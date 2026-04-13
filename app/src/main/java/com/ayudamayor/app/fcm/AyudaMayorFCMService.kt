package com.ayudamayor.app.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ayudamayor.app.MainActivity
import com.ayudamayor.app.R
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

class AyudaMayorFCMService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_SOS      = "ayudamayor_sos"
        const val CHANNEL_GENERAL  = "ayudamayor_general"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    /** Llamado cuando llega un mensaje push con la app en background o cerrada */
    override fun onMessageReceived(message: RemoteMessage) {
        val type  = message.data["type"]  ?: message.notification?.title ?: "general"
        val title = message.data["title"] ?: message.notification?.title ?: "AyudaMayor"
        val body  = message.data["body"]  ?: message.notification?.body  ?: ""

        val isSos = type == "sos" || type == "fall"

        showNotification(
            title   = title,
            body    = body,
            isSos   = isSos,
            data    = message.data
        )
    }

    /** Llamado cuando se renueva el token FCM — actualizarlo en el servidor */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Guardar localmente para enviar al servidor cuando el usuario abra la app
        getSharedPreferences("ayudamayor_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("fcm_token_pending", token)
            .apply()
        // Suscribirse a topics por rol — el servidor puede hacer broadcast por segmento
        // El rol real se obtiene cuando el usuario inicia sesión en el servidor;
        // aquí nos suscribimos a "all" siempre y al rol guardado si existe.
        FirebaseMessaging.getInstance().subscribeToTopic("ayudamayor_all")
        val prefs = getSharedPreferences("ayudamayor_prefs", Context.MODE_PRIVATE)
        val role  = prefs.getString("user_role", "") ?: ""
        if (role.isNotBlank()) {
            FirebaseMessaging.getInstance().subscribeToTopic("ayudamayor_$role")
        }
    }


    private fun showNotification(
        title: String,
        body: String,
        isSos: Boolean,
        data: Map<String, String>
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data.forEach { (k, v) -> putExtra(k, v) }
        }
        val pi = PendingIntent.getActivity(
            this, System.currentTimeMillis().toInt(), intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (isSos) CHANNEL_SOS else CHANNEL_GENERAL
        val soundUri  = RingtoneManager.getDefaultUri(
            if (isSos) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(if (isSos) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .apply {
                if (isSos) {
                    setVibrate(longArrayOf(0, 500, 100, 500, 100, 500))
                    setCategory(NotificationCompat.CATEGORY_ALARM)
                    setFullScreenIntent(pi, true) // wake-lock screen
                }
            }
            .build()

        val notifId = if (isSos) 9001 else System.currentTimeMillis().toInt()
        nm.notify(notifId, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Canal SOS — máxima prioridad, sonido de alarma
        if (nm.getNotificationChannel(CHANNEL_SOS) == null) {
            val sos = NotificationChannel(
                CHANNEL_SOS,
                "Alertas SOS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de emergencia del mayor — no silenciar"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 100, 500, 100, 500)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(sos)
        }

        // Canal general — notificaciones normales
        if (nm.getNotificationChannel(CHANNEL_GENERAL) == null) {
            val general = NotificationChannel(
                CHANNEL_GENERAL,
                "Notificaciones AyudaMayor",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Medicación, batería, check-in y actividad del mayor"
            }
            nm.createNotificationChannel(general)
        }
    }
}
