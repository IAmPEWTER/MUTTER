package com.peter.mutter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

class TextInjector(private val context: Context) {

    private val tag = "MutterInject"
    private val main = Handler(Looper.getMainLooper())

    fun inject(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (text.isEmpty()) return false
        val target = node ?: run {
            Log.w(tag, "no focused node")
            return false
        }
        if (!target.isEditable) {
            Log.w(tag, "focused node not editable")
            return false
        }
        return pasteAndRestore(target, text)
    }

    private fun pasteAndRestore(node: AccessibilityNodeInfo, text: String): Boolean {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (cm == null) {
            Log.e(tag, "no ClipboardManager")
            return false
        }
        val previous: ClipData? = try { cm.primaryClip } catch (t: Throwable) {
            Log.d(tag, "could not read prior clipboard", t)
            null
        }
        try {
            cm.setPrimaryClip(ClipData.newPlainText("mutter", text))
        } catch (t: Throwable) {
            Log.e(tag, "setPrimaryClip failed", t)
            return false
        }
        val pasted = try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (t: Throwable) {
            Log.e(tag, "ACTION_PASTE threw", t)
            false
        }
        // Restore clipboard ~200ms later regardless of paste success.
        main.postDelayed({
            try {
                if (previous != null) cm.setPrimaryClip(previous)
                else cm.clearPrimaryClip()
            } catch (t: Throwable) {
                Log.d(tag, "clipboard restore failed", t)
            }
        }, RESTORE_DELAY_MS)
        if (!pasted) {
            Log.w(tag, "ACTION_PASTE returned false — text remains on clipboard")
        }
        return pasted
    }

    companion object {
        const val RESTORE_DELAY_MS = 200L
    }
}
