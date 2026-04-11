package com.ayudamayor.app.iot

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.*

/**
 * IotDiscovery — escanea la red local para encontrar dispositivos IoT.
 * Usa SSDP (UPnP) + escaneo de puertos conocidos (8001 Samsung, 80 HTTP, 1900 SSDP).
 * Resultados entregados via callback en el formato que espera iot_modal.js.
 */
class IotDiscovery(
    private val context: Context,
    private val onResult: (String) -> Unit
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        job = scope.launch {
            val subnet = getSubnet() ?: return@launch
            val wifiInfo = getWifiInfo()
            val found = mutableListOf<JSONObject>()

            // Fase 1: SSDP broadcast
            val ssdpDevices = discoverSsdp()
            found.addAll(ssdpDevices)
            emitResult(found, wifiInfo, subnet)

            // Fase 2: escaneo de puertos en toda la subred /24
            val jobs = (1..254).map { i ->
                async {
                    val ip = "$subnet.$i"
                    scanHost(ip)?.let { device ->
                        synchronized(found) { found.add(device) }
                        emitResult(found, wifiInfo, subnet)
                    }
                }
            }
            jobs.awaitAll()
            emitResult(found, wifiInfo, subnet) // resultado final
        }
    }

    fun stop() {
        job?.cancel()
        scope.coroutineContext.cancelChildren()
    }

    @Suppress("DEPRECATION")
    private fun getSubnet(): String? {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ip = wm.connectionInfo.ipAddress
        if (ip == 0) return null
        return "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}"
    }

    @Suppress("DEPRECATION")
    private fun getWifiInfo(): JSONObject {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return JSONObject().apply {
            put("ssid",   wm?.connectionInfo?.ssid?.trim('"') ?: "")
            put("ip",     wm?.connectionInfo?.ipAddress?.let { ip ->
                "${ip and 0xff}.${ip shr 8 and 0xff}.${ip shr 16 and 0xff}.${ip shr 24 and 0xff}"
            } ?: "")
            put("rssi",   wm?.connectionInfo?.rssi ?: 0)
            put("subnet", getSubnet() ?: "")
        }
    }

    private suspend fun discoverSsdp(): List<JSONObject> = withContext(Dispatchers.IO) {
        val results = mutableListOf<JSONObject>()
        try {
            val socket = MulticastSocket(1900)
            socket.soTimeout = 3000
            val group = InetAddress.getByName("239.255.255.250")
            socket.joinGroup(group)

            val msg = "M-SEARCH * HTTP/1.1\r\n" +
                      "HOST: 239.255.255.250:1900\r\n" +
                      "MAN: \"ssdp:discover\"\r\n" +
                      "MX: 3\r\n" +
                      "ST: ssdp:all\r\n\r\n"
            val data = msg.toByteArray()
            socket.send(DatagramPacket(data, data.size, group, 1900))

            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val response = String(packet.data, 0, packet.length)
                    val ip = packet.address.hostAddress ?: continue
                    val device = parseSsdpResponse(ip, response)
                    if (device != null) results.add(device)
                } catch (_: SocketTimeoutException) { break }
            }
            socket.leaveGroup(group)
            socket.close()
        } catch (_: Exception) {}
        results
    }

    private fun parseSsdpResponse(ip: String, response: String): JSONObject? {
        val server = response.lines()
            .firstOrNull { it.startsWith("SERVER:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: ""
        val st = response.lines()
            .firstOrNull { it.startsWith("ST:", ignoreCase = true) }
            ?.substringAfter(":")?.trim() ?: ""

        val (type, brand) = when {
            server.contains("Samsung", ignoreCase = true) || st.contains("samsung", ignoreCase = true)
                -> Pair("tv", "Samsung")
            server.contains("LG", ignoreCase = true)
                -> Pair("tv", "LG")
            server.contains("Philips", ignoreCase = true) || st.contains("hue", ignoreCase = true)
                -> Pair("light", "Philips Hue")
            server.contains("Shelly", ignoreCase = true)
                -> Pair("plug", "Shelly")
            server.contains("Tasmota", ignoreCase = true)
                -> Pair("plug", "Tasmota")
            st.contains("MediaRenderer", ignoreCase = true)
                -> Pair("tv", "")
            else -> return null
        }

        return JSONObject().apply {
            put("ip", ip)
            put("name", if (brand.isNotEmpty()) "$brand $type" else "$type en $ip")
            put("brand", brand)
            put("type", type)
            put("source", "ssdp")
            put("protocol", if (type == "tv") "other" else "shelly")
            put("port", if (type == "tv") 8001 else 80)
            put("detected", true)
        }
    }

    private suspend fun scanHost(ip: String): JSONObject? = withContext(Dispatchers.IO) {
        val ports = listOf(
            8001 to "tv",      // Samsung TV WebSocket
            8002 to "tv",      // Samsung TV WSS
            80   to "other",   // HTTP genérico
            81   to "cam",     // Cámaras IP comunes
            554  to "cam",     // RTSP
        )
        for ((port, hint) in ports) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(ip, port), 300)
                sock.close()

                // Intentar identificar el dispositivo por el puerto
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
            } catch (_: Exception) {}
        }
        null
    }

    private fun identifyByPort(ip: String, port: Int, hint: String): Pair<String, String> = when (port) {
        8001, 8002 -> Pair("tv", "TV Samsung ($ip)")
        554        -> Pair("cam", "Cámara IP ($ip)")
        81         -> Pair("cam", "Cámara ($ip)")
        else       -> Pair(hint, "Dispositivo ($ip)")
    }

    private fun emitResult(devices: List<JSONObject>, wifi: JSONObject, subnet: String) {
        val json = JSONObject().apply {
            put("devices", JSONArray(devices))
            put("wifi",    wifi)
            put("subnet",  subnet)
        }
        onResult(json.toString())
    }
}
