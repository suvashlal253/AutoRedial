package com.autoredial.pro.telecom

import android.telecom.Call
import java.util.concurrent.atomic.AtomicBoolean

object CallManager {
    private var activeCall: Call? = null
    private val isConnectedState = AtomicBoolean(false)
    private var accessibilityEndCallListener: (() -> Unit)? = null

    @Synchronized
    fun registerCurrentCall(call: Call) {
        activeCall = call
        isConnectedState.set(false)
    }

    @Synchronized
    fun unregisterCurrentCall() {
        activeCall = null
        isConnectedState.set(false)
    }

    @Synchronized
    fun notifyCallConnected() {
        isConnectedState.set(true)
    }

    fun isCallActive(): Boolean {
        return isConnectedState.get() || (activeCall?.state == Call.STATE_ACTIVE)
    }

    @Synchronized
    fun disconnectActiveCall(): Boolean {
        activeCall?.let {
            it.disconnect()
            return true
        }
        return false
    }

    fun setAccessibilityEndCallListener(listener: () -> Unit) {
        accessibilityEndCallListener = listener
    }

    fun triggerAccessibilityEndCall() {
        accessibilityEndCallListener?.invoke()
    }
}
