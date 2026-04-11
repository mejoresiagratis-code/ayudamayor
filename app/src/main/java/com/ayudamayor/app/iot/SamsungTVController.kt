package com.ayudamayor.app.iot

import org.json.JSONObject
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLContext

/**
 * SamsungTVController — control Samsung TV vía WebSocket RFC 6455.
 * Puerto 8001 (HTTP) / 8002 (WSS). Usa token para autenticación persistente.
 * Compatible con Samsung Tizen TV 2016+.
 */
class SamsungTVController {

    companion object {
        private const val APP_NAME     = "AyudaMayor"
        private const val APP_NAME_B64 = "QXl1ZGFNYW9"  // base64("AyudaMayor") parcial
        private const val TIMEOUT_MS   = 5000
    }

    fun sendCommand(ip: String, cmd: String, token: String): String {
        return try {
            // Intentar primero por WS sin TLS (puerto 8001)
            val result = tryWebSocket(ip, 8001, cmd, token, useTls = false)
            if (result.optBoolean("ok", false)) return result.toString()
            // Fallback WS con TLS (puerto 8002)
            tryWebSocket(ip, 8002, cmd, token, useTls = true).toString()
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("msg", e.message ?: "Error de conexión")
            }.toString()
        }
    }

    private fun tryWebSocket(ip: String, port: Int, cmd: String, token: String, useTls: Boolean): JSONObject {
        val socket: Socket = if (useTls) {
            val ssl = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(TrustAllCerts()), SecureRandom())
            }
            ssl.socketFactory.createSocket().also {
                it.connect(InetSocketAddress(ip, port), TIMEOUT_MS)
            }
        } else {
            Socket().also { it.connect(InetSocketAddress(ip, port), TIMEOUT_MS) }
        }

        socket.soTimeout = TIMEOUT_MS
        val out = DataOutputStream(socket.getOutputStream())
        val inp = BufferedReader(InputStreamReader(socket.getInputStream()))

        // Handshake WebSocket
        val wsKey = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val tokenParam = if (token.isNotBlank()) "&token=$token" else ""
        val path = "/api/v2/channels/samsung.remote.control?name=${APP_NAME_B64}$tokenParam"

        out.writeBytes("GET $path HTTP/1.1\r\n")
        out.writeBytes("Host: $ip:$port\r\n")
        out.writeBytes("Upgrade: websocket\r\n")
        out.writeBytes("Connection: Upgrade\r\n")
        out.writeBytes("Sec-WebSocket-Key: $wsKey\r\n")
        out.writeBytes("Sec-WebSocket-Version: 13\r\n\r\n")
        out.flush()

        // Leer respuesta HTTP
        val statusLine = inp.readLine() ?: return error("Sin respuesta del TV")
        var newToken = token
        var needsApproval = false

        if (!statusLine.contains("101")) {
            // 403/401 pueden indicar que necesita aprobación en la TV
            needsApproval = statusLine.contains("403") || statusLine.contains("401")
            return JSONObject().apply {
                put("ok", false)
                put("needsApproval", needsApproval)
                put("msg", "TV respondió: $statusLine")
            }
        }

        // Consumir cabeceras
        while (true) {
            val line = inp.readLine() ?: break
            if (line.isBlank()) break
            if (line.startsWith("x-auth-token", ignoreCase = true)) {
                newToken = line.substringAfter(":").trim()
            }
        }

        // Enviar comando RemoteControl como frame WebSocket texto
        val key = samsungKey(cmd)
        val payload = """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$key","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
        sendWsFrame(out, payload)
        out.flush()

        socket.close()

        return JSONObject().apply {
            put("ok",    true)
            put("token", newToken)
            put("needsApproval", false)
        }
    }

    private fun sendWsFrame(out: DataOutputStream, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        out.write(0x81)  // FIN + opcode TEXT
        when {
            data.size <= 125 -> { out.write(0x80 or data.size); out.write(mask) }
            data.size <= 65535 -> {
                out.write(0x80 or 126)
                out.write(data.size shr 8 and 0xFF)
                out.write(data.size and 0xFF)
                out.write(mask)
            }
            else -> {
                out.write(0x80 or 127)
                repeat(8) { i -> out.write((data.size.toLong() shr (56 - 8 * i) and 0xFF).toInt()) }
                out.write(mask)
            }
        }
        val masked = ByteArray(data.size) { i -> (data[i].toInt() xor mask[i % 4].toInt()).toByte() }
        out.write(masked)
    }

    private fun samsungKey(cmd: String) = when (cmd.lowercase()) {
        "power", "toggle"   -> "KEY_POWER"
        "vol_up"            -> "KEY_VOLUP"
        "vol_down"          -> "KEY_VOLDOWN"
        "mute"              -> "KEY_MUTE"
        "up"                -> "KEY_UP"
        "down"              -> "KEY_DOWN"
        "left"              -> "KEY_LEFT"
        "right"             -> "KEY_RIGHT"
        "ok", "enter"       -> "KEY_ENTER"
        "back"              -> "KEY_RETURN"
        "home"              -> "KEY_HOME"
        "menu"              -> "KEY_MENU"
        "source"            -> "KEY_SOURCE"
        "ch_up"             -> "KEY_CHUP"
        "ch_down"           -> "KEY_CHDOWN"
        "play"              -> "KEY_PLAY"
        "pause"             -> "KEY_PAUSE"
        "stop"              -> "KEY_STOP"
        else                -> cmd.uppercase().let { if (it.startsWith("KEY_")) it else "KEY_$it" }
    }

    private fun error(msg: String) = JSONObject().apply { put("ok", false); put("msg", msg) }
}

/** Trust manager que acepta cualquier certificado — solo para LAN local */
private class TrustAllCerts : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
    override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
}
