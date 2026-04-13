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
        // El WebView los necesita para micrófono, GPS y llamadas
        val CRITICAL_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
        )
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
