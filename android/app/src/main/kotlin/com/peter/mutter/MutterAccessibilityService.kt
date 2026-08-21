package com.peter.mutter

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MutterAccessibilityService : AccessibilityService() {

    private val tag = "MutterSvc"

    // Capture-side state only: true between an accepted DOWN and its UP.
    // Transcription + injection continue on `worker` after capturing flips
    // false, so a NEW hold can start while the previous one is still draining
    // — a slow transcription can never swallow a press. The FIFO worker keeps
    // text in spoken order across holds.
    private val capturing = AtomicBoolean(false)

    // The silence callback, not the foreground promotion, is what reports a
    // blocked microphone: promotion can fail and capture still work, so
    // notifying on promotion alone cried wolf.
    private val recorder = AudioRecorder(onSilenced = { notifyMicBlocked() })
    private lateinit var engine: SttEngine
    private lateinit var segmenter: VadSegmenter
    private lateinit var downloader: ModelDownloader
    private lateinit var injector: TextInjector
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MutterWorker").apply { isDaemon = true }
    }
    private val modelExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MutterModelLoad").apply { isDaemon = true }
    }

    // Per-hold context. Created on the input thread at DOWN; afterwards only
    // touched on `worker` (chunk lambdas capture it by reference, so a task
    // always sees its own hold even after a new hold starts).
    private class Hold(@Volatile @JvmField var targetNode: AccessibilityNodeInfo?) {
        @JvmField var injectedAny = false
        @JvmField val failedTexts = mutableListOf<String>()
    }

    @Volatile private var hold: Hold? = null
    private var recycleReceiver: BroadcastReceiver? = null
    private var debugReceiver: BroadcastReceiver? = null

    companion object {
        /** Sent by SetupActivity when a model download completes. In-package only. */
        const val ACTION_MODEL_READY = "com.peter.mutter.action.MODEL_READY"
    }

    override fun onCreate() {
        super.onCreate()
        downloader = ModelDownloader(this)
        engine = SttEngine(downloader)
        // Each completed chunk is queued to the single-thread worker, so chunks
        // transcribe and inject strictly in spoken order while capture continues.
        segmenter = VadSegmenter(
            modelPath = { downloader.vadModelPath() },
            onChunk = { chunk ->
                val h = hold
                worker.execute { transcribeAndInject(h, chunk) }
            },
        )
        injector = TextInjector(this)
        NotificationHelper.ensureChannel(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(tag, "service connected")
        modelExec.execute {
            // Whatever this phone has, not only the preferred model: an update
            // that changes models must never cost the user dictation while the
            // replacement downloads.
            val spec = downloader.resolve()
            if (spec != null) {
                val ok = engine.load()
                val vadOk = segmenter.load()
                Log.i(tag, "model load: $ok (${spec.dir}), vad load: $vadOk")
                Diagnostics.record(
                    this,
                    if (ok) "ready — speech model ${spec.dir}${if (vadOk) "" else " (no voice detector)"}"
                    else "PROBLEM: speech model ${spec.dir} is present but failed to load",
                )
            } else {
                Log.w(tag, "no model on this device — fetching")
                Diagnostics.record(this, "PROBLEM: no speech model on this phone — downloading it")
            }
        }
        // Fetches the preferred model if it is missing, announces itself in the
        // shade, and broadcasts ACTION_MODEL_READY when done. No-op once the
        // model is in place.
        ModelBootstrap.ensure(this, downloader)
        registerRecycleReceiver()
        if (BuildConfig.DEBUG) registerDebugHoldReceiver()
        DailyRecycler.arm(this)
        // Open the HAL input now so the first key-down only has to leave
        // standby instead of paying the whole mic open.
        modelExec.execute { recorder.prepare() }
        worker.execute { PendingAudio.prune(this) }
        // Self-guarded: does nothing until the preferred model is verified
        // present. v0.7.0 called the unguarded version here and deleted the
        // only working model on every install.
        modelExec.execute { downloader.pruneSupersededModels() }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(tag, "service unbinding")
        recorder.release()
        segmenter.release()
        engine.release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        recorder.release()
        recycleReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        recycleReceiver = null
        debugReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        debugReceiver = null
        DailyRecycler.disarm(this)
        segmenter.release()
        engine.release()
        worker.shutdownNow()
        modelExec.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.i(tag, "onInterrupt")
        abortHold()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — we only care about key events. The events we registered
        // for in the config keep the service receiving updates so focus stays
        // current.
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!Prefs.isInterceptEnabled(this)) return false
        if (event.repeatCount > 0) {
            // Consume repeats while we're capturing; pass through otherwise.
            return capturing.get()
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown()
            KeyEvent.ACTION_UP -> handleUp()
            else -> false
        }
    }

    private fun handleDown(): Boolean {
        // Password guard runs first: never record while a password field
        // has focus, regardless of keyboard visibility.
        val focused = FocusTargets.focused(this)
        if (focused != null && FocusTargets.isPasswordField(focused)) {
            Log.i(tag, "handleDown drop: password field focused")
            return false
        }

        val editable = focused?.takeIf { it.isEditable }
        val imeUp = FocusTargets.isImeUp(this)
        // IME-up gate covers editors (Samsung Notes, rich-text canvases)
        // whose surface doesn't expose isEditable via AX but still raise
        // the soft keyboard.
        if (editable == null && !imeUp) {
            Log.d(tag, "handleDown drop: no editable + ime down")
            return false
        }
        Log.i(tag, "handleDown accept: editable=${editable != null} imeUp=$imeUp")
        return startHold(editable)
    }

    /**
     * A hold, once the gates have passed. Split from the gates so the real
     * path can be driven inside the real service process: an accessibility
     * service captures as a background app, an instrumentation process does
     * not, and that difference is exactly what this path turns on.
     */
    private fun startHold(target: AccessibilityNodeInfo?): Boolean {
        if (!capturing.compareAndSet(false, true)) return true // swallow stray double-down
        // Off the input thread on purpose — loading the VAD here would put its
        // startup back on the very path this hold is trying to keep short. The
        // hold runs degraded; the next one has it.
        if (!segmenter.isLoaded()) modelExec.execute { segmenter.load() }

        // may be null; transcribeAndInject falls back via findPasteTarget
        hold = Hold(target)
        segmenter.reset()
        // Clipboard snapshot rides the FIFO worker so it lands after the
        // previous hold's finish and before this hold's first chunk.
        worker.execute { injector.begin() }
        // Foreground FIRST, because Android hands a background app zeros rather
        // than an error (developer.android.com/media/platform/sharing-audio-input)
        // and an accessibility service with no UI on top counts as background —
        // starting the mic before this ate the head of every hold.
        //
        // But a refused promotion must not cost the user the hold. Through
        // v0.6.0 this failure was swallowed and dictation still worked; making
        // it fatal turned one bad phone state into "every press just buzzes".
        // Record anyway — the capture loop detects real silence and says so.
        promoteToForeground()
        if (!startRecording()) {
            demoteForeground()
            capturing.set(false)
            worker.execute { injector.finish(leftover = null) } // undo begin
            Diagnostics.record(this, "PROBLEM: hold refused — the microphone would not start")
            haptic(50)
            return false
        }
        return true
    }

    private fun handleUp(): Boolean {
        if (!capturing.compareAndSet(true, false)) return false
        // stop() joins the capture thread, so every cut chunk has already been
        // queued. flush() then queues the final partial chunk; both land on the
        // FIFO worker ahead of the end-of-hold task below.
        try { recorder.stop() } catch (t: Throwable) { Log.e(tag, "stop failed", t) }
        segmenter.flush()
        demoteForeground()
        modelExec.execute { recorder.prepare() } // stay warm for the next hold

        val h = hold
        worker.execute { endHold(h) }
        return true
    }

    // Runs on `worker`, strictly after every chunk of the hold. Failed chunks
    // go to the clipboard (instead of being clobbered by the old restore) and
    // are surfaced via notification — a dictation is never silently lost.
    private fun endHold(h: Hold?) {
        val leftover = h?.failedTexts?.takeIf { it.isNotEmpty() }?.joinToString(" ")
        // Recorded because it is the one outcome with no other trace: the hold
        // ran, the mic was open, and nothing came back. That separates a muted
        // microphone from a model that failed.
        if (leftover == null && h?.injectedAny != true) {
            Diagnostics.record(this, "hold ended — no speech heard")
        }
        injector.finish(leftover)
        if (leftover != null) {
            NotificationHelper.notifyError(
                this,
                getString(R.string.notif_inject_failed_title),
                leftover,
            )
        }
    }

    private fun startRecording(): Boolean =
        recorder.start { window -> segmenter.feed(window) }

    // One streamed chunk: transcribe and inject in spoken order. Runs on the
    // single `worker` thread; `h` is this chunk's own hold, so spacing and
    // failure bookkeeping stay correct even if a new hold already started.
    private fun transcribeAndInject(h: Hold?, samples: FloatArray) {
        if (!engine.isLoaded() && !engine.load()) {
            Log.e(tag, "engine load failed at transcribe time")
            Diagnostics.record(this, "PROBLEM: heard you, but the speech model would not load")
            persistChunk(samples)
            haptic(100)
            return
        }
        val raw = engine.transcribe(samples, 16000)
        if (raw == null) {
            // Engine failure — the audio must not be lost.
            Diagnostics.record(this, "PROBLEM: heard you, but transcription failed — audio saved")
            persistChunk(samples)
            haptic(100)
            return
        }
        if (raw.isBlank()) {
            Log.i(tag, "empty transcript")
            return
        }
        // The VAD confirmed speech in this chunk, so trust the transcript:
        // deliberate short utterances ("okay", "thank you") always type. A
        // decoder repeat-loop is bounded to one instance by collapseRepeats.
        val clean = Sanitizer.collapseRepeats(Sanitizer.sanitize(raw))
        if (clean.isEmpty()) return
        val text = if (h?.injectedAny == true) " $clean" else clean
        val node = FocusTargets.focusedEditable(this) ?: refreshedTarget(h) ?: FocusTargets.pasteTarget(this)
        if (injector.inject(node, text)) {
            h?.injectedAny = true
            Diagnostics.record(this, "typed ${clean.length} characters")
        } else {
            Log.w(tag, "injection failed — preserving transcript for end of hold")
            Diagnostics.record(this, "PROBLEM: could not type into this app — text copied to clipboard")
            h?.failedTexts?.add(clean)
            haptic(80)
        }
    }

    // Never-drop: a chunk that could not be transcribed is written to
    // filesDir/pending/ as a WAV and surfaced via notification.
    private fun persistChunk(samples: FloatArray) {
        val f = PendingAudio.save(this, samples, 16000)
        NotificationHelper.notifyError(
            this,
            getString(R.string.notif_transcribe_failed_title),
            getString(R.string.notif_transcribe_failed_text, f?.name ?: "pending/"),
        )
    }

    // The node captured at DOWN can go stale (focus moved, window gone);
    // refresh() validates it before use so ACTION_PASTE doesn't fail silently.
    private fun refreshedTarget(h: Hold?): AccessibilityNodeInfo? {
        val node = h?.targetNode ?: return null
        val alive = try { node.refresh() } catch (t: Throwable) { false }
        if (!alive) {
            h.targetNode = null
            return null
        }
        return node
    }

    /**
     * Best effort, never fatal. An active microphone foreground service is what
     * keeps Android from handing this process silence, so it is attempted
     * first — but Android can refuse to start one from the background (usually
     * a lapsed battery-optimisation exemption) and on many devices capture
     * works regardless. The hold proceeds either way; [notifyMicBlocked] fires
     * only if the framework confirms it is actually feeding us zeros.
     */
    private fun promoteToForeground() {
        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.recordingNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (t: Throwable) {
            Log.e(tag, "startForeground refused — recording anyway", t)
        }
    }

    private fun notifyMicBlocked() {
        Diagnostics.record(this, "PROBLEM: Android muted the microphone — nothing was recorded")
        NotificationHelper.notifyError(
            this,
            getString(R.string.notif_mic_blocked_title),
            getString(R.string.notif_mic_blocked_text),
            NotificationHelper.MIC_NOTIFICATION_ID,
        )
    }

    private fun demoteForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.d(tag, "stopForeground failed", t)
        }
    }

    private fun abortHold() {
        capturing.set(false)
        try { recorder.stop() } catch (_: Throwable) {}
        segmenter.reset() // drop the in-flight hold's buffered audio
        val h = hold
        worker.execute { endHold(h) } // FIFO: after any already-queued chunks
        demoteForeground()
    }

    /**
     * Drives a real hold from adb, inside this process, so capture can be
     * observed under the background conditions production runs in — an
     * instrumentation process is foreground and never reproduces them.
     *
     *   adb shell am broadcast -a com.peter.mutter.DEBUG_HOLD --es op down
     *
     * Releases ship the debug APK, so BuildConfig.DEBUG is true on real
     * phones: this receiver has to be exported to hear adb, and exported plus
     * unguarded would let any installed app start a recording. Sending it
     * therefore requires DUMP, which shell holds and no ordinary app can.
     */
    private fun registerDebugHoldReceiver() {
        if (debugReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.getStringExtra("op")) {
                    "down" -> Log.i(tag, "DEBUG hold down -> ${startHold(null)}")
                    "up" -> Log.i(tag, "DEBUG hold up -> ${handleUp()}")
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter("com.peter.mutter.DEBUG_HOLD"),
            android.Manifest.permission.DUMP,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
        debugReceiver = receiver
    }

    private fun registerRecycleReceiver() {
        if (recycleReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    DailyRecycler.ACTION, ACTION_MODEL_READY -> reloadEngine()
                }
            }
        }
        // RECEIVER_NOT_EXPORTED is mandatory on API 34+; the alarm broadcast is
        // self-targeted (setPackage), so it still reaches us.
        val filter = IntentFilter(DailyRecycler.ACTION).apply { addAction(ACTION_MODEL_READY) }
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        recycleReceiver = receiver
    }

    // Two callers: the ~5am alarm, which rebuilds the native recognizer to
    // bound heap growth over long uptime, and SetupActivity once a model
    // download finishes. Runs on modelExec so it serializes with the initial
    // load, and only when not capturing. transcribe/release are synchronized on
    // the engine, so even a drain racing this is memory-safe — worst case a
    // queued chunk reloads on demand. Never lost audio.
    private fun reloadEngine() {
        modelExec.execute {
            if (capturing.get()) {
                Log.i(tag, "reload skipped — capturing")
                return@execute
            }
            if (!downloader.hasAnyModel()) return@execute
            engine.release()
            segmenter.release()
            val ok = engine.load()
            val vadOk = segmenter.load()
            Log.i(tag, "reload: engine=$ok vad=$vadOk")
            Diagnostics.record(
                this,
                if (ok) "ready — speech model ${downloader.resolve()?.dir ?: "?"}"
                else "PROBLEM: speech model failed to load",
            )
        }
    }

    private fun haptic(ms: Long) {
        val effect = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        val vibrator: Vibrator? =
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        vibrator?.vibrate(effect)
    }
}
