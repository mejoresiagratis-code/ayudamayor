package com.ayudamayor.app.bridge

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.content.IntentFilter
import android.os.BatteryManager
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ayudamayor.app.R
import com.ayudamayor.app.billing.BillingManager
import com.ayudamayor.app.iot.IotDiscovery
import com.ayudamayor.app.iot.SamsungTVController
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * NativeBridge — expone funcionalidad nativa Android al JavaScript del WebView.
 * Accesible desde JS como: window.NativeBridge.nombreMetodo(args)
 * Todos los métodos @JavascriptInterface se ejecutan en hilo secundario.
 */
class NativeBridge(
    private val context: Context,
    private val billing: BillingManager,
    private val onEval: (String) -> Unit   // callback para evaluateJavascript en UI thread
) {

    private var iotDiscovery: IotDiscovery? = null
    private val samsungController = SamsungTVController()
    // Hilo dedicado para operaciones de red Samsung (evita bloquear el hilo JS del WebView)
    private val samsungExecutor = Executors.newSingleThreadExecutor()

    // ── LLAMADAS ─────────────────────────────────────────────
    @JavascriptInterface
    fun call(contactNameOrPhone: String) {
        val phone = resolvePhone(contactNameOrPhone) ?: return
        if (!hasPermission(Manifest.permission.CALL_PHONE)) return
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ── SMS ───────────────────────────────────────────────────
    @JavascriptInterface
    fun sendSms(contactNameOrPhone: String, message: String) {
        val phone = resolvePhone(contactNameOrPhone) ?: return
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ── LINTERNA ──────────────────────────────────────────────
    @JavascriptInterface
    fun setTorch(on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.cameraIdList.firstOrNull()?.let { cm.setTorchMode(it, on) }
        } catch (_: Exception) {}
    }

    // ── VOLUMEN ───────────────────────────────────────────────
    @JavascriptInterface
    fun setVolume(level: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, ((level.coerceIn(0, 100) / 100f) * max).toInt(), 0)
    }

    // ── BRILLO ────────────────────────────────────────────────
    @JavascriptInterface
    fun setBrightness(level: Int) {
        if (!Settings.System.canWrite(context)) return
        Settings.System.putInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS,
            ((level.coerceIn(0, 100) / 100f) * 255).toInt()
        )
    }

    // ── ABRIR APP ─────────────────────────────────────────────
    @JavascriptInterface
    fun openApp(appName: String) {
        val packages = mapOf(
            "whatsapp" to "com.whatsapp", "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps", "gmail" to "com.google.android.gm",
            "youtube" to "com.google.android.youtube", "chrome" to "com.android.chrome",
            "tiempo" to "com.google.android.apps.weather", "meteorologia" to "com.google.android.apps.weather",
            "calculadora" to "com.google.android.calculator", "fotos" to "com.google.android.apps.photos",
            "camara" to "com.android.camera2", "cámara" to "com.android.camera2",
            "calendario" to "com.google.android.calendar", "ajustes" to "com.android.settings",
            "configuracion" to "com.android.settings", "configuración" to "com.android.settings",
            "facebook" to "com.facebook.katana", "instagram" to "com.instagram.android",
            "reloj" to "com.google.android.deskclock", "alarma" to "com.google.android.deskclock",
            "musica" to "com.google.android.music", "música" to "com.google.android.music",
            "spotify" to "com.spotify.music", "telefono" to "com.google.android.dialer",
            "teléfono" to "com.google.android.dialer", "contactos" to "com.google.android.contacts",
            "mensajes" to "com.google.android.apps.messaging", "galeria" to "com.google.android.apps.photos",
            "galería" to "com.google.android.apps.photos",
        )
        val key = appName.lowercase().trim()
        val pkg = packages[key]
            ?: packages.entries.firstOrNull { key.contains(it.key) || it.key.contains(key) }?.value
            ?: return
        context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { context.startActivity(it) }
    }

    // ── ALARMAS NATIVAS ──────────────────────────────────────
    /**
     * Crea una alarma real en el sistema Android con AlarmManager.
     * JS: window.NativeBridge.setAlarm("08:30", "Tomar pastilla")
     * Si time es null o vacío, abre la app de reloj como fallback.
     */
    @JavascriptInterface
    fun setAlarm(time: String, label: String) {
        if (time.isBlank()) { openApp("reloj"); return }

        val parts = time.trim().split(":")
        if (parts.size < 2) { openApp("reloj"); return }

        val hour   = parts[0].toIntOrNull() ?: run { openApp("reloj"); return }
        val minute = parts[1].toIntOrNull() ?: run { openApp("reloj"); return }

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            // Si ya pasó hoy, programar para mañana
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel("alarm_channel") == null
        ) {
            nm.createNotificationChannel(
                NotificationChannel("alarm_channel", "Alarmas AyudaMayor",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("label", label)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            (hour * 100 + minute),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 60_000L, pi)
            } else {
                am.setAlarmClock(
                    AlarmManager.AlarmClockInfo(cal.timeInMillis, pi),
                    pi
                )
            }
            // Persistir alarma para poder reprogramarla tras reinicio del dispositivo
            persistAlarm(hour, minute, label, hour * 100 + minute)
        } catch (_: Exception) {
            openApp("reloj")
        }
    }

    private fun persistAlarm(hour: Int, minute: Int, label: String, id: Int) {
        val prefs = context.getSharedPreferences("ayudamayor_alarms", Context.MODE_PRIVATE)
        val stored = prefs.getString("alarms_json", "[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(stored)
            // Eliminar si ya existe con mismo id
            val newArr = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                if (arr.getJSONObject(i).getInt("id") != id) newArr.put(arr.getJSONObject(i))
            }
            newArr.put(org.json.JSONObject().apply {
                put("id", id); put("hour", hour); put("minute", minute); put("label", label)
            })
            prefs.edit().putString("alarms_json", newArr.toString()).apply()
        } catch (_: Exception) {}
    }

    // ── CÁMARA ────────────────────────────────────────────────
    @JavascriptInterface
    fun takePicture() {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ── BILLING ───────────────────────────────────────────────
    @JavascriptInterface
    fun getTier(): String = billing.getCurrentTier()

    @JavascriptInterface
    fun launchBilling(plan: String) = billing.launchBillingFlow(plan)

    // ── DEVICE INFO ───────────────────────────────────────────
    @JavascriptInterface
    fun getDeviceInfo(): String {
        val wm = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val ssid = if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            wm?.connectionInfo?.ssid?.trim('"') ?: "" else ""
        return JSONObject().apply {
            put("brand",   Build.BRAND)
            put("model",   Build.MODEL)
            put("sdk",     Build.VERSION.SDK_INT)
            put("ssid",    ssid)
            put("battery", getBatteryLevel())
        }.toString()
    }

    /**
     * Devuelve el nivel de batería del dispositivo (0-100).
     * JS: window.NativeBridge.getBatteryLevel() → int
     */
    @JavascriptInterface
    fun getBatteryLevel(): Int {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) ((level.toFloat() / scale) * 100).toInt() else -1
        } catch (_: Exception) { -1 }
    }

    // ── IOT: ESCANEO DE RED ───────────────────────────────────
    /**
     * Inicia el escaneo IoT. Resultados llegan via window.onIotScanResult(json).
     * JS: window.NativeBridge.startIotScan()
     */
    @JavascriptInterface
    fun startIotScan() {
        iotDiscovery?.stop()
        iotDiscovery = IotDiscovery(context) { result ->
            // Llamar al callback JS en cualquier hilo — evaluateJavascript es thread-safe
            val escaped = result.replace("'", "\\'").replace("\n", "")
            onEval("if(window.onIotScanResult) window.onIotScanResult('$escaped');")
        }
        iotDiscovery?.start()
    }

    /** Detiene el escaneo IoT. JS: window.NativeBridge.stopIotScan() */
    @JavascriptInterface
    fun stopIotScan() {
        iotDiscovery?.stop()
        iotDiscovery = null
    }

    // ── IOT: SAMSUNG TV ──────────────────────────────────────
    /**
     * Controla Samsung TV vía WebSocket nativo.
     * JS: window.NativeBridge.controlSamsungTV(ip, cmd, token) → JSON string
     * Returns: {"ok":true,"token":"xxx"} | {"ok":false,"msg":"...","needsApproval":true}
     * Guarda automáticamente el token recibido en SharedPreferences.
     */
    @JavascriptInterface
    fun controlSamsungTV(ip: String, cmd: String, token: String): String {
        samsungExecutor.execute {
            val result = try {
                val raw = samsungController.sendCommand(ip, cmd, token)
                try {
                    val json = JSONObject(raw)
                    val newToken = json.optString("token", "")
                    if (newToken.isNotBlank() && newToken != token) {
                        saveSamsungToken(ip, newToken)
                    }
                } catch (_: Exception) {}
                raw
            } catch (e: Exception) {
                JSONObject().apply {
                    put("ok", false)
                    put("msg", e.message ?: "Error desconocido")
                }.toString()
            }
            val escaped = result.replace("'", "\\'").replace("\n", "")
            onEval("if(window._onSamsungResult) window._onSamsungResult('$escaped');")
        }
        return "{\"ok\":false,\"pending\":true}"
    }

    /**
     * Empareja con la TV Samsung sin enviar ningún comando de tecla.
     * Solo conecta, recibe el token de bienvenida y lo persiste.
     * Usar para el botón "Probar emparejamiento" en la UI.
     * JS: window.NativeBridge.pairSamsungTV(ip) → JSON string
     * Returns: {"ok":true,"token":"xxx","paired":true} | {"ok":false,"needsApproval":true,...}
     */
    @JavascriptInterface
    fun pairSamsungTV(ip: String): String {
        // Lanzar en hilo de red para no bloquear el hilo JS del WebView
        samsungExecutor.execute {
            val result = try {
                val savedToken = getSamsungToken(ip)
                val raw = samsungController.sendCommand(ip, SamsungTVController.CMD_PAIR, savedToken)
                try {
                    val json = JSONObject(raw)
                    val newToken = json.optString("token", "")
                    if (newToken.isNotBlank()) saveSamsungToken(ip, newToken)
                } catch (_: Exception) {}
                raw
            } catch (e: Exception) {
                JSONObject().apply {
                    put("ok", false)
                    put("msg", e.message ?: "Error de emparejamiento")
                }.toString()
            }
            val escaped = result.replace("'", "\\'").replace("\n", "")
            onEval("if(window._onPairResult) window._onPairResult('$escaped');")
        }
        // Devuelve vacío inmediatamente — el resultado llega por callback JS
        return "{\"ok\":false,\"pending\":true}"
    }

    /**
     * Obtiene el token Samsung guardado para una IP concreta.
     * JS: window.NativeBridge.getSamsungToken("192.168.1.12") → string
     */
    @JavascriptInterface
    fun getSamsungToken(ip: String): String {
        return context.getSharedPreferences("ayudamayor_samsung", Context.MODE_PRIVATE)
            .getString("token_${ip.replace('.', '_')}", "") ?: ""
    }

    /**
     * Guarda el token Samsung para una IP. Llamado automáticamente por controlSamsungTV
     * y pairSamsungTV, pero también disponible desde JS si la web lo gestiona.
     * JS: window.NativeBridge.saveSamsungToken("192.168.1.12", "12345678")
     */
    @JavascriptInterface
    fun saveSamsungToken(ip: String, token: String) {
        context.getSharedPreferences("ayudamayor_samsung", Context.MODE_PRIVATE)
            .edit()
            .putString("token_${ip.replace('.', '_')}", token)
            .apply()
    }

    // ── FCM TOKEN ─────────────────────────────────────────────
    /** El JS puede obtener el token FCM guardado para enviarlo al servidor */
    @JavascriptInterface
    fun getFcmToken(): String {
        return context.getSharedPreferences("ayudamayor_prefs", Context.MODE_PRIVATE)
            .getString("fcm_token_pending", "") ?: ""
    }

    /**
     * Guarda el rol del usuario y suscribe al topic FCM correspondiente.
     * Llamar desde JS tras login: window.NativeBridge.setUserRole("major")
     */
    @JavascriptInterface
    fun setUserRole(role: String) {
        context.getSharedPreferences("ayudamayor_prefs", Context.MODE_PRIVATE)
            .edit().putString("user_role", role).apply()
        // Suscribir al topic del rol
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .subscribeToTopic("ayudamayor_$role")
        } catch (_: Exception) {}
    }

    // ── HELPERS ───────────────────────────────────────────────
    private fun resolvePhone(nameOrPhone: String): String? {
        if (nameOrPhone.matches(Regex("[+\\d\\s\\-()]+"))) return nameOrPhone.trim()
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$nameOrPhone%"), null
        ) ?: return null
        return cursor.use {
            if (it.moveToFirst())
                it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            else null
        }
    }

    private fun hasPermission(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}
