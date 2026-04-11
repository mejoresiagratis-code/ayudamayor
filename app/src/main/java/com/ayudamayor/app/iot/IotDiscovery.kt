package com.ayudamayor.app.iot

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * IotDiscovery v2 - descubrimiento IoT con:
 * 1. SSDP/UPnP - nombre real de Samsung TV, LG, etc.
 * 2. Samsung TV API REST (puerto 8001)
 * 3. mDNS - Shelly, ESP, Tasmota, Hue, Chromecast
 * 4. Escaneo LAN con filtrado inteligente (descarta PCs/moviles)
 * 5. Control nativo Samsung TV (power/vol/teclas)
 */
class IotDiscovery(private val context: Context) {

    companion object {
        private val MDNS_SERVICES = listOf(
            "_http._tcp", "_hap._tcp", "_miio._udp",
            "_googlecast._tcp", "_esphomelib._tcp", "_arduino._tcp"
        )
        private val IOT_PORTS = listOf(80, 8001, 8009, 9080, 55000, 6466, 5555, 1883, 7676, 8888, 8080)
        private val NON_IOT_PORTS = setOf(22, 3389, 62078, 5985, 548)
        private const val SSDP_ADDRESS  = "239.255.255.250"
        private const val SSDP_PORT     = 1900
        private const val SSDP_TIMEOUT  = 3000
        private const val SCAN_TIMEOUT  = 250
        private const val HTTP_TIMEOUT  = 2000
        private const val CHUNK_SIZE    = 50
    }

    private val devices   = ConcurrentHashMap<String, JSONObject>()
    private var nsdMgr    : NsdManager? = null
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val scope     = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── API publica ─────────────────────────────────────────────────────────

    fun start(onUpdate: (String) -> Unit) {
        devices.clear()
        val wifi = getWifiInfo()
        onUpdate(buildResult(wifi))
        scope.launch {
            launch { runSsdp(wifi, onUpdate) }
            launch { runMdns(wifi, onUpdate) }
            val subnet = wifi.optString("subnet")
            if (subnet.isNotEmpty()) launch { runScan(subnet, wifi, onUpdate) }
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        nsdMgr?.let { m -> listeners.forEach { try { m.stopServiceDiscovery(it) } catch (_: Exception) {} } }
        listeners.clear()
        devices.clear()
    }

    fun getWifiInfo(): JSONObject {
        val wm   = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val dhcp = wm.dhcpInfo
        val ip   = intToIp(info.ipAddress)
        return JSONObject().apply {
            put("ssid",    info.ssid?.removeSurrounding("\"") ?: "")
            put("bssid",   info.bssid ?: "")
            put("ip",      ip)
            put("gateway", intToIp(dhcp.gateway))
            put("netmask", intToIp(dhcp.netmask))
            put("subnet",  if (ip.isNotEmpty()) ip.substringBeforeLast(".") else "")
            put("rssi",    info.rssi)
        }
    }

    // ── Control Samsung TV ─────────────────────────────────────────────────

    suspend fun controlSamsungTV(ip: String, command: String, token: String? = null): SamsungTVController.Result =
        SamsungTVController(ip).sendKey(command, token)

    suspend fun getSamsungInfo(ip: String): JSONObject? =
        SamsungTVController(ip).getInfo()

    // ── SSDP / UPnP ────────────────────────────────────────────────────────

    private suspend fun runSsdp(wifi: JSONObject, onUpdate: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket()
            socket.soTimeout = SSDP_TIMEOUT
            val msg = "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDRESS:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: ssdp:all\r\n\r\n"
            val addr = InetAddress.getByName(SSDP_ADDRESS)
            socket.send(DatagramPacket(msg.toByteArray(), msg.length, addr, SSDP_PORT))
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + SSDP_TIMEOUT
            while (System.currentTimeMillis() < deadline) {
                try {
                    val resp = DatagramPacket(buf, buf.size)
                    socket.receive(resp)
                    val text = String(resp.data, 0, resp.length)
                    val ip   = resp.address.hostAddress ?: continue
                    val location = extractHeader(text, "LOCATION")
                    val server   = extractHeader(text, "SERVER")
                    val st       = extractHeader(text, "ST")
                    launch {
                        val d   = fetchUpnpDevice(location, ip, server, st)
                        val key = "$ip:upnp"
                        val old = devices[key]
                        if (old == null || score(d) > score(old)) { devices[key] = d; onUpdate(buildResult(wifi)) }
                    }
                } catch (_: Exception) {}
            }
            socket.close()
        } catch (_: Exception) {}
    }

    private suspend fun fetchUpnpDevice(location: String?, ip: String, server: String?, st: String?): JSONObject =
        withContext(Dispatchers.IO) {
            var name = "Dispositivo $ip"; var brand = ""; var type = "generic"; var model = ""
            if (!location.isNullOrEmpty()) {
                try {
                    val conn = URL(location).openConnection() as HttpURLConnection
                    conn.connectTimeout = HTTP_TIMEOUT; conn.readTimeout = HTTP_TIMEOUT
                    val xml = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    xmlTag(xml, "friendlyName")?.let { name  = it }
                    xmlTag(xml, "manufacturer") ?.let { brand = it }
                    val mn = xmlTag(xml, "modelName") ?: ""
                    val mn2= xmlTag(xml, "modelNumber") ?: ""
                    if (mn.isNotEmpty()) model = if (mn2.isNotEmpty()) "$mn ($mn2)" else mn
                } catch (_: Exception) {}
            }
            // Samsung TV port check
            if (isPortOpen(ip, 8001) || brand.lowercase().contains("samsung")) {
                fetchSamsungInfo(ip)?.let { tv ->
                    name = tv.optString("name", name); brand = "Samsung"; model = tv.optString("model", model); type = "tv"
                }
            }
            if (type == "generic") {
                val c = "${brand.lowercase()} ${server?.lowercase()} ${st?.lowercase()}"
                type = when {
                    c.contains("samsung")  -> "tv"
                    c.contains("lg")       -> "tv"
                    c.contains("sony")     -> "tv"
                    c.contains("philips") && c.contains("hue") -> "light"
                    c.contains("philips")  -> "tv"
                    c.contains("shelly")   -> "plug"
                    c.contains("tasmota")  -> "plug"
                    c.contains("google") || c.contains("chromecast") -> "chromecast"
                    c.contains("printer") || c.contains("impresora")  -> "printer"
                    c.contains("router") || c.contains("gateway")     -> "router"
                    c.contains("camera") || c.contains("cam")         -> "cam"
                    else -> "generic"
                }
            }
            JSONObject().apply {
                put("id", "upnp-${ip.replace('.', '-')}"); put("name", name)
                put("brand", brand); put("model", model); put("type", type)
                put("ip", ip); put("port", if (type == "tv") 8001 else 80)
                put("source", "ssdp"); put("services", JSONArray().apply { put("upnp") })
            }
        }

    private suspend fun fetchSamsungInfo(ip: String): JSONObject? =
        SamsungTVController(ip).getInfo()

    // ── mDNS ───────────────────────────────────────────────────────────────

    private fun runMdns(wifi: JSONObject, onUpdate: (String) -> Unit) {
        nsdMgr = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        MDNS_SERVICES.forEach { svc ->
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(s: String, e: Int) {}
                override fun onStopDiscoveryFailed(s: String, e: Int) {}
                override fun onDiscoveryStarted(s: String) {}
                override fun onDiscoveryStopped(s: String) {}
                override fun onServiceLost(i: NsdServiceInfo) {}
                override fun onServiceFound(i: NsdServiceInfo) { resolveNsd(i, svc, wifi, onUpdate) }
            }
            listeners.add(l)
            try { nsdMgr?.discoverServices(svc, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
        }
    }

    private fun resolveNsd(info: NsdServiceInfo, svc: String, wifi: JSONObject, onUpdate: (String) -> Unit) {
        nsdMgr?.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceResolved(i: NsdServiceInfo) {
                val ip   = i.host?.hostAddress ?: return
                val port = i.port
                val name = i.serviceName ?: "Dispositivo"
                val key  = "$ip:$port"
                val svcs = (devices[key]?.optJSONArray("services") ?: JSONArray()).apply { put(svc) }
                val type = detectTypeByName(name) ?: detectTypeBySvc(svc)
                devices[key] = JSONObject().apply {
                    put("id", "mdns-${sanitize(name)}"); put("name", name)
                    put("brand", brandOf(type)); put("type", type)
                    put("ip", ip); put("port", port); put("source", "mdns"); put("services", svcs)
                }
                scope.launch {
                    if (isPortOpen(ip, 8001)) {
                        fetchSamsungInfo(ip)?.let { tv ->
                            devices[key]?.apply { put("name", tv.optString("name")); put("brand","Samsung"); put("type","tv") }
                        }
                    } else { enrichHttp(devices[key]!!, ip, port) }
                    onUpdate(buildResult(wifi))
                }
            }
        })
    }

    // ── Escaneo LAN con filtrado ────────────────────────────────────────────

    private suspend fun runScan(subnet: String, wifi: JSONObject, onUpdate: (String) -> Unit) =
        withContext(Dispatchers.IO) {
            val myOctet  = wifi.optString("ip").substringAfterLast(".")
            val gwOctet  = wifi.optString("gateway").substringAfterLast(".")
            (1..254).chunked(CHUNK_SIZE).forEach { chunk ->
                chunk.map { i ->
                    async {
                        if (i.toString() == myOctet || i.toString() == gwOctet) return@async
                        val host = "$subnet.$i"
                        if (!isReachable(host)) return@async
                        val openIot    = IOT_PORTS.filter { isPortOpen(host, it) }
                        val openNonIot = NON_IOT_PORTS.filter { isPortOpen(host, it) }
                        if (openIot.isEmpty()) return@async   // solo PC/movil → ignorar
                        val port = openIot.first()
                        val key  = "$host:$port"
                        if (devices.containsKey(key)) return@async
                        val d = JSONObject().apply {
                            put("id", "scan-${host.replace('.', '-')}"); put("name", "Dispositivo $host")
                            put("brand", ""); put("model", ""); put("type", "generic")
                            put("ip", host); put("port", port); put("source", "scan"); put("services", JSONArray())
                        }
                        when {
                            8001 in openIot -> fetchSamsungInfo(host)?.let { tv ->
                                d.put("name", tv.optString("name", "Samsung Smart TV"))
                                d.put("brand", "Samsung"); d.put("type", "tv"); d.put("port", 8001)
                            }
                            9080 in openIot -> { d.put("name","LG Smart TV"); d.put("brand","LG"); d.put("type","tv"); d.put("port",9080) }
                            8009 in openIot -> { d.put("name","Chromecast"); d.put("brand","Google"); d.put("type","chromecast"); d.put("port",8009) }
                            80 in openIot || 8080 in openIot -> enrichHttp(d, host, if (80 in openIot) 80 else 8080)
                        }
                        devices[key] = d
                        onUpdate(buildResult(wifi))
                    }
                }.awaitAll()
            }
            onUpdate(buildResult(wifi))
        }

    // ── HTTP fingerprinting ─────────────────────────────────────────────────

    private suspend fun enrichHttp(device: JSONObject, ip: String, port: Int) = withContext(Dispatchers.IO) {
        val url = if (port == 80) "http://$ip/" else "http://$ip:$port/"
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = HTTP_TIMEOUT; conn.readTimeout = HTTP_TIMEOUT
            val headers = conn.headerFields.entries.joinToString(" ") { (k, v) -> "${k ?: ""}:${v.joinToString()}" }
            val body = try { conn.inputStream.bufferedReader().readText().take(3000) } catch (_: Exception) { "" }
            conn.disconnect()
            val t = "${headers.lowercase()} ${body.lowercase()}"
            val (type, brand, name) = when {
                t.contains("shelly")     -> Triple("plug",  "Shelly",
                    Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: "Shelly")
                t.contains("tasmota")    -> Triple("plug",  "Tasmota",
                    Regex("<title>([^<]+)</title>").find(body)?.groupValues?.get(1) ?: "Tasmota")
                t.contains("esphome") || t.contains("esp32") || t.contains("esp8266")
                                         -> Triple("esp",   "ESP", "ESP Device $ip")
                t.contains("hue bridge") || t.contains("ipbridge")
                                         -> Triple("light", "Philips Hue", "Philips Hue Bridge")
                t.contains("home assistant") -> Triple("generic", "Home Assistant", "Home Assistant")
                else -> Triple(device.optString("type","generic"), device.optString("brand",""), device.optString("name","Dispositivo $ip"))
            }
            device.put("type", type); device.put("brand", brand); device.put("name", name)
        } catch (_: Exception) {}
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun isReachable(host: String) = try { InetAddress.getByName(host).isReachable(SCAN_TIMEOUT) } catch (_: Exception) { false }
    private fun isPortOpen(host: String, port: Int) = try { Socket().use { it.connect(InetSocketAddress(host, port), SCAN_TIMEOUT); true } } catch (_: Exception) { false }
    private fun xmlTag(xml: String, tag: String) = Regex("<$tag>([^<]+)</$tag>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim()
    private fun extractHeader(r: String, h: String) = Regex("$h:\\s*(.+)", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1)?.trim()
    private fun detectTypeByName(n: String): String? { val l = n.lowercase(); return when { l.contains("shelly") -> "plug"; l.contains("hue") -> "light"; l.contains("tasmota") -> "plug"; l.contains("esp") -> "esp"; l.contains("chromecast") -> "chromecast"; l.contains("samsung") -> "tv"; else -> null } }
    private fun detectTypeBySvc(s: String) = when { s.contains("googlecast") -> "chromecast"; s.contains("hap") -> "homekit"; s.contains("miio") -> "xiaomi"; s.contains("esphome") -> "esp"; else -> "generic" }
    private fun brandOf(type: String) = when (type) { "tv" -> "Samsung"; "chromecast" -> "Google"; "light" -> "Philips"; "plug" -> "Shelly"; "esp" -> "Espressif"; "xiaomi" -> "Xiaomi"; else -> "" }
    private fun sanitize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-")
    private fun intToIp(ip: Int) = if (ip == 0) "" else "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    private fun score(d: JSONObject): Int { var s = 0; if (!d.optString("name").startsWith("Dispositivo")) s += 3; if (d.optString("brand").isNotEmpty()) s += 2; if (d.optString("model").isNotEmpty()) s += 1; if (d.optString("source") == "ssdp") s += 2; return s }
    private fun isIotRelevant(d: JSONObject) = d.optString("type") !in setOf("router", "printer", "workstation")

    private fun buildResult(wifi: JSONObject): String {
        val byIp = LinkedHashMap<String, JSONObject>()
        devices.values.forEach { d ->
            val ip = d.optString("ip")
            val ex = byIp[ip]
            if (ex == null || score(d) > score(ex)) byIp[ip] = d
        }
        val arr = JSONArray()
        byIp.values.filter { isIotRelevant(it) }.forEach { arr.put(it) }
        return JSONObject().apply { put("wifi", wifi); put("devices", arr) }.toString()
    }
}
