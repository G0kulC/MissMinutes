package com.missminutes.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only. Type a request instead of saying it, while Pico is running:
 *   adb shell am broadcast -n com.missminutes.agent/.DebugAskReceiver --es text "what's the weather in Chennai?"
 * Add --es mode announce to test a spoken alert instead.
 */
class DebugAskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        if (!MissMinutesService.running) return
        val action = if (intent.getStringExtra("mode") == "announce") MissMinutesService.ACTION_ANNOUNCE
                     else MissMinutesService.ACTION_ASK
        context.startService(MissMinutesService.intent(context, action).putExtra(MissMinutesService.EXTRA_TEXT, text))
    }
}
