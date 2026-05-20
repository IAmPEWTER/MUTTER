package com.peter.mutter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object NotificationHelper {

    const val CHANNEL_ID = "mutter_recording"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    fun recordingNotification(context: Context): Notification =
        Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notif_recording_title))
            .setContentText(context.getString(R.string.notif_recording_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
}
