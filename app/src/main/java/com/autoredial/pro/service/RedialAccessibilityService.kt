package com.autoredial.pro.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoredial.pro.telecom.CallManager

class RedialAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        CallManager.setAccessibilityEndCallListener {
            performInstantEndCallClick()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun performInstantEndCallClick() {
        val rootNode = rootInActiveWindow ?: return
        val endCallKeywords = listOf("End call", "Disconnect", "Hang up", "Cut", "End")

        for (keyword in endCallKeywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(keyword)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
        }

        performGlobalAction(GLOBAL_ACTION_BACK)
    }
}
