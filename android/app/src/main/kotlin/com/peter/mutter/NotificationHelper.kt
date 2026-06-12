package com.peter.mutter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log

object NotificationHelper {

    const val CHANNEL_ID = "mutter_recording"
    const val NOTIFICATION_ID = 1001
    const val ERROR_CHANNEL_ID = "mutter_errors"
    const val ERROR_NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
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
        if (nm.getNotificationChannel(ERROR_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ERROR_CHANNEL_ID,
                    context.getString(R.string.notif_error_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
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

    /** Never-drop surfacing: a dictation that couldn't be delivered normally. */
    fun notifyError(context: Context, title: String, text: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = Notification.Builder(context, ERROR_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setSmallIcon(R.drawable.ic_mic)
                .setAutoCancel(true)
                .build()
            nm.notify(ERROR_NOTIFICATION_ID, n)
        } catch (t: Throwable) {
            Log.e("MutterNotif", "notifyError failed", t)
        }
    }
}
