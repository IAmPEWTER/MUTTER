package com.peter.mutter

import android.accessibilityservice.AccessibilityService
import android.content.pm.ServiceInfo
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class MutterAccessibilityService : AccessibilityService() {

    private val tag = "MutterSvc"
    private val state = AtomicReference(MutterState.IDLE)
    private val stateLock = Any()

    private var recorder: AudioRecorder? = null
    private lateinit var engine: WhisperEngine
    private lateinit var downloader: ModelDownloader
    private lateinit var injector: TextInjector
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MutterWorker").apply { isDaemon = true }
    }
    private val modelExec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MutterModelLoad").apply { isDaemon = true }
    }
    @Volatile private var lockedDownTime: Long = 0L
    @Volatile private var lastInputNode: AccessibilityNodeInfo? = null
    @Volatile private var softCapWarned: Boolean = false
    private var softCapFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "MutterScheduler").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()
        downloader = ModelDownloader(this)
        engine = WhisperEngine(downloader.modelDir())
        injector = TextInjector(this)
        NotificationHelper.ensureChannel(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(tag, "service connected")
        modelExec.execute {
            if (downloader.isPresent()) {
                val ok = engine.load()
                Log.i(tag, "model load: $ok")
            } else {
                Log.w(tag, "model not present — user must run setup")
            }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(tag, "service unbinding")
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder = null
        engine.release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        try { recorder?.stop() } catch (_: Throwable) {}
        softCapFuture?.cancel(false)
        engine.release()
        worker.shutdownNow()
        modelExec.shutdownNow()
        scheduler.shutdownNow()
        super.onDestroy()
    }

    override fun onInterrupt() {
        Log.i(tag, "onInterrupt")
        abortToIdle()
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
            // Consume repeats while we're listening; pass through otherwise.
            return state.get() == MutterState.LISTENING
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
        val focused = try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (t: Throwable) {
            Log.d(tag, "findFocus failed", t); null
        }
        if (focused != null && isPasswordField(focused)) {
            Log.i(tag, "handleDown drop: password field focused")
            return false
        }

        val editable = focused?.takeIf { it.isEditable }
        val imeUp = isImeUp()
        // IME-up gate covers editors (Samsung Notes, rich-text canvases)
        // whose surface doesn't expose isEditable via AX but still raise
        // the soft keyboard.
        if (editable == null && !imeUp) {
            Log.d(tag, "handleDown drop: no editable + ime down")
            return false
        }
        Log.i(tag, "handleDown accept: editable=${editable != null} imeUp=$imeUp")

        synchronized(stateLock) {
            if (state.get() != MutterState.IDLE) return true // swallow stray
            state.set(MutterState.LISTENING)
        }
        lockedDownTime = System.currentTimeMillis()
        lastInputNode = editable  // may be null; transcribeAndInject falls back via findPasteTarget
        softCapWarned = false
        val ok = startRecording()
        if (!ok) {
            synchronized(stateLock) { state.set(MutterState.IDLE) }
            haptic(50)
            return false
        }
        promoteToForeground()
        softCapFuture = scheduler.schedule({
            if (state.get() == MutterState.LISTENING) {
                softCapWarned = true
                haptic(40)
            }
        }, SOFT_CAP_SEC.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        return true
    }

    private fun handleUp(): Boolean {
        synchronized(stateLock) {
            if (state.get() != MutterState.LISTENING) return false
            state.set(MutterState.TRANSCRIBING)
        }
        softCapFuture?.cancel(false)
        softCapFuture = null
        val rec = recorder
        recorder = null
        val samples = try {
            rec?.stop() ?: FloatArray(0)
        } catch (t: Throwable) {
            Log.e(tag, "stop failed", t)
            FloatArray(0)
        }
        demoteForeground()

        worker.execute {
            try {
                transcribeAndInject(samples)
            } finally {
                synchronized(stateLock) { state.set(MutterState.IDLE) }
            }
        }
        return true
    }

    private fun startRecording(): Boolean {
        val r = AudioRecorder(sampleRate = 16000, blockMs = 50, maxSeconds = 60)
        val ok = r.start()
        if (ok) recorder = r
        return ok
    }

    private fun transcribeAndInject(samples: FloatArray) {
        if (samples.size < 16000 * MIN_CAPTURED_SEC) {
            Log.i(tag, "clip too short (${samples.size} samples), dropping")
            return
        }
        if (EnergyGate.isSilent(samples, 16000)) {
            Log.i(tag, "energy gate dropped silent clip")
            return
        }
        if (!engine.isLoaded()) {
            val ok = engine.load()
            if (!ok) {
                Log.e(tag, "engine load failed at transcribe time")
                haptic(100)
                return
            }
        }
        val raw = engine.transcribe(samples, 16000).trim()
        if (raw.isEmpty()) {
            Log.i(tag, "empty transcript")
            return
        }
        if (HallucinationFilter.isHallucination(raw)) {
            Log.i(tag, "hallucination filtered (len=${raw.length})")
            return
        }
        val clean = Sanitizer.sanitize(raw)
        if (clean.isEmpty()) return
        val node = findFocusedEditable() ?: lastInputNode ?: findPasteTarget()
        val injected = injector.inject(node, clean)
        if (!injected) {
            Log.w(tag, "injection failed; text on clipboard")
            haptic(80)
        }
    }

    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        val node = try {
            findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (t: Throwable) {
            Log.d(tag, "findFocus failed", t)
            null
        } ?: return null
        if (!node.isEditable) return null
        if (isPasswordField(node)) return null
        return node
    }

    private fun isPasswordField(node: AccessibilityNodeInfo): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun isImeUp(): Boolean = try {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (t: Throwable) {
        Log.d(tag, "windows lookup failed", t)
        false
    }

    // Fallback target lookup for apps whose editor doesn't surface as
    // isEditable in the AX tree (Samsung Notes, custom rich-text canvases).
    // Walks active window roots and picks the first node whose action list
    // contains ACTION_PASTE.
    private fun findPasteTarget(): AccessibilityNodeInfo? {
        val roots = try {
            windows.mapNotNull { it.root }
        } catch (t: Throwable) {
            Log.d(tag, "windows.root failed", t)
            return null
        }
        for (root in roots) {
            val hit = findNodeWithPasteAction(root)
            if (hit != null) return hit
        }
        return null
    }

    private fun findNodeWithPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = findNodeWithPasteAction(child)
            if (hit != null) return hit
        }
        return null
    }

    private fun promoteToForeground() {
        try {
            startForeground(
                NotificationHelper.NOTIFICATION_ID,
                NotificationHelper.recordingNotification(this),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (t: Throwable) {
            Log.e(tag, "startForeground failed", t)
        }
    }

    private fun demoteForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.d(tag, "stopForeground failed", t)
        }
    }

    private fun abortToIdle() {
        synchronized(stateLock) {
            state.set(MutterState.IDLE)
        }
        try { recorder?.stop() } catch (_: Throwable) {}
        recorder = null
        demoteForeground()
    }

    private fun haptic(ms: Long) {
        val effect = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
        val vibrator: Vibrator? =
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        vibrator?.vibrate(effect)
    }

    companion object {
        private const val MIN_CAPTURED_SEC = 0.3f
        private const val SOFT_CAP_SEC = 30
    }
}
