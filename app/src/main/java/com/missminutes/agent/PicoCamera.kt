package com.missminutes.agent

import android.content.Context
import android.content.Intent

/**
 * Connects the camera window ([CameraActivity]) with the assistant: whether it's open, the photo of what it sees
 * right now (sent with each question) and the ghost shown in its corner.
 */
object PicoCamera {
    @Volatile var open = false
        private set
    /** Which camera is showing, for the assistant to know ("back" or "front"). */
    @Volatile var facing = "back"
    /** A JPEG of what the camera sees now (taken on the main thread by the window), or null if it can't. */
    @Volatile var frame: (suspend () -> ByteArray?)? = null
    /** The ghost in the window's corner: it shows the same face as the floating one. */
    @Volatile var ghost: GhostView? = null
    /** A line under the camera: "Listening…", "Thinking…", then what the assistant says. */
    @Volatile var onCaption: ((String) -> Unit)? = null
    /** Told when the window opens or closes (the floating ghost steps aside while it's open). */
    @Volatile var onOpenChanged: ((Boolean) -> Unit)? = null

    fun opened() { open = true; PicoLog.i("Camera window open"); onOpenChanged?.invoke(true) }
    fun closed() { open = false; frame = null; ghost = null; onCaption = null; PicoLog.i("Camera window closed"); onOpenChanged?.invoke(false) }

    fun openWindow(context: Context, front: Boolean = false, fromVoice: Boolean = false) {
        context.startActivity(Intent(context, CameraActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("from_voice", fromVoice)
            .putExtra(CameraActivity.EXTRA_FRONT, front))
    }
}
