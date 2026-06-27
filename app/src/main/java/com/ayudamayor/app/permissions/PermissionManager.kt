package com.ayudamayor.app.permissions

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
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
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
        )
    }

    private var onGranted: (() -> Unit)? = null

    fun requestCriticalPermissions(onAllGranted: () -> Unit) {
        this.onGranted = onAllGranted

        // POST_NOTIFICATIONS solo es permiso en tiempo de ejecución en Android 13+ (API 33)
        val perms = CRITICAL_PERMISSIONS.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = perms.filter {
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
