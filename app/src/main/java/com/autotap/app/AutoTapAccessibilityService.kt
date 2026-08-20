package com.autotap.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Runs while the user has enabled AutoTap under Settings -> Accessibility.
 *
 * The main activity (and the capture loop) reach this service through the
 * static [instance] to:
 *  - [tapByText] find a button/label by its text and perform a click, or
 *  - [tapAt] dispatch a raw tap gesture at screen coordinates.
 *
 * Accessibility node infos are reference-counted; every node obtained via
 * [AccessibilityNodeInfo.getChild] or [AccessibilityService.rootInActiveWindow]
 * must be recycled exactly once. The helpers below own that lifecycle.
 */
class AutoTapAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Tap the first on-screen node whose text contains [text]. */
    fun tapByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val match = findNodeByText(root, text)
        if (match == null) return false // BFS already recycled the subtree

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

    /** Tap at screen coordinates by finding the node at that point and clicking it. */
    fun tapAt(x: Int, y: Int): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeAtPoint(root, x, y)
        if (node != null) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            root.recycle()
            return clicked
        }
        root.recycle()
        // Fallback: use gesture dispatch if node not found
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo(x.toFloat() + 10f, y.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, 150)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    private fun findNodeAtPoint(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.contains(x, y) && node.isClickable) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            if (node !== root) node.recycle()
        }
        return null
    }

    /**
     * Breadth-first search for a node whose text contains [text]. The matched
     * node is returned (un-recycled); every other visited node is recycled
     * here, so the caller only has to recycle the returned node.
     */
    private fun findNodeByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val matches = node.text?.toString()?.contains(text, ignoreCase = true) == true
            if (matches) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            node.recycle()
        }
        return null
    }

    companion object {
        @Volatile
        var instance: AutoTapAccessibilityService? = null
    }
}
