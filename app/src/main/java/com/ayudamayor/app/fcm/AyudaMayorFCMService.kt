package com.ayudamayor.app.fcm

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Placeholder para Firebase Cloud Messaging.
 * Activar cuando se configure google-services.json real y se añada Firebase al build.gradle.kts
 */
class AyudaMayorFCMService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
