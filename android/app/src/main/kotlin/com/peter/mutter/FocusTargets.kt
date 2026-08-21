package com.peter.mutter

import android.accessibilityservice.AccessibilityService
import android.text.InputType
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Where dictated text may go, and where it can actually land.
 *
 * Pure accessibility-tree queries, kept out of the hold lifecycle: deciding
 * whether a field is a password box has nothing to do with running a mic.
 */
object FocusTargets {

    private const val TAG = "MutterFocus"

    fun isPasswordField(node: AccessibilityNodeInfo): Boolean {
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    fun focused(service: AccessibilityService): AccessibilityNodeInfo? = try {
        service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    } catch (t: Throwable) {
        Log.d(TAG, "findFocus failed", t)
        null
    }

    fun focusedEditable(service: AccessibilityService): AccessibilityNodeInfo? {
        val node = focused(service) ?: return null
        if (!node.isEditable) return null
        if (isPasswordField(node)) return null
        return node
    }

    fun isImeUp(service: AccessibilityService): Boolean = try {
        service.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    } catch (t: Throwable) {
        Log.d(TAG, "windows lookup failed", t)
        false
    }

    /**
     * Fallback for apps whose editor doesn't surface as isEditable in the AX
     * tree (Samsung Notes, custom rich-text canvases): walk the active window
     * roots and take the first node that accepts ACTION_PASTE.
     */
    fun pasteTarget(service: AccessibilityService): AccessibilityNodeInfo? {
        val roots = try {
            service.windows.mapNotNull { it.root }
        } catch (t: Throwable) {
            Log.d(TAG, "windows.root failed", t)
            return null
        }
        for (root in roots) {
            val hit = acceptingPaste(root)
            if (hit != null) return hit
        }
        return null
    }

    private fun acceptingPaste(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE.id }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = acceptingPaste(child)
            if (hit != null) return hit
        }
        return null
    }
}
