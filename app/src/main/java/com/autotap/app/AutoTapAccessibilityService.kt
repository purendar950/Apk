package com.autotap.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.DataOutputStream

class AutoTapAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoTapA11y"
        @Volatile var instance: AutoTapAccessibilityService? = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ── Text-based tap ───────────────────────────────────────────────────

    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = findNodeByText(root, text)
        if (match == null) { root.recycle(); return false }

        var node: AccessibilityNodeInfo? = match
        while (node != null && !node.isClickable) {
            val parent = node.parent
            node.recycle()
            node = parent
        }
        if (node == null) return false
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        node.recycle()
        return ok
    }

    // ── Coordinate tap — gesture-first, then input tap fallback ──────────

    fun tapAt(x: Int, y: Int): Boolean {
        Log.d(TAG, "tapAt($x, $y)")

        // Strategy 1 (primary): dispatchGesture — matches the proven auto-clicker approach.
        // Very short stroke (50 ms), single-point path, no lineTo.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 50)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val success = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    Log.d(TAG, "dispatchGesture completed OK")
                }
                override fun onCancelled(gestureDescription: GestureDescription) {
                    Log.w(TAG, "dispatchGesture cancelled — falling back to input tap")
                    inputTap(x, y)
                }
            }, null)
            Log.d(TAG, "dispatchGesture returned $success")
            if (success) return true
        }

        // Strategy 2: try node-based click (accessibility action)
        val root = rootInActiveWindow
        if (root != null) {
            val node = findClickableNodeAtPoint(root, x, y)
            if (node != null) {
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
                root.recycle()
                Log.d(TAG, "node click returned $ok")
                return ok
            }
            root.recycle()
        }

        // Strategy 3: input tap via shell (works on rooted / adb-enabled devices)
        Log.d(TAG, "Trying input tap fallback")
        return inputTap(x, y)
    }

    /** Shell-level input tap — bypasses accessibility entirely. */
    private fun inputTap(x: Int, y: Int): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", "input tap $x $y"))
            proc.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "input tap failed", e)
            false
        }
    }

    // ── Node helpers ─────────────────────────────────────────────────────

    private fun findClickableNodeAtPoint(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = Int.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val id = System.identityHashCode(node).toString()
            if (!seen.add(id)) continue
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(x, y) && node.isClickable) {
                val area = bounds.width() * bounds.height()
                if (area < bestArea) {
                    bestArea = area
                    best?.recycle()
                    best = node; continue
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            node.recycle()
        }
        return best
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString()?.contains(text, ignoreCase = true) == true) return node
            for (i in 0 until node.childCount) { node.getChild(i)?.let { queue.add(it) } }
            node.recycle()
        }
        return null
    }
}
