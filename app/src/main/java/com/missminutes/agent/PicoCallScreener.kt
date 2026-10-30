package com.missminutes.agent

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Sees each incoming call before it rings (with the call screening role the user grants in the app). It only acts
 * while Not-available mode is on ([Away]); otherwise every call rings as normal.
 */
class PicoCallScreener : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
        val number = details.handle?.schemeSpecificPart.orEmpty()
        PicoLog.init(this)
        val decision = if (incoming && number.isNotEmpty()) {
            runCatching { Away.screenCall(this, Prefs(this), number) }.onFailure { PicoLog.w("Call screening failed", it) }.getOrNull()
        } else null
        val response = if (decision == Away.Decision.DECLINE) {
            // Like tapping Decline: it shows as a missed call, and "forward when busy" can catch it.
            CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(false).build()
        } else {
            CallResponse.Builder().build()
        }
        respondToCall(details, response)
    }
}
