package com.missminutes.agent

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Pico's sound cues (made by tools/pico_sounds.py): a few short, quiet bell tones from one family, used the same
 * way every time so they're learned without explanation. They play on the same channel and volume as her voice
 * (so they work whenever she can talk, vibrate mode included), and stop when Sounds is off in the app.
 */
class Earcons(context: Context, private val prefs: Prefs) {
    enum class Cue(val res: Int) {
        WAKE(R.raw.pico_wake),       // she's here and listening
        HEARD(R.raw.pico_heard),     // got what you said
        SUCCESS(R.raw.pico_success), // an action worked
        ERROR(R.raw.pico_error),     // something failed
        NOTIFY(R.raw.pico_notify),   // a reminder or alert
    }

    /** Little feeling sounds (tools/pico_moods.py), a tiny cartoon voice for the mood of a reply. */
    enum class Feeling(val tag: String, val res: Int, val title: String) {
        LOVE("love", R.raw.pico_mood_love, "Love: awww"),
        HAPPY("happy", R.raw.pico_mood_happy, "Happy: hehe"),
        PLAYFUL("playful", R.raw.pico_mood_playful, "Playful: a giggle"),
        EXCITED("excited", R.raw.pico_mood_excited, "Excited: yaay"),
        SURPRISED("surprised", R.raw.pico_mood_surprised, "Surprised: oooh"),
        CONFUSED("confused", R.raw.pico_mood_confused, "Confused: hmm?"),
        SAD("sad", R.raw.pico_mood_sad, "Sad: a little sob"),
        SCARED("scared", R.raw.pico_mood_scared, "Scared: eep"),
        ANGRY("angry", R.raw.pico_mood_angry, "Grumpy: hmph");

        companion object {
            fun of(tag: String) = entries.firstOrNull { it.tag == tag }
        }
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(AudioAttributes.Builder()
            // Her voice's channel: the system-sounds channel is muted on vibrate, which left the cues silent.
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()
    private val ids = Cue.entries.associateWith { pool.load(context, it.res, 1) }
    private val feelings = Feeling.entries.associateWith { pool.load(context, it.res, 1) }
    private val lastPlayed = HashMap<Feeling, Long>()

    fun play(cue: Cue) {
        if (!prefs.sounds) return
        ids[cue]?.let { pool.play(it, VOLUME, VOLUME, 1, 0, 1f) }
    }

    /**
     * The sound for a reply's mood ("love", "sad"…), now and then: never "calm", the same one at most every two
     * minutes, and "happy" (the most common) only half the time, so it stays a treat instead of a habit.
     */
    fun feel(tag: String) {
        if (!prefs.sounds || !prefs.moodSounds) return
        val f = Feeling.of(tag) ?: return
        val now = System.currentTimeMillis()
        if (now - (lastPlayed[f] ?: 0L) < 120_000L) return
        if (now - (lastPlayed.values.maxOrNull() ?: 0L) < 20_000L) return // one little sound per reply, not two
        if (f == Feeling.HAPPY && Math.random() < 0.5) return
        lastPlayed[f] = now
        PicoLog.i("Feeling sound: ${f.tag}")
        feelings[f]?.let { pool.play(it, VOLUME, VOLUME, 1, 0, 1f) }
    }

    /** Plays one feeling sound straight away (the app's "Hear them" button). */
    fun preview(f: Feeling) {
        feelings[f]?.let { pool.play(it, VOLUME, VOLUME, 1, 0, 1f) }
    }

    fun release() = pool.release()

    private companion object {
        /** Quieter than her voice: loudness is what makes cues annoying. */
        const val VOLUME = 0.6f
    }
}
