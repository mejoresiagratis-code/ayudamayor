package com.ayudamayor.app.bridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import com.ayudamayor.app.billing.BillingManager

/**
 * NativeBridge — expone funcionalidad nativa Android al JavaScript del WebView.
 * Accesible desde JS como: window.NativeBridge.nombreMetodo(args)
 *
 * Todos los métodos anotados con @JavascriptInterface son públicos para el JS.
 * Se ejecutan en un hilo secundario — usar Handler si necesitas tocar la UI.
 */
class NativeBridge(
    private val context: Context,
    private val billing: BillingManager
) {

    // ──────────────────────────────────────────────────────────
    // LLAMADAS TELEFÓNICAS
    // Web:     window.open(`tel:${phone}`)
    // Android: window.NativeBridge.call("Pablo")
    // ──────────────────────────────────────────────────────────
    @JavascriptInterface
    fun call(contactNameOrPhone: String) {
        // Si ya es un número, llamar directamente
        val phone = if (contactNameOrPhone.matches(Regex("[+\\d\\s\\-()]+"))) {
            contactNameOrPhone.trim()
        } else {
            findPhoneByName(contactNameOrPhone)
        } ?: return

        if (!hasPermission(Manifest.permission.CALL_PHONE)) return
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ──────────────────────────────────────────────────────────
    // SMS
    // No disponible en web — nuevo en Android
    // window.NativeBridge.sendSms("Pablo", "Estoy bien")
    // ──────────────────────────────────────────────────────────
    @JavascriptInterface
    fun sendSms(contactNameOrPhone: String, message: String) {
        val phone = if (contactNameOrPhone.matches(Regex("[+\\d\\s\\-()]+"))) {
            contactNameOrPhone.trim()
        } else {
            findPhoneByName(contactNameOrPhone)
        } ?: return

        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
        intent.putExtra("sms_body", message)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ──────────────────────────────────────────────────────────
    // LINTERNA
    // Web:     handleSettings({type:'torch', value:true/false})
    // Android: window.NativeBridge.setTorch(true)
    // ──────────────────────────────────────────────────────────
    @JavascriptInterface
    fun setTorch(on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull() ?: return
            cm.setTorchMode(cameraId, on)
        } catch (e: Exception) {
            // Silenciar — dispositivo sin flash
        }
    }

    // ──────────────────────────────────────────────────────────
    // VOLUMEN
    // window.NativeBridge.setVolume(75)  // 0-100
    // ──────────────────────────────────────────────────────────
    @JavascriptInterface
    fun setVolume(level: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val vol = ((level.coerceIn(0, 100) / 100f) * max).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0)
    }

    // ──────────────────────────────────────────────────────────
    // BRILLO
    // window.NativeBridge.setBrightness(80)  // 0-100
    // Requiere WRITE_SETTINGS — el usuario debe concederlo en Ajustes
    // ──────────────────────────────────────────────────────────
    @JavascriptInterface
    fun setBrightness(level: Int) {
        if (!Settings.System.canWrite(context)) return
        val brightness = ((level.coerceIn(0, 100) / 100f) * 255).toInt()
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            brightness
        )
    }

    // ──────────────────────────────────────────────────────────
    // ABRIR APP POR NOMBRE
    // window.NativeBridge.openApp("whatsapp")
    // ──────────────────────────────────────────────────────────
    @JavascriptInterface
    fun openApp(appName: String) {
        val packages = mapOf(
            "whatsapp"      to "com.whatsapp",
            "maps"          to "com.google.android.apps.maps",
            "google maps"   to "com.google.android.apps.maps",
            "gmail"         to "com.google.android.gm",
            "youtube"       to "com.google.android.youtube",
            "chrome"        to "com.android.chrome",
            "tiempo"        to "com.google.android.apps.weather",
            "calculadora"   to "com.google.android.calculator",
            "fotos"         to "com.google.android.apps.photos",
            "camara"        to "android.media.action.IMAGE_CAPTURE",
            "cámara"        to "android.media.action.IMAGE_CAPTURE",
            "calendario"    to "com.google.android.calendar",
            "ajustes"       to "com.android.settings",
            "configuracion" to "com.android.settings",
            "facebook"      to "com.facebook.katana",
            "instagram"     to "com.instagram.android",
        )

        val pm = context.packageManager
        val key = appName.lowercase().trim()
        val pkg = packages[key] ?: run {
            // Búsqueda aproximada
            packages.entries.firstOrNull { key.contains(it.key) || it.key.contains(key) }?.value
        } ?: return

        pm.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    // ──────────────────────────────────────────────────────────
    // CÁMARA — tomar foto
    // window.NativeBridge.takePicture()
    // ──────────────────────────────────────────────────────────
    @JavascriptInterface
    fun takePicture() {
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ──────────────────────────────────────────────────────────
    // SUSCRIPCIÓN / TIER
    // window.NativeBridge.getTier()       → "FAMILIAR_FREE" / "FAMILIAR_PRO"
    // window.NativeBridge.launchBilling("monthly")
    // ──────────────────────────────────────────────────────────
    @JavascriptInterface
    fun getTier(): String = billing.getCurrentTier()

    @JavascriptInterface
    fun launchBilling(plan: String) {
        billing.launchBillingFlow(plan)
    }

    // ──────────────────────────────────────────────────────────
    // INFO DEL DISPOSITIVO
    // window.NativeBridge.getDeviceInfo()  → JSON string
    // ──────────────────────────────────────────────────────────
    @JavascriptInterface
    fun getDeviceInfo(): String {
        return """{"brand":"${Build.BRAND}","model":"${Build.MODEL}","sdk":${Build.VERSION.SDK_INT}}"""
    }

    // ──────────────────────────────────────────────────────────
    // HELPER PRIVADO: buscar teléfono por nombre en contactos del sistema
    // ──────────────────────────────────────────────────────────
    private fun findPhoneByName(name: String): String? {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        ) ?: return null

        return cursor.use {
            if (it.moveToFirst()) {
                it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
            } else null
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
