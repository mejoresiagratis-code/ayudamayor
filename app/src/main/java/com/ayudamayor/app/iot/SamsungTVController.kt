package com.ayudamayor.app.iot

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * SamsungTVController — control nativo de Samsung Smart TV 2016+
 *
 * Protocolo:
 *  - 2016-2018: HTTP REST en puerto 8001 (sin token)
 *  - 2019+    : WebSocket en puerto 8002 (WSS, con token)
 *    El TV muestra un diálogo "¿Permitir conexión de AyudaMayor?" la primera vez.
 *    El usuario acepta en el TV → el WS devuelve un token que guardamos.
 *    Las siguientes conexiones usan ese token y ya no piden confirmación.
 *
 * Uso:
 *   val ctrl = SamsungTVController("192.168.1.12")
 *   val result = ctrl.sendKey("KEY_VOLUP", savedToken)
 *   // result.token → guardar si es nuevo (primera vez)
 *   // result.ok    → true si el comando se envió
 *   // result.needsApproval → true si el TV está esperando que el usuario acepte en pantalla
 */
class SamsungTVController(private val ip: String) {

    companion object {
        private const val APP_NAME   = "AyudaMayor"
        private const val PORT_HTTP  = 8001
        private const val PORT_WSS   = 8002
        private const val TIMEOUT_MS = 5000

        // Teclas Samsung
        val KEY_MAP = mapOf(
            "power"        to "KEY_POWER",
            "vol_up"       to "KEY_VOLUP",
            "vol_down"     to "KEY_VOLDOWN",
            "mute"         to "KEY_MUTE",
            "ok"           to "KEY_ENTER",
            "up"           to "KEY_UP",
            "down"         to "KEY_DOWN",
            "left"         to "KEY_LEFT",
            "right"        to "KEY_RIGHT",
            "home"         to "KEY_HOME",
            "back"         to "KEY_RETURN",
            "play"         to "KEY_PLAY",
            "pause"        to "KEY_PAUSE",
            "channel_up"   to "KEY_CHUP",
            "channel_down" to "KEY_CHDOWN",
            "source"       to "KEY_SOURCE",
            "info"         to "KEY_INFO",
            "menu"         to "KEY_MENU",
            "red"          to "KEY_RED",
            "green"        to "KEY_GREEN",
            "yellow"       to "KEY_YELLOW",
            "blue"         to "KEY_BLUE",
            "num_0"        to "KEY_0",  "num_1" to "KEY_1", "num_2" to "KEY_2",
            "num_3"        to "KEY_3",  "num_4" to "KEY_4", "num_5" to "KEY_5",
            "num_6"        to "KEY_6",  "num_7" to "KEY_7", "num_8" to "KEY_8",
            "num_9"        to "KEY_9"
        )
    }

    data class Result(
        val ok: Boolean,
        val token: String? = null,       // token nuevo recibido del TV (guardar!)
        val needsApproval: Boolean = false, // TV está mostrando el diálogo de permiso
        val msg: String = ""
    )

    // ─────────────────────────────────────────────────────────────────────────
    // API pública
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Envía una tecla al TV.
     * @param command  Comando legible (ej: "vol_up", "power")
     * @param token    Token guardado de sesiones anteriores (null si es la primera vez)
     * @return Result con ok, token nuevo (si lo hay), y needsApproval
     */
    suspend fun sendKey(command: String, token: String? = null): Result =
        withContext(Dispatchers.IO) {
            val key = KEY_MAP[command] ?: command  // permite pasar KEY_VOLUP directamente

            // Primero intentar WebSocket (2019+, TokenAuthSupport)
            val wssResult = tryWebSocket(key, token)
            if (wssResult.ok || wssResult.needsApproval) return@withContext wssResult

            // Fallback: HTTP REST (modelos 2016-2018)
            tryHttpRest(key)
        }

    /**
     * Obtiene información del TV (nombre, modelo, estado)
     */
    suspend fun getInfo(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("http://$ip:$PORT_HTTP/api/v2/").openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS; conn.readTimeout = TIMEOUT_MS
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            json.optJSONObject("device")
        } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket nativo (sin librerías externas)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun tryWebSocket(key: String, token: String?): Result =
        withContext(Dispatchers.IO) {
            try {
                // SSLContext que acepta el certificado autofirmado del TV
                val sslCtx = SSLContext.getInstance("TLS")
                sslCtx.init(null, arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }), SecureRandom())

                val socket = sslCtx.socketFactory.createSocket() as SSLSocket
                socket.connect(InetSocketAddress(ip, PORT_WSS), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS

                val appNameB64 = Base64.encodeToString(APP_NAME.toByteArray(), Base64.NO_WRAP)

                // Path con token si existe
                val path = buildString {
                    append("/api/v2/channels/samsung.remote.control?name=$appNameB64")
                    if (!token.isNullOrEmpty()) append("&token=$token")
                }

                // Handshake WebSocket RFC 6455
                val wsKey    = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
                val request  = buildString {
                    append("GET $path HTTP/1.1\r\n")
                    append("Host: $ip:$PORT_WSS\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $wsKey\r\n")
                    append("Sec-WebSocket-Version: 13\r\n\r\n")
                }

                val out: OutputStream = socket.outputStream
                out.write(request.toByteArray())
                out.flush()

                // Leer respuesta HTTP del handshake
                val reader = BufferedReader(InputStreamReader(socket.inputStream))
                val statusLine = reader.readLine() ?: return@withContext Result(false, msg = "Sin respuesta del TV")

                if (!statusLine.contains("101")) {
                    socket.close()
                    return@withContext Result(false, msg = "TV rechazó WebSocket: $statusLine")
                }

                // Consumir cabeceras HTTP
                while (true) { if (reader.readLine().isNullOrEmpty()) break }

                // Leer primer mensaje del TV (contiene el token o estado de aprobación)
                val rawFrame = readWsFrame(socket)
                val firstMsg = if (rawFrame != null) JSONObject(rawFrame) else null

                var receivedToken: String? = null
                var needsApproval = false

                if (firstMsg != null) {
                    val event = firstMsg.optString("event")
                    when (event) {
                        "ms.channel.connect" -> {
                            // Conexión aceptada — extraer token
                            receivedToken = firstMsg.optJSONObject("data")?.optString("token")
                                .takeIf { !it.isNullOrEmpty() }
                        }
                        "ms.channel.unauthorized" -> {
                            // TV mostrando diálogo de permiso — usuario debe aceptar en el TV
                            needsApproval = true
                            socket.close()
                            return@withContext Result(false, needsApproval = true,
                                msg = "Acepta la conexión en la pantalla del TV")
                        }
                        "ms.channel.clientConnect" -> {
                            // Ya hay otro cliente — intentar igualmente
                        }
                    }
                }

                // Enviar el comando de tecla
                val cmd = JSONObject().apply {
                    put("method", "ms.remote.control")
                    put("params", JSONObject().apply {
                        put("Cmd", "Click")
                        put("DataOfCmd", key)
                        put("TypeOfRemote", "SendRemoteKey")
                    })
                }

                sendWsFrame(socket, cmd.toString())
                delay(200)  // pequeña espera para que el TV procese

                socket.close()

                Result(ok = true, token = receivedToken ?: token,
                    msg = "Comando $key enviado (WSS)")

            } catch (e: Exception) {
                Log.d("SamsungTV", "WSS falló: ${e.message}")
                Result(false, msg = "WSS: ${e.message}")
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP REST (Samsung 2016-2018 sin TokenAuth)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun tryHttpRest(key: String): Result = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("method", "ms.remote.control")
                put("params", JSONObject().apply {
                    put("Cmd", "Click")
                    put("DataOfCmd", key)
                    put("TypeOfRemote", "SendRemoteKey")
                })
            }.toString()

            val conn = URL("http://$ip:$PORT_HTTP/api/v2/channels/samsung.remote.control")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(body.toByteArray())
            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..299) Result(true, msg = "HTTP OK")
            else Result(false, msg = "HTTP $code")
        } catch (e: Exception) {
            Result(false, msg = "HTTP: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebSocket frame encoding/decoding (RFC 6455 — mínimo necesario)
    // ─────────────────────────────────────────────────────────────────────────

    /** Lee un frame WebSocket y devuelve el payload como String */
    private fun readWsFrame(socket: SSLSocket): String? {
        return try {
            val input = socket.inputStream
            val b0    = input.read()
            val b1    = input.read()
            if (b0 < 0 || b1 < 0) return null

            val payloadLen = when (val len = b1 and 0x7F) {
                126  -> (input.read() shl 8) or input.read()
                127  -> {
                    // 64-bit length — leer 8 bytes, usar solo los últimos 4
                    repeat(4) { input.read() }
                    (input.read() shl 24) or (input.read() shl 16) or (input.read() shl 8) or input.read()
                }
                else -> len
            }

            val payload = ByteArray(payloadLen)
            var read = 0
            while (read < payloadLen) {
                val n = input.read(payload, read, payloadLen - read)
                if (n < 0) break
                read += n
            }
            String(payload, Charsets.UTF_8)
        } catch (_: Exception) { null }
    }

    /** Envía un frame WebSocket de texto (FIN=1, opcode=1, enmascarado) */
    private fun sendWsFrame(socket: SSLSocket, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val mask    = ByteArray(4).also { SecureRandom().nextBytes(it) }
        val masked  = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i % 4].toInt()).toByte() }

        val out   = socket.outputStream
        val frame = buildList<Byte> {
            add(0x81.toByte())  // FIN + opcode text
            when {
                payload.size <= 125 -> add((0x80 or payload.size).toByte())
                payload.size <= 65535 -> {
                    add((0x80 or 126).toByte())
                    add((payload.size shr 8).toByte())
                    add((payload.size and 0xFF).toByte())
                }
                else -> {
                    add((0x80 or 127).toByte())
                    repeat(4) { add(0) }
                    add((payload.size shr 24).toByte()); add((payload.size shr 16).toByte())
                    add((payload.size shr 8).toByte());  add((payload.size and 0xFF).toByte())
                }
            }
            addAll(mask.toList())
            addAll(masked.toList())
        }
        out.write(frame.toByteArray())
        out.flush()
    }
}
