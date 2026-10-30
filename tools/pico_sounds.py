"""
Makes Pico's sound cues (res/raw/pico_*.wav): one family of soft bell tones, short and quiet, in the same timbre
so they read as one voice. Rising melodies mean "go" (wake, success, notify); falling and lower means a problem.
Run: python -I tools/pico_sounds.py   (from the MissMinutes folder). Edit the notes here, then re-run.
"""
import math
import os
import struct
import wave

RATE = 22050
PEAK = 0.32  # about -10 dBFS: well under her voice, so the cues never startle
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")

NOTE = {"C5": 523.25, "E5": 659.25, "G5": 783.99, "A5": 880.0, "C6": 1046.5, "D6": 1174.66,
        "E6": 1318.51, "G6": 1567.98, "B6": 1975.53}


def bell(freq, seconds, decay):
    """A soft bell: a sine with a little of the bell's inharmonic overtone, a 5 ms attack and an exponential fade."""
    out = []
    for i in range(int(RATE * seconds)):
        t = i / RATE
        attack = min(1.0, t / 0.005)
        env = attack * math.exp(-t / decay)
        tone = (math.sin(2 * math.pi * freq * t)
                + 0.18 * math.sin(2 * math.pi * freq * 2.0 * t) * math.exp(-t / (decay * 0.5))
                + 0.07 * math.sin(2 * math.pi * freq * 2.76 * t) * math.exp(-t / (decay * 0.35)))
        out.append(env * tone)
    return out


def cue(notes, length, decay=0.12, level=1.0):
    """[notes] are (name, start seconds); overlapping notes ring into each other."""
    buf = [0.0] * int(RATE * length)
    for name, start in notes:
        tone = bell(NOTE[name], length - start, decay)
        at = int(RATE * start)
        for i, v in enumerate(tone):
            if at + i < len(buf):
                buf[at + i] += v
    top = max(abs(v) for v in buf) or 1.0
    # Fade the last 20 ms so no cue ends on a click.
    fade = int(RATE * 0.02)
    for i in range(fade):
        buf[-1 - i] *= i / fade
    return [v / top * PEAK * level for v in buf]


CUES = {
    "pico_wake": cue([("E6", 0.0), ("B6", 0.08)], 0.38),
    "pico_heard": cue([("G6", 0.0)], 0.16, decay=0.05, level=0.55),
    "pico_success": cue([("C6", 0.0), ("E6", 0.07), ("G6", 0.14)], 0.46),
    "pico_error": cue([("E5", 0.0), ("C5", 0.11)], 0.42, decay=0.16, level=0.8),
    "pico_notify": cue([("G5", 0.0), ("D6", 0.11), ("G6", 0.22)], 0.62, decay=0.14),
}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, samples in CUES.items():
        with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in samples))
        print(f"wrote {name}.wav ({len(samples) / RATE * 1000:.0f} ms)")
