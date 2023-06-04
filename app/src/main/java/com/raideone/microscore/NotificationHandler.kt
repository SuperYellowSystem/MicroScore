package com.raideone.microscore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationHandler(private val context: Context, private val chanId: String) {

    companion object {
        const val NTF_ID_BOSS = 1
        const val NTF_ID_GOLEM = 2
        const val NTF_CHAN_ID_BOSS = "boss_channel"
        const val NTF_CHAN_ID_GOLEM = "golem_channel"
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(chanId, chanId, NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(): Notification {
        return NotificationCompat.Builder(context, chanId)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(context.getString(R.string.notification_content))
            .setSmallIcon(R.drawable.ic_notification)
            .build()
    }
}