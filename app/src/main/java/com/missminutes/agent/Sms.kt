package com.missminutes.agent

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends a text and waits for the phone to say it went. Long texts are split into parts: a text with an emoji fits
 * only 70 characters, and a single over-long text was dropped without a word (the away reply never arrived).
 */
object Sms {
    private const val ACTION = "com.missminutes.agent.SMS_SENT"

    /** Null when every part was sent, else why not, in plain words. */
    suspend fun send(context: Context, number: String, text: String): String? {
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sms.divideMessage(text)
        val action = "$ACTION.${System.nanoTime()}"
        val results = Channel<Int>(parts.size)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) { results.trySend(resultCode) }
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, IntentFilter(action))
        return try {
            val sent = ArrayList(parts.indices.map {
                PendingIntent.getBroadcast(context, it, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            })
            sms.sendMultipartTextMessage(number, null, parts, sent, null)
            val codes = withTimeoutOrNull(25_000) { List(parts.size) { results.receive() } }
                ?: return "the phone didn't confirm it within 25 seconds"
            when (codes.firstOrNull { it != Activity.RESULT_OK }) {
                null -> null
                SmsManager.RESULT_ERROR_NO_SERVICE -> "there's no mobile signal"
                SmsManager.RESULT_ERROR_RADIO_OFF -> "the mobile radio is off (airplane mode?)"
                else -> "the network didn't accept it"
            }
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
