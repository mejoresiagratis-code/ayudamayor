package com.ayudamayor.app.permissions

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionManager(private val activity: Activity) {

    companion object {
        const val REQUEST_CODE = 1001

        // Permisos que pedimos al arrancar la app
        // El WebView los necesita para micrófono, GPS, llamadas y WiFi/IoT
        val CRITICAL_PERMISSIONS: Array<String> = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.CALL_PHONE)
            add(Manifest.permission.ACCESS_FINE_LOCATION) // Cubre WiFi SSID en Android 8-12
            add(Manifest.permission.CAMERA)
            // Android 13+: permiso específico para dispositivos WiFi cercanos
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
    }

    private var onGranted: (() -> Unit)? = null

    fun requestCriticalPermissions(onAllGranted: () -> Unit) {
        this.onGranted = onAllGranted

        val missing = CRITICAL_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            // Ya tenemos todos los permisos — cargar la URL directamente
            onAllGranted()
        } else {
            ActivityCompat.requestPermissions(
                activity,
                missing.toTypedArray(),
                REQUEST_CODE
            )
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE) {
            // Continuar aunque algunos permisos sean denegados
            // La app funciona sin ellos (el bridge comprueba permisos antes de usarlos)
            onGranted?.invoke()
            onGranted = null
        }
    }
}
