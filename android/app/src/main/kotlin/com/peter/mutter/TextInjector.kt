package com.peter.mutter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pastes transcript chunks at the cursor via the clipboard — the one path that
 * works universally, including Samsung Notes and rich-text canvases that don't
 * expose `isEditable` through AX. When ACTION_PASTE is refused, falls back to
 * an ACTION_SET_TEXT splice at the cursor.
 *
 * Streaming-aware: the user's clipboard is read once per hold ([begin]) and
 * written back once after the final chunk ([finish]). Reading it per-chunk
 * would fire the One UI "accessed clipboard" toast on every chunk and could
 * race the restore.
 *
 * Never-drop: when chunks could not be injected, [finish] puts THEM on the
 * clipboard instead of the saved content — restoring the old clipboard used
 * to destroy the failed transcript 200 ms after the failure buzz.
 *
 * Threading: [begin], [inject], [finish] run on the service's FIFO worker;
 * the delayed clipboard write runs on the main looper. All clipboard state
 * is guarded by this object's monitor.
 */
class TextInjector(private val context: Context) {

    private val tag = "MutterInject"
    private val main = Handler(Looper.getMainLooper())
    private var saved: ClipData? = null
    private var savedValid = false
    // finish() writes its outcome to the clipboard RESTORE_DELAY_MS later (so
    // the final paste settles first). If the next hold's begin() lands inside
    // that window, the clipboard still holds OUR last chunk — begin() adopts
    // the pending payload as its snapshot and cancels the late write, which
    // would otherwise clobber the new hold's chunks mid-paste.
    private var pendingWrite: ClipData? = null
    private var writePending = false

    private fun clipboard(): ClipboardManager? =
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?: run { Log.e(tag, "no ClipboardManager"); null }

    /** Capture the user's clipboard once, at the start of a dictation hold. */
    @Synchronized
    fun begin() {
        val cm = clipboard() ?: return
        saved = if (writePending) {
            writePending = false // cancel the late write; we own its payload now
            pendingWrite
        } else {
            try { cm.primaryClip } catch (t: Throwable) {
                Log.d(tag, "could not read prior clipboard", t); null
            }
        }
        savedValid = true
    }

    /** Inject one chunk at the cursor: paste, then SET_TEXT splice fallback. */
    @Synchronized
    fun inject(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (text.isEmpty()) return false
        val target = node ?: run {
            Log.w(tag, "no target node")
            return false
        }
        val cm = clipboard() ?: return false
        val clipSet = try {
            cm.setPrimaryClip(ClipData.newPlainText("mutter", text))
            true
        } catch (t: Throwable) {
            Log.e(tag, "setPrimaryClip failed", t)
            false
        }
        if (clipSet) {
            val pasted = try {
                target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (t: Throwable) {
                Log.e(tag, "ACTION_PASTE threw", t)
                false
            }
            if (pasted) return true
            Log.w(tag, "ACTION_PASTE refused — trying SET_TEXT splice")
        }
        return setTextSplice(target, text)
    }

    // Splice the chunk into the node's existing text at the cursor, then move
    // the cursor past it. Only works on real isEditable nodes — exactly the
    // fields most likely to refuse ACTION_PASTE.
    private fun setTextSplice(node: AccessibilityNodeInfo, insert: String): Boolean = try {
        if (!node.isEditable) {
            false
        } else {
            val existing = node.text?.toString() ?: ""
            val rawStart = node.textSelectionStart
            val rawEnd = node.textSelectionEnd
            val selStart = if (rawStart in 0..existing.length) rawStart else existing.length
            val selEnd = if (rawEnd in selStart..existing.length) rawEnd else selStart
            val newText = existing.substring(0, selStart) + insert + existing.substring(selEnd)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText,
                )
            }
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) {
                val cursor = selStart + insert.length
                val sel = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel)
            }
            ok
        }
    } catch (t: Throwable) {
        Log.e(tag, "setTextSplice failed", t)
        false
    }

    /**
     * End-of-hold clipboard handling, queued on the FIFO worker after the
     * last chunk. [leftover] non-null = chunks that could not be injected:
     * they go on the clipboard so the dictation survives; otherwise the
     * clipboard captured by [begin] is restored.
     */
    @Synchronized
    fun finish(leftover: String?) {
        val hadBegin = savedValid
        val prev = saved
        saved = null
        savedValid = false
        if (!hadBegin && leftover == null) return
        val cm = clipboard() ?: return
        val payload = if (leftover != null) ClipData.newPlainText("mutter", leftover) else prev
        pendingWrite = payload
        writePending = true
        // Delay so the last paste settles before the clipboard changes.
        main.postDelayed({
            synchronized(this) {
                if (!writePending || pendingWrite !== payload) return@postDelayed
                writePending = false
                pendingWrite = null
                try {
                    if (payload != null) cm.setPrimaryClip(payload) else cm.clearPrimaryClip()
                } catch (t: Throwable) {
                    Log.d(tag, "clipboard finish failed", t)
                }
            }
        }, RESTORE_DELAY_MS)
    }

    companion object {
        const val RESTORE_DELAY_MS = 200L
    }
}
