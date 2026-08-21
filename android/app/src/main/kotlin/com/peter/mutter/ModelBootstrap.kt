package com.peter.mutter

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gets the preferred model onto the phone without the user needing to know
 * there is a model.
 *
 * v0.7.0 made a new model mandatory and still required a wizard tap to fetch
 * it, so an install that was merely updated stopped dictating and explained
 * itself only in a notification. Fetching is the app's job now: on an unmetered
 * network it simply happens, with progress in the shade. On mobile data it
 * waits and asks first — this is ~620 MB — and picks itself back up the moment
 * Wi-Fi appears, so "waiting for Wi-Fi" never becomes "waiting forever".
 *
 * Runs on its own thread: the fetch outlives any hold and must not sit on the
 * executor that warms the microphone.
 */
object ModelBootstrap {

    private const val TAG = "MutterBootstrap"
    private val running = AtomicBoolean(false)
    private val waitingForNetwork = AtomicBoolean(false)

    /**
     * Start a fetch if one is warranted. Safe to call on every service connect;
     * returns immediately and is a no-op once the model is in place.
     */
    fun ensure(context: Context, downloader: ModelDownloader) {
        if (downloader.isPresent()) return
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        Thread({
            try {
                fetch(app, downloader)
            } finally {
                running.set(false)
            }
        }, "MutterModelFetch").apply { isDaemon = true }.start()
    }

    private fun fetch(context: Context, downloader: ModelDownloader) {
        // A phone that still has the previous model keeps dictating while this
        // runs, so only a phone with nothing gets an attention-grabbing prompt.
        val dark = !downloader.hasAnyModel()
        if (!unmetered(context)) {
            Log.i(TAG, "deferring fetch — metered or offline (dark=$dark)")
            if (dark) {
                NotificationHelper.notifyError(
                    context,
                    context.getString(R.string.notif_model_wifi_title),
                    context.getString(R.string.notif_model_wifi_text),
                    NotificationHelper.MODEL_NOTIFICATION_ID,
                    openSetup = true,
                )
            }
            awaitUnmetered(context, downloader)
            return
        }
        val title = context.getString(R.string.notif_model_fetching_title)
        NotificationHelper.notifyProgress(context, title, "", 0)
        var lastPct = -1
        val result = runBlocking {
            downloader.download { downloaded, total, _ ->
                val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                // onProgress fires per 64 KB — ~10k times for this model. Only
                // whole percents reach the notification manager.
                if (pct != lastPct) {
                    lastPct = pct
                    NotificationHelper.notifyProgress(
                        context, title,
                        context.getString(R.string.notif_model_fetching_text, pct),
                        pct,
                    )
                }
            }
        }
        NotificationHelper.cancel(context, NotificationHelper.MODEL_NOTIFICATION_ID)
        if (result.isSuccess) {
            Log.i(TAG, "model ready")
            context.sendBroadcast(
                Intent(MutterAccessibilityService.ACTION_MODEL_READY)
                    .setPackage(context.packageName)
            )
        } else {
            val why = result.exceptionOrNull()?.message ?: "unknown error"
            Log.e(TAG, "fetch failed: $why")
            NotificationHelper.notifyError(
                context,
                context.getString(R.string.notif_model_missing_title),
                why,
                NotificationHelper.MODEL_NOTIFICATION_ID,
                openSetup = true,
            )
            // Offline mid-download is the common case and resumes from the
            // .part file, so treat a failure as "try again on the next Wi-Fi".
            awaitUnmetered(context, downloader)
        }
    }

    private fun unmetered(context: Context): Boolean = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (t: Throwable) {
        Log.d(TAG, "connectivity unavailable", t)
        false
    }

    /** One-shot: resume the fetch the next time an unmetered network appears. */
    private fun awaitUnmetered(context: Context, downloader: ModelDownloader) {
        if (!waitingForNetwork.compareAndSet(false, true)) return
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    try { cm.unregisterNetworkCallback(this) } catch (_: Throwable) {}
                    waitingForNetwork.set(false)
                    ensure(context, downloader)
                }
            }
            cm.registerNetworkCallback(request, callback)
        } catch (t: Throwable) {
            Log.d(TAG, "could not watch for an unmetered network", t)
            waitingForNetwork.set(false)
        }
    }
}
