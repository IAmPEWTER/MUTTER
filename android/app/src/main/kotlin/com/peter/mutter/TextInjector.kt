package com.peter.mutter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pastes transcript chunks at the cursor via the clipboard — the one path that
 * works universally, including Samsung Notes and rich-text canvases that don't
 * expose `isEditable` through AX.
 *
 * Streaming-aware: the user's clipboard is read exactly once per hold ([begin])
 * and restored exactly once after the final chunk ([finish]). Reading the
 * clipboard per-chunk would fire the Android 12+/One UI "accessed clipboard"
 * notification on every chunk of a long dictation, and could race the restore
 * and strand a dictation fragment on the clipboard.
 */
class TextInjector(private val context: Context) {

    private val tag = "MutterInject"
    private val main = Handler(Looper.getMainLooper())
    private var saved: ClipData? = null
    private var savedValid = false

    private fun clipboard(): ClipboardManager? =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?: run { Log.e(tag, "no ClipboardManager"); null }

    /** Capture the user's clipboard once, at the start of a dictation hold. */
    fun begin() {
        val cm = clipboard() ?: return
        saved = try { cm.primaryClip } catch (t: Throwable) {
            Log.d(tag, "could not read prior clipboard", t); null
        }
        savedValid = true
    }

    /** Paste one chunk at the cursor. No clipboard read; restore happens in [finish]. */
    fun inject(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (text.isEmpty()) return false
        val target = node ?: run {
            Log.w(tag, "no target node")
            return false
        }
        val cm = clipboard() ?: return false
        try {
            cm.setPrimaryClip(ClipData.newPlainText("mutter", text))
        } catch (t: Throwable) {
            Log.e(tag, "setPrimaryClip failed", t)
            return false
        }
        val pasted = try {
            target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            Log.e(tag, "ACTION_PASTE threw", t)
            false
        }
        if (!pasted) Log.w(tag, "ACTION_PASTE returned false — text remains on clipboard")
        return pasted
    }

    /** Restore the user's clipboard once, after the final chunk of the hold. */
    fun finish() {
        if (!savedValid) return
        val prev = saved
        saved = null
        savedValid = false
        val cm = clipboard() ?: return
        // Delay so the last paste settles before we swap the clipboard back.
        main.postDelayed({
            try {
                if (prev != null) cm.setPrimaryClip(prev) else cm.clearPrimaryClip()
            } catch (t: Throwable) {
                Log.d(tag, "clipboard restore failed", t)
            }
        }, RESTORE_DELAY_MS)
    }

    companion object {
        const val RESTORE_DELAY_MS = 200L
    }
}
