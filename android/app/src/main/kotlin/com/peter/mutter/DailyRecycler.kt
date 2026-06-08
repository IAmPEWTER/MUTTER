package com.peter.mutter

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Once a day at [RECYCLE_HOUR] local time, fires a private broadcast so the
 * service can tear down and rebuild the native recognizer. That bounds native
 * heap growth over long uptime (the recognizer is the only thing that holds
 * real memory for the life of the process) while keeping it hot — the reload
 * re-warms in ~1s, before the user is awake.
 *
 * One inexact, doze-friendly repeating alarm. No watchdog, no polling. Armed
 * from onServiceConnected (which also runs after a reboot, when the system
 * rebinds the service), so it survives reboots without a BOOT_COMPLETED
 * receiver. Re-arming is idempotent via the fixed request code.
 */
object DailyRecycler {
    const val ACTION = "com.peter.mutter.action.DAILY_RECYCLE"
    const val RECYCLE_HOUR = 5 // 5 AM local
    private const val REQUEST_CODE = 5001

    /**
     * Epoch millis of the next [hour]:00 in [zone], strictly after [nowMillis].
     * If it is already past (or exactly) [hour]:00 today, returns tomorrow's.
     */
    fun nextFireAt(nowMillis: Long, hour: Int, zone: ZoneId): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        var next = now.toLocalDate().atTime(hour, 0).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    fun arm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val first = nextFireAt(System.currentTimeMillis(), RECYCLE_HOUR, ZoneId.systemDefault())
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            first,
            AlarmManager.INTERVAL_DAY,
            pendingIntent(context),
        )
    }

    fun disarm(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent {
        // setPackage keeps the broadcast explicit to our app, pairing with the
        // receiver's RECEIVER_NOT_EXPORTED registration.
        val intent = Intent(ACTION).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
