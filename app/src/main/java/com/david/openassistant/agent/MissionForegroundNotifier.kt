package com.david.openassistant.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.david.openassistant.MainActivity
import com.david.openassistant.R

/**
 * Manages foreground notifications for long-running research missions.
 */
class MissionForegroundNotifier(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Research Missions",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing research mission status and controls."
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createForegroundInfo(goalId: String, title: String, phase: String): ForegroundInfo {
        val notification = buildNotification(goalId, title, phase)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    fun updateNotification(goalId: String, title: String, phase: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(goalId, title, phase))
    }

    private fun buildNotification(goalId: String, title: String, phase: String): Notification {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(context, MissionControlReceiver::class.java).apply {
            action = MissionControlReceiver.ACTION_STOP_MISSION
            putExtra(MissionControlReceiver.EXTRA_GOAL_ID, goalId)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("Status: $phase")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Use a better icon if available
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "Open App", openAppPendingIntent)
            .addAction(0, "Stop Mission", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "research_missions"
        const val NOTIFICATION_ID = 42
    }
}
