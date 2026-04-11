package com.ayudamayor.app.bridge

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ayudamayor.app.MainActivity
import com.ayudamayor.app.R
import org.json.JSONArray
import java.util.Calendar

/**
 * AlarmReceiver — dos funciones:
 * 1. Mostrar notificación cuando dispara una alarma programada
 * 2. Reprogramar todas las alarmas tras reinicio del dispositivo (BOOT_COMPLETED)
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> rescheduleAllAlarms(context)
            else                          -> showAlarmNotification(context, intent)
        }
    }

    // ── Mostrar notificación de alarma ────────────────────────
    private fun showAlarmNotification(context: Context, intent: Intent) {
        val label = intent.getStringExtra("label") ?: "Recordatorio"

        ensureAlarmChannel(context)

        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        val notification = NotificationCompat.Builder(context, CHANNEL_ALARMS)
            .setContentTitle("⏰ $label")
            .setContentText("Recordatorio de AyudaMayor")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pi, true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(System.currentTimeMillis().toInt() and 0xFFFF, notification)
    }

    // ── Reprogramar todas las alarmas tras reinicio ───────────
    private fun rescheduleAllAlarms(context: Context) {
        val prefs  = context.getSharedPreferences("ayudamayor_alarms", Context.MODE_PRIVATE)
        val stored = prefs.getString("alarms_json", "[]") ?: "[]"

        try {
            val arr = JSONArray(stored)
            val am  = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            ensureAlarmChannel(context)

            for (i in 0 until arr.length()) {
                val obj    = arr.getJSONObject(i)
                val id     = obj.getInt("id")
                val hour   = obj.getInt("hour")
                val minute = obj.getInt("minute")
                val label  = obj.getString("label")

                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, hour)
                    set(Calendar.MINUTE, minute)
                    set(Calendar.SECOND, 0)
                    if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
                }

                val alarmIntent = Intent(context, AlarmReceiver::class.java)
                    .putExtra("label", label)
                val pi = PendingIntent.getBroadcast(
                    context, id, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                        am.setWindow(AlarmManager.RTC_WAKEUP, cal.timeInMillis, 60_000L, pi)
                    } else {
                        am.setAlarmClock(AlarmManager.AlarmClockInfo(cal.timeInMillis, pi), pi)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun ensureAlarmChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ALARMS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARMS, "Alarmas AyudaMayor",
                    NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        const val CHANNEL_ALARMS = "alarm_channel"
    }
}
