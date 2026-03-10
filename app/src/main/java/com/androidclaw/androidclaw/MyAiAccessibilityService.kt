package com.androidclaw.androidclaw

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo


class MyAiAccessibilityService : AccessibilityService() {
    companion object {
        var instance: MyAiAccessibilityService? = null
    }
    override fun onServiceConnected() { instance = this }

    fun dumpScreenInfo(): String {
        val root = rootInActiveWindow ?: return "Empty Screen"
        val sb = StringBuilder()
        parseNode(root, sb)
        return sb.toString()
    }

    private fun parseNode(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        node ?: return
        if (node.isClickable || !node.text.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            sb.append("{text:'${node.text}', bounds:[${rect.left},${rect.top},${rect.right},${rect.bottom}]}\n")
        }
        for (i in 0 until node.childCount) parseNode(node.getChild(i), sb)
    }

    fun performClick(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}