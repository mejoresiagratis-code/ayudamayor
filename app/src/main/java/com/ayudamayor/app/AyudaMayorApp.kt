package com.ayudamayor.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebView

class AyudaMayorApp : Application() {

    companion object {
        private var wakeLock: PowerManager.WakeLock? = null

        /**
         * Adquirir WakeLock parcial — mantiene la CPU activa en background
         * para que el SSE y el GPS sigan funcionando aunque la pantalla esté apagada.
         * Solo se adquiere si no está ya activo.
         */
        fun acquireWakeLock(app: Application) {
            if (wakeLock?.isHeld == true) return
            try {
                val pm = app.getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AyudaMayor::BackgroundKeepAlive"
                ).also {
                    it.setReferenceCounted(false)
                    it.acquire(8 * 60 * 60 * 1000L) // máx 8h — se renueva al volver al foreground
                }
            } catch (_: Exception) {}
        }

        fun releaseWakeLock() {
            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Cookies persistentes desde el inicio de la app
        CookieManager.getInstance().setAcceptCookie(true)

        // Habilitar debugging del WebView en builds de desarrollo
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Lanza el intent para pedir al usuario que excluya la app de la optimización de batería.
     * Debe llamarse desde una Activity (no desde Application).
     * Muestra el diálogo del sistema — el usuario puede aceptar o rechazar.
     */
    fun requestIgnoreBatteryOptimizations(activity: android.app.Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return // ya excluida

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            activity.startActivity(intent)
        } catch (_: Exception) {}
    }
}
