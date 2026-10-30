package com.missminutes.agent

import android.app.Activity
import android.os.Bundle

/**
 * An invisible screen, open for a split second, behind the notification's Talk button and the "can't hear you"
 * notification. Android only gives an app the microphone in the background if it was last started while on screen;
 * starting from here always counts as on screen, so the assistant can hear again.
 */
class TalkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action?.takeIf { it == MissMinutesService.ACTION_TALK } ?: MissMinutesService.ACTION_REFRESH
        runCatching { startForegroundService(MissMinutesService.intent(this, action)) }
        finish()
        overridePendingTransition(0, 0)
    }
}
