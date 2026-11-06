package com.autoredial.pro.service

import android.telecom.Call
import android.telecom.InCallService
import com.autoredial.pro.telecom.CallManager

class AutoRedialInCallService : InCallService() {

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call?, state: Int) {
            super.onStateChanged(call, state)
            when (state) {
                Call.STATE_ACTIVE -> {
                    CallManager.notifyCallConnected()
                }
                Call.STATE_DISCONNECTED -> {
                    CallManager.unregisterCurrentCall()
                }
            }
        }
    }

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        call?.let {
            it.registerCallback(callCallback)
            CallManager.registerCurrentCall(it)
        }
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        call?.unregisterCallback(callCallback)
        CallManager.unregisterCurrentCall()
    }
}
