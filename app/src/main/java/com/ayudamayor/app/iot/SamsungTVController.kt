package com.ayudamayor.app.iot

import org.json.JSONObject
import java.io.*
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLContext

/**
 * SamsungTVController — control Samsung TV vía WebSocket RFC 6455.
 * Puerto 8001 (HTTP) / 8002 (WSS). Usa token para autenticación persistente.
 * Compatible con Samsung Tizen TV 2016+.
 *
 * Protocolo real de la API v2:
 *  1. GET /api/v2/channels/samsung.remote.control?name=<base64AppName>[&token=<token>]
 *  2. TV responde 101 Switching Protocols
 *  3. TV envía frame WS JSON: {"method":"ms.channel.connect","params":{"data":{"token":"XXXXXXXX",...}}}
 *  4. Si token vacío/nuevo: TV muestra diálogo de aprobación al usuario
 *  5. Tras aprobación (o si token ya conocido): enviamos el comando KEY_*
 */
class SamsungTVController {

    companion object {
        private const val APP_NAME     = "AyudaMayor"
        // base64("AyudaMayor") sin padding — verificado: echo -n "AyudaMayor" | base64 | tr -d '='
        private const val APP_NAME_B64 = "QXl1ZGFNYXlvcg"
        private const val TIMEOUT_MS   = 5000
        // Cmd especial: solo conectar para obtener/renovar token sin enviar tecla
        const val CMD_PAIR = "__pair__"
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
        val rawIn = socket.getInputStream()

        // ── Handshake WebSocket ───────────────────────────────
        val wsKey = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val tokenParam = if (token.isNotBlank()) "&token=$token" else ""
        val path = "/api/v2/channels/samsung.remote.control?name=$APP_NAME_B64$tokenParam"

        out.writeBytes("GET $path HTTP/1.1\r\n")
        out.writeBytes("Host: $ip:$port\r\n")
        out.writeBytes("Upgrade: websocket\r\n")
        out.writeBytes("Connection: Upgrade\r\n")
        out.writeBytes("Sec-WebSocket-Key: $wsKey\r\n")
        out.writeBytes("Sec-WebSocket-Version: 13\r\n\r\n")
        out.flush()

        // ── Leer respuesta HTTP byte a byte (sin BufferedReader) ─
        // BufferedReader consume más bytes de los necesarios y se lleva
        // el frame WebSocket de bienvenida, impidiendo leer el token.
        val statusLine = readHttpLine(rawIn)
            ?: return error("Sin respuesta del TV")

        if (!statusLine.contains("101")) {
            val needsApproval = statusLine.contains("403") || statusLine.contains("401")
            return JSONObject().apply {
                put("ok", false)
                put("needsApproval", needsApproval)
                put("msg", "TV respondió: $statusLine")
            }
        }

        // Consumir cabeceras HTTP hasta línea vacía (byte a byte)
        while (true) {
            val line = readHttpLine(rawIn) ?: break
            if (line.isBlank()) break
        }

        // ── Leer frame WebSocket de bienvenida ────────────────
        // Ahora rawIn está exactamente en el inicio del frame WS,
        // sin bytes consumidos de más por un buffer.
        var newToken = token
        var needsApproval = false

        try {
            socket.soTimeout = 3000
            val b0 = rawIn.read()
            val b1 = rawIn.read()
            if (b0 != -1 && b1 != -1) {
                val masked = (b1 and 0x80) != 0
                var payloadLen = (b1 and 0x7F).toLong()
                payloadLen = when {
                    payloadLen == 126L -> ((rawIn.read().toLong() shl 8) or rawIn.read().toLong())
                    payloadLen == 127L -> { var len = 0L; repeat(8) { len = (len shl 8) or rawIn.read().toLong() }; len }
                    else -> payloadLen
                }
                val maskBytes = if (masked) ByteArray(4).also { rawIn.read(it) } else null
                val payload = ByteArray(payloadLen.toInt())
                var bytesRead = 0
                while (bytesRead < payload.size) {
                    val r = rawIn.read(payload, bytesRead, payload.size - bytesRead)
                    if (r == -1) break
                    bytesRead += r
                }
                if (maskBytes != null) {
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskBytes[i % 4].toInt()).toByte()
                }
                val frameJson = String(payload, Charsets.UTF_8)
                try {
                    val obj = JSONObject(frameJson)
                    val data = obj.optJSONObject("params")?.optJSONObject("data")
                    val tvToken = data?.optString("token", "")
                    if (!tvToken.isNullOrBlank()) newToken = tvToken
                    if (obj.optString("method", "") == "ms.channel.unauthorized") needsApproval = true
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // Timeout — TV no envió frame de bienvenida (token ya conocido o modelo antiguo)
        } finally {
            socket.soTimeout = TIMEOUT_MS
        }

        if (needsApproval) {
            socket.close()
            return JSONObject().apply {
                put("ok", false)
                put("needsApproval", true)
                put("token", newToken)
                put("msg", "Acepta la conexión en el televisor")
            }
        }

        if (cmd == CMD_PAIR) {
            socket.close()
            return JSONObject().apply {
                put("ok", true)
                put("token", newToken)
                put("needsApproval", false)
                put("paired", true)
            }
        }

        // ── Enviar comando ────────────────────────────────────
        val key = samsungKey(cmd)
        val cmdPayload = """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"$key","Option":"false","TypeOfRemote":"SendRemoteKey"}}"""
        sendWsFrame(out, cmdPayload)
        out.flush()
        socket.close()

        return JSONObject().apply {
            put("ok",    true)
            put("token", newToken)
            put("needsApproval", false)
        }
    }

    /** Lee una línea HTTP byte a byte desde el InputStream raw.
     *  Evita usar BufferedReader que consumiría bytes del frame WebSocket siguiente. */
    private fun readHttpLine(stream: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = stream.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (prev == '\r'.code && b == '\n'.code) {
                // Quitar el \r del final
                if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
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
        "vol_up"            -> "KEY_VOLUMEUP"
        "vol_down"          -> "KEY_VOLUMEDOWN"
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
