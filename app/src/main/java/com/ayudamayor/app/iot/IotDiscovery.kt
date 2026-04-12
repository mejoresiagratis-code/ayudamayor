package com.ayudamayor.app.iot

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.*

/**
 * IotDiscovery — escanea la red local para encontrar dispositivos IoT.
 *
 * Fixes aplicados:
 * - MulticastLock adquirido antes de SSDP (sin él Android descarta paquetes multicast)
 * - getSubnet() con fallback via NetworkInterface (funciona en Android 12+ sin GPS)
 * - Timeout de conexión ajustado a 500ms (más realista en LAN)
 * - Samsung TV añadido a escaneo en puerto 8001 con identificación mejorada
 */
class IotDiscovery(
    private val context: Context,
    private val onResult: (String) -> Unit
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        job = scope.launch {
            // Adquirir MulticastLock — imprescindible para recibir respuestas SSDP
            acquireMulticastLock()

            val subnet   = getSubnet()
            val wifiInfo = getWifiInfo()

            if (subnet == null) {
                // Sin subred — notificar error sin disparar paso de resultados
                onResult(org.json.JSONObject().apply {
                    put("error", "no_subnet")
                    put("devices", org.json.JSONArray())
                    put("wifi", wifiInfo)
                    put("subnet", "")
                }.toString())
                releaseMulticastLock()
                return@launch
            }

            val found = mutableListOf<JSONObject>()

            // Fase 1: SSDP — muy rápido, encuentra Samsung TV, Hue, Shelly
            val ssdpDevices = discoverSsdp()
            synchronized(found) { found.addAll(ssdpDevices) }
            if (found.isNotEmpty()) emitResult(found, wifiInfo, subnet)

            // Fase 2: escaneo TCP paralelo de toda la subred /24
            val jobs = (1..254).map { i ->
                async {
                    val ip = "$subnet.$i"
                    scanHost(ip)?.let { device ->
                        // Evitar duplicados con SSDP
                        val alreadyFound = synchronized(found) {
                            found.any { it.optString("ip") == ip }
                        }
                        if (!alreadyFound) {
                            synchronized(found) { found.add(device) }
                            emitResult(found, wifiInfo, subnet)
                        }
                    }
                }
            }
            jobs.awaitAll()

            releaseMulticastLock()
            emitResult(found, wifiInfo, subnet) // resultado final
        }
    }

    fun stop() {
        job?.cancel()
        scope.coroutineContext.cancelChildren()
        releaseMulticastLock()
    }

    // ── MulticastLock ─────────────────────────────────────────
    private fun acquireMulticastLock() {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wm.createMulticastLock("AyudaMayor_IoT").also {
                it.setReferenceCounted(true)
                it.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (_: Exception) {}
        multicastLock = null
    }

    // ── Obtener subred — con fallback para Android 12+ ────────
    @Suppress("DEPRECATION")
    private fun getSubnet(): String? {
        // Método 1: WifiManager.connectionInfo (API <31 o con GPS concedido)
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wm?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}"
            }
        } catch (_: Exception) {}

        // Método 2: NetworkInterface (funciona sin permiso de ubicación)
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces.toList()) {
                if (!iface.isUp || iface.isLoopback || iface.name.startsWith("dummy")) continue
                // Solo interfaces WiFi/Ethernet (wlan0, eth0, ap0...)
                if (!iface.name.startsWith("wlan") &&
                    !iface.name.startsWith("eth") &&
                    !iface.name.startsWith("ap")) continue
                for (addr in iface.interfaceAddresses) {
                    val a = addr.address
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        val parts = a.hostAddress?.split(".") ?: continue
                        if (parts.size == 4) return "${parts[0]}.${parts[1]}.${parts[2]}"
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    @Suppress("DEPRECATION")
    private fun getWifiInfo(): JSONObject {
        var ssid  = ""
        var ip    = ""
        var rssi  = 0
        var subnet = getSubnet() ?: ""

        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wm?.connectionInfo
            ssid  = info?.ssid?.trim('"') ?: ""
            rssi  = info?.rssi ?: 0
            val ipInt = info?.ipAddress ?: 0
            if (ipInt != 0) {
                ip = "${ipInt and 0xff}.${ipInt shr 8 and 0xff}.${ipInt shr 16 and 0xff}.${ipInt shr 24 and 0xff}"
            }
        } catch (_: Exception) {}

        // Fallback IP desde NetworkInterface
        if (ip.isEmpty()) {
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
                    if (!iface.isUp || iface.isLoopback) return@forEach
                    iface.interfaceAddresses.forEach { addr ->
                        val a = addr.address
                        if (a is Inet4Address && !a.isLoopbackAddress) {
                            ip = a.hostAddress ?: ""
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        return JSONObject().apply {
            put("ssid",   ssid)
            put("ip",     ip)
            put("rssi",   rssi)
            put("subnet", subnet)
        }
    }

    // ── SSDP Discovery ────────────────────────────────────────
    private suspend fun discoverSsdp(): List<JSONObject> = withContext(Dispatchers.IO) {
        val results = mutableListOf<JSONObject>()
        try {
            val socket = MulticastSocket(null).also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(0))
                it.soTimeout    = 4000
                it.timeToLive   = 4
            }

            val group   = InetAddress.getByName("239.255.255.250")
            val msg     = "M-SEARCH * HTTP/1.1\r\n" +
                          "HOST: 239.255.255.250:1900\r\n" +
                          "MAN: \"ssdp:discover\"\r\n" +
                          "MX: 3\r\n" +
                          "ST: ssdp:all\r\n\r\n"
            val data    = msg.toByteArray()
            socket.send(DatagramPacket(data, data.size, group, 1900))

            val buf      = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length)
                    val ip       = packet.address.hostAddress ?: continue
                    parseSsdpResponse(ip, response)?.let {
                        if (results.none { r -> r.optString("ip") == ip }) results.add(it)
                    }
                } catch (_: SocketTimeoutException) { break }
            }
            socket.close()
        } catch (_: Exception) {}
        results
    }

    private fun parseSsdpResponse(ip: String, response: String): JSONObject? {
        val headers = response.lines().associate { line ->
            val idx = line.indexOf(':')
            if (idx > 0) line.substring(0, idx).trim().uppercase() to line.substring(idx + 1).trim()
            else "" to ""
        }
        val server  = headers["SERVER"]  ?: ""
        val st      = headers["ST"]      ?: ""
        val usn     = headers["USN"]     ?: ""
        val all     = "$server $st $usn"

        val (type, brand) = when {
            all.contains("Samsung", ignoreCase = true) -> Pair("tv",    "Samsung")
            all.contains("LG",      ignoreCase = true) -> Pair("tv",    "LG")
            all.contains("Sony",    ignoreCase = true) -> Pair("tv",    "Sony")
            all.contains("Philips", ignoreCase = true) ||
            all.contains(":hue",    ignoreCase = true) -> Pair("light", "Philips Hue")
            all.contains("Shelly",  ignoreCase = true) -> Pair("plug",  "Shelly")
            all.contains("Tasmota", ignoreCase = true) -> Pair("plug",  "Tasmota")
            all.contains("Kasa",    ignoreCase = true) -> Pair("plug",  "TP-Link Kasa")
            all.contains("WeMo",    ignoreCase = true) -> Pair("plug",  "WeMo")
            all.contains("MediaRenderer", ignoreCase = true) -> Pair("tv", "")
            all.contains("MediaServer",   ignoreCase = true) -> Pair("other", "")
            all.isNotBlank() && all.trim() != "  " -> Pair("other", "")
            else -> return null
        }

        val port = if (type == "tv") 8001 else 80
        return JSONObject().apply {
            put("ip",       ip)
            put("name",     if (brand.isNotEmpty()) "$brand en $ip" else "Dispositivo en $ip")
            put("brand",    brand)
            put("type",     type)
            put("source",   "ssdp")
            put("protocol", if (type == "tv") "other" else "shelly")
            put("port",     port)
            put("detected", true)
        }
    }

    // ── Escaneo TCP de puertos ────────────────────────────────
    private suspend fun scanHost(ip: String): JSONObject? = withContext(Dispatchers.IO) {
        // Puertos por orden de probabilidad — Samsung TV primero
        val ports = listOf(
            8001 to "tv",    // Samsung TV WebSocket
            8002 to "tv",    // Samsung TV WSS
            80   to "other", // HTTP genérico (routers, cámaras, enchufes)
            443  to "other", // HTTPS
            81   to "cam",   // Cámaras IP
            554  to "cam",   // RTSP
            1883 to "other", // MQTT
        )
        for ((port, hint) in ports) {
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(ip, port), 500)
                    val (type, name) = identifyByPort(ip, port, hint)
                    return@withContext JSONObject().apply {
                        put("ip",       ip)
                        put("name",     name)
                        put("type",     type)
                        put("brand",    "")
                        put("source",   "scan")
                        put("protocol", if (type == "tv") "other" else "other")
                        put("port",     port)
                        put("detected", true)
                    }
                }
            } catch (_: Exception) {}
        }
        null
    }

    private fun identifyByPort(ip: String, port: Int, hint: String): Pair<String, String> = when (port) {
        8001, 8002 -> Pair("tv",    "TV Samsung ($ip)")
        554        -> Pair("cam",   "Cámara RTSP ($ip)")
        81         -> Pair("cam",   "Cámara IP ($ip)")
        1883       -> Pair("other", "Broker MQTT ($ip)")
        else       -> Pair(hint,    "Dispositivo ($ip)")
    }

    private fun emitResult(devices: List<JSONObject>, wifi: JSONObject, subnet: String) {
        onResult(JSONObject().apply {
            put("devices", JSONArray(devices))
            put("wifi",    wifi)
            put("subnet",  subnet)
        }.toString())
    }
}
