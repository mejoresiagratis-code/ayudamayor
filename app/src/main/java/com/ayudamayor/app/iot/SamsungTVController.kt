package com.ayudamayor.app.iot

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * SamsungTVController — control de Samsung Tizen TV vía WebSocket (OkHttp).
 *
 * Protocolo "Samsung API v2":
 *   wss://<ip>:8002/api/v2/channels/samsung.remote.control?name=<base64Nombre>[&token=<token>]
 *   (TVs 2018+; certificado TLS autofirmado). Modelos antiguos: ws://<ip>:8001 (sin TLS).
 *
 * Emparejamiento (primera vez, sin token):
 *   1. Se abre la conexión → el televisor muestra el diálogo "Permitir conexión".
 *   2. Al aceptar, el TV envía {"event":"ms.channel.connect","data":{"token":"XXXX",...}}.
 *   3. Guardamos ese token; las siguientes conexiones lo usan y ya no piden permiso.
 *
 * Mantiene la firma sendCommand(ip, cmd, token) que usa NativeBridge.
 */
class SamsungTVController {

    companion object {
        // base64("AyudaMayor") sin padding
        private const val APP_NAME_B64    = "QXl1ZGFNYXlvcg"
        const val CMD_PAIR                = "__pair__"
        private const val PAIR_WAIT_SECS  = 30L   // margen para que el usuario pulse "Permitir"
        private const val KEY_WAIT_SECS   = 8L
        private const val CONNECT_TIMEOUT = 6L
    }

    /** OkHttp que confía en el certificado autofirmado de la TV (solo red local). */
    private val client: OkHttpClient by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<javax.net.ssl.TrustManager>(trustAll), SecureRandom())
        }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // WS: esperamos la aprobación sin cortar
            .pingInterval(0, TimeUnit.SECONDS)
            .build()
    }

    /** Punto de entrada usado por NativeBridge. */
    fun sendCommand(ip: String, cmd: String, token: String): String {
        // Encender/apagar idempotente: KEY_POWER es un toggle en Samsung, así que solo
        // conmutamos si el estado real del TV difiere del deseado (evita apagarlo al
        // intentar "encender" cuando ya estaba encendido).
        if (cmd == "on" || cmd == "off") {
            val ps = powerState(ip)   // "on" | "standby" | null (desconocido)
            if (ps != null && ps.equals("on", true) == (cmd == "on")) {
                return JSONObject().apply {
                    put("ok", true); put("token", token); put("noop", true)
                }.toString()
            }
        }
        // 8002 (wss, TVs 2018+ con token) primero; fallback a 8001 (ws, modelos antiguos).
        val r8002 = runChannel(ip, 8002, secure = true,  cmd = cmd, token = token)
        if (r8002.optBoolean("ok", false) || r8002.optBoolean("needsApproval", false)) return r8002.toString()
        val r8001 = runChannel(ip, 8001, secure = false, cmd = cmd, token = token)
        return if (r8001.optBoolean("ok", false) || r8001.optBoolean("needsApproval", false))
            r8001.toString() else r8002.toString()
    }

    /** Cliente HTTP corto para leer el estado de encendido (GET /api/v2/). */
    private val httpClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /** Lee device.PowerState del TV ("on"/"standby") o null si no responde. */
    private fun powerState(ip: String): String? = try {
        val req = Request.Builder().url("http://$ip:8001/api/v2/").build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (body.isNullOrBlank()) null
            else JSONObject(body).optJSONObject("device")
                ?.optString("PowerState", "")?.ifBlank { null }
        }
    } catch (_: Exception) { null }

    private fun runChannel(ip: String, port: Int, secure: Boolean, cmd: String, token: String): JSONObject {
        val scheme     = if (secure) "https" else "http"
        val tokenParam = if (token.isNotBlank()) "&token=$token" else ""
        val url        = "$scheme://$ip:$port/api/v2/channels/samsung.remote.control?name=$APP_NAME_B64$tokenParam"
        val isPair     = cmd == CMD_PAIR

        val latch = CountDownLatch(1)
        val out   = JSONObject().apply { put("ok", false) }

        val listener = object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("event")) {
                        "ms.channel.connect" -> {
                            // Conexión aceptada (o token ya válido).
                            val tk = obj.optJSONObject("data")?.optString("token", "") ?: ""
                            out.put("token", if (tk.isNotBlank()) tk else token)
                            if (isPair) {
                                out.put("ok", true); out.put("paired", true)
                            } else {
                                val key = samsungKey(cmd)
                                ws.send("{\"method\":\"ms.remote.control\",\"params\":{\"Cmd\":\"Click\"," +
                                        "\"DataOfCmd\":\"$key\",\"Option\":\"false\",\"TypeOfRemote\":\"SendRemoteKey\"}}")
                                out.put("ok", true)
                            }
                            ws.close(1000, null); latch.countDown()
                        }
                        "ms.channel.unauthorized" -> {
                            out.put("ok", false); out.put("needsApproval", true)
                            out.put("msg", "Acepta la conexión en el televisor")
                            ws.close(1000, null); latch.countDown()
                        }
                        // ms.channel.timeOut u otros eventos: seguir esperando la aprobación.
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                if (code == 401 || code == 403) {
                    out.put("ok", false); out.put("needsApproval", true)
                    out.put("msg", "Acepta la conexión en el televisor")
                } else {
                    out.put("ok", false); out.put("msg", t.message ?: "No se pudo conectar")
                }
                latch.countDown()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) { latch.countDown() }
        }

        val ws     = client.newWebSocket(Request.Builder().url(url).build(), listener)
        val waited = latch.await(if (isPair) PAIR_WAIT_SECS else KEY_WAIT_SECS, TimeUnit.SECONDS)
        if (!waited) {
            // La conexión se abrió (el popup salió) pero el usuario aún no aceptó.
            ws.cancel()
            if (!out.has("msg")) {
                out.put("needsApproval", true)
                out.put("msg", "Acepta la conexión en el televisor y reintenta")
            }
        }
        return out
    }

    private fun samsungKey(cmd: String) = when (cmd.lowercase()) {
        "power", "toggle", "on", "off" -> "KEY_POWER"
        "vol_up"          -> "KEY_VOLUMEUP"
        "vol_down"        -> "KEY_VOLUMEDOWN"
        "mute"            -> "KEY_MUTE"
        "up"              -> "KEY_UP"
        "down"            -> "KEY_DOWN"
        "left"            -> "KEY_LEFT"
        "right"           -> "KEY_RIGHT"
        "ok", "enter"     -> "KEY_ENTER"
        "back"            -> "KEY_RETURN"
        "home"            -> "KEY_HOME"
        "menu"            -> "KEY_MENU"
        "source"          -> "KEY_SOURCE"
        "ch_up"           -> "KEY_CHUP"
        "ch_down"         -> "KEY_CHDOWN"
        "play"            -> "KEY_PLAY"
        "pause"           -> "KEY_PAUSE"
        "stop"            -> "KEY_STOP"
        else              -> cmd.uppercase().let { if (it.startsWith("KEY_")) it else "KEY_$it" }
    }
}
