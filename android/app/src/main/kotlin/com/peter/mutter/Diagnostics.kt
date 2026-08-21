package com.peter.mutter

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A rolling record of what dictation actually did, shown in Settings.
 *
 * A hold that fails on a user's phone is otherwise invisible: notifications
 * need a permission that may be denied and logcat needs a cable, so a refused
 * microphone and a missing model look identical from the outside — both are
 * one short buzz. Every failure branch writes here instead.
 */
object Diagnostics {

    private const val FILE = "diagnostics.log"
    private const val KEEP = 60
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lock = Any()

    fun record(context: Context, line: String) {
        Log.i("MutterDiag", line)
        synchronized(lock) {
            try {
                val f = File(context.filesDir, FILE)
                val previous = if (f.exists()) f.readLines() else emptyList()
                val next = (previous + "${stamp.format(Date())}  $line").takeLast(KEEP)
                f.writeText(next.joinToString("\n"))
            } catch (t: Throwable) {
                Log.w("MutterDiag", "record failed", t)
            }
        }
    }

    /** Newest first — the last thing that happened is the thing being debugged. */
    fun read(context: Context): String = synchronized(lock) {
        try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return "" else f.readLines().reversed().joinToString("\n")
        } catch (t: Throwable) {
            ""
        }
    }
}
