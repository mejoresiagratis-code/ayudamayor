package com.ayudamayor.app.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ayudamayor.app.MainActivity
import com.ayudamayor.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AyudaMayorFCMService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_SOS      = "sos_alerts"
        const val CHANNEL_GENERAL  = "general"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // TODO: enviar el token al servidor PHP para almacenarlo en push_subscriptions
        // El servidor ya tiene la lógica WebPush — aquí habría que añadir un endpoint
        // POST /api/push.php?action=register_fcm con { token: token }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data  = message.data
        val notif = message.notification
        val type  = data["type"] ?: ""

        val title = notif?.title ?: data["title"] ?: "AyudaMayor"
        val body  = notif?.body  ?: data["body"]  ?: ""

        // SOS y caídas tienen canal de alta prioridad con sonido de alarma
        val channelId = if (type == "sos" || type == "fall") CHANNEL_SOS else CHANNEL_GENERAL

        showNotification(title, body, channelId, type)
    }

    private fun showNotification(
        title: String, body: String, channelId: String, type: String
    ) {
        createNotificationChannels()

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("notification_type", type)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val priority = if (channelId == CHANNEL_SOS)
            NotificationCompat.PRIORITY_MAX
        else
            NotificationCompat.PRIORITY_DEFAULT

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(soundUri)
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .apply {
                if (channelId == CHANNEL_SOS) {
                    // SOS: vibración de emergencia
                    setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
                }
            }
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Canal SOS — máxima importancia, sonido de alarma
        if (manager.getNotificationChannel(CHANNEL_SOS) == null) {
            NotificationChannel(
                CHANNEL_SOS,
                "Alertas SOS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alertas de emergencia del mayor"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }.also { manager.createNotificationChannel(it) }
        }

        // Canal general — importancia normal
        if (manager.getNotificationChannel(CHANNEL_GENERAL) == null) {
            NotificationChannel(
                CHANNEL_GENERAL,
                "Notificaciones",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Medicación, GPS, estado del mayor"
            }.also { manager.createNotificationChannel(it) }
        }
    }
}
