package com.missminutes.agent

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pico's own log: every line goes to Logcat and to a file on the phone (files/pico.log), because the phone's
 * Logcat fills up with system messages and drops hers within minutes. The file keeps the last ~256 KB, plus
 * the previous ~256 KB in pico.old.log.
 */
object PicoLog {
    private const val TAG = "MissMinutes"
    private const val MAX_BYTES = 256 * 1024
    private var file: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ENGLISH)

    fun init(context: Context) {
        file = File(context.filesDir, "pico.log")
    }

    fun i(msg: String) { Log.i(TAG, msg); write("I", msg, null) }
    fun w(msg: String, e: Throwable? = null) { Log.w(TAG, msg, e); write("W", msg, e) }
    fun e(msg: String, e: Throwable? = null) { Log.e(TAG, msg, e); write("E", msg, e) }

    /** Deletes the log files (they include what was said), for "Delete all my data". */
    @Synchronized
    fun clear() {
        val f = file ?: return
        f.delete()
        File(f.parentFile, "pico.old.log").delete()
    }

    @Synchronized
    private fun write(level: String, msg: String, e: Throwable?) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "pico.old.log"))
            val extra = e?.let { " | ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()
            f.appendText("${stamp.format(Date())} $level $msg$extra\n")
        }
    }
}
