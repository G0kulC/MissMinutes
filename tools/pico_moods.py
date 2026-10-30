"""
Makes Pico's little feeling sounds (res/raw/pico_mood_*.wav): a tiny cartoon voice, not words. A buzzing "voice"
goes through three resonances that shape the vowel (the way a mouth does), and each sound is a few keyframes of
pitch, vowel, loudness and breath that the voice glides between. Small and high-pitched, so it reads as cute.
Run: python -I tools/pico_moods.py   (from the MissMinutes folder). Edit the keyframes here, then re-run.
"""
import math
import os
import random
import struct
import wave

RATE = 22050
PEAK = 0.34
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
random.seed(7)

# Vowel resonances (Hz) for an adult; scaled up for a small character.
VOWELS = {
    "a": (800, 1200, 2500), "o": (500, 900, 2400), "u": (330, 800, 2300), "e": (500, 1900, 2500),
    "i": (300, 2300, 3000), "m": (280, 1300, 2300), "w": (300, 700, 2200),
}
SMALL = 1.22  # a smaller "mouth": every resonance a bit higher


def lerp(a, b, t):
    return a + (b - a) * t


def at(keys, t):
    """Keyframes [(time, {...})] -> the values at time t, gliding linearly between frames."""
    if t <= keys[0][0]:
        return keys[0][1]
    for (t0, k0), (t1, k1) in zip(keys, keys[1:]):
        if t0 <= t <= t1:
            f = (t - t0) / (t1 - t0) if t1 > t0 else 0
            out = {}
            for name in k0:
                if name == "v":
                    out["v"] = tuple(lerp(a, b, f) for a, b in zip(VOWELS[k0["v"]] if isinstance(k0["v"], str) else k0["v"],
                                                                   VOWELS[k1["v"]] if isinstance(k1["v"], str) else k1["v"]))
                else:
                    out[name] = lerp(k0[name], k1[name], f)
            return out
    return keys[-1][1]


def voice(keys, length, vibrato=(0.0, 0.0), tremolo=(0.0, 0.0)):
    """keys: [(t, {"f0": Hz, "v": vowel, "amp": 0..1, "breath": 0..1})]."""
    out = []
    phase = 0.0
    tilt = 0.0
    res = [[0.0, 0.0] for _ in range(3)]
    coefs = None
    for n in range(int(RATE * length)):
        t = n / RATE
        k = at(keys, t)
        vowel = k["v"] if isinstance(k["v"], tuple) else VOWELS[k["v"]]
        if n % 32 == 0:  # resonance shape follows the vowel
            coefs = []
            for i, f in enumerate(vowel):
                f *= SMALL
                bw = (80, 110, 160)[i]
                c = -math.exp(-2 * math.pi * bw / RATE)
                b = 2 * math.exp(-math.pi * bw / RATE) * math.cos(2 * math.pi * f / RATE)
                coefs.append((1 - b - c, b, c))
        f0 = k["f0"] * (1 + vibrato[1] * math.sin(2 * math.pi * vibrato[0] * t)) * (1 + random.uniform(-0.004, 0.004))
        phase = (phase + f0 / RATE) % 1.0
        saw = 2 * phase - 1
        tilt = tilt * 0.55 + saw * 0.45  # soften the buzz
        src = tilt * (1 - k["breath"]) + random.uniform(-1, 1) * k["breath"] * 0.6
        x = src
        for i, (a, b, c) in enumerate(coefs):  # mouth resonances in a row
            y = a * x + b * res[i][0] + c * res[i][1]
            res[i][1], res[i][0] = res[i][0], y
            x = y
        trem = 1 - tremolo[1] * (0.5 + 0.5 * math.sin(2 * math.pi * tremolo[0] * t))
        out.append(x * k["amp"] * trem)
    return out


def bell(freq, seconds, decay=0.12):
    return [min(1.0, (i / RATE) / 0.005) * math.exp(-(i / RATE) / decay) * math.sin(2 * math.pi * freq * i / RATE)
            for i in range(int(RATE * seconds))]


def mix(length, *parts):
    """parts: (start seconds, samples, gain)."""
    buf = [0.0] * int(RATE * length)
    for start, samples, gain in parts:
        s = int(RATE * start)
        for i, v in enumerate(samples):
            if s + i < len(buf):
                buf[s + i] += v * gain
    return buf


def finish(buf):
    top = max(abs(v) for v in buf) or 1.0
    fade = int(RATE * 0.03)
    for i in range(min(fade, len(buf))):
        buf[-1 - i] *= i / fade
        buf[i] *= min(1.0, i / (RATE * 0.004))
    return [v / top * PEAK for v in buf]


def syllable(f0a, f0b, va, vb, dur, amp=1.0, breath=0.08, attack=0.02):
    return [(0.0, {"f0": f0a, "v": va, "amp": 0.0, "breath": breath}),
            (attack, {"f0": f0a, "v": va, "amp": amp, "breath": breath}),
            (dur * 0.75, {"f0": lerp(f0a, f0b, 0.75), "v": vb, "amp": amp * 0.85, "breath": breath}),
            (dur, {"f0": f0b, "v": vb, "amp": 0.0, "breath": breath})]


def sparkle(start=0.0):
    return [(start, bell(2093, 0.3, 0.07), 0.25), (start + 0.06, bell(2637, 0.3, 0.07), 0.22), (start + 0.12, bell(3136, 0.35, 0.08), 0.2)]


def giggle(n, base):
    parts = []
    for i in range(n):
        f = base * (1 + 0.06 * (i % 2)) * (1 + 0.03 * i)
        parts.append((i * 0.13, voice(syllable(f, f * 0.92, "e", "i", 0.09, breath=0.35, attack=0.008), 0.09), 1.0))
    return parts


SOUNDS = {
    # love: a soft, melting "awww~"
    "pico_mood_love": finish(mix(1.0,
        (0.0, voice([(0.0, {"f0": 560, "v": "a", "amp": 0.0, "breath": 0.12}), (0.05, {"f0": 560, "v": "a", "amp": 1.0, "breath": 0.12}),
                     (0.45, {"f0": 470, "v": "o", "amp": 0.9, "breath": 0.15}), (0.75, {"f0": 400, "v": "w", "amp": 0.6, "breath": 0.2}),
                     (0.9, {"f0": 380, "v": "u", "amp": 0.0, "breath": 0.25})], 0.9, vibrato=(5.5, 0.025)), 1.0),
        *sparkle(0.55))),
    # sad: a little sob, "uhu-huuu"
    "pico_mood_sad": finish(mix(1.05,
        (0.0, voice(syllable(470, 430, "u", "o", 0.14, breath=0.3), 0.14), 0.8),
        (0.2, voice(syllable(450, 400, "u", "o", 0.14, breath=0.3), 0.14), 0.8),
        (0.42, voice([(0.0, {"f0": 440, "v": "o", "amp": 0.0, "breath": 0.25}), (0.04, {"f0": 440, "v": "o", "amp": 0.9, "breath": 0.25}),
                      (0.45, {"f0": 330, "v": "u", "amp": 0.6, "breath": 0.35}), (0.6, {"f0": 300, "v": "u", "amp": 0.0, "breath": 0.4})],
                     0.6, vibrato=(7.0, 0.05)), 1.0))),
    # angry: a grumpy "hmph!"
    "pico_mood_angry": finish(mix(0.5,
        (0.0, voice([(0.0, {"f0": 260, "v": "m", "amp": 0.0, "breath": 0.1}), (0.02, {"f0": 270, "v": "m", "amp": 1.0, "breath": 0.1}),
                     (0.2, {"f0": 210, "v": "m", "amp": 0.9, "breath": 0.15}), (0.24, {"f0": 200, "v": "m", "amp": 0.0, "breath": 0.2})],
                    0.24, vibrato=(30.0, 0.04)), 1.0),
        (0.22, voice([(0.0, {"f0": 200, "v": "u", "amp": 0.0, "breath": 0.95}), (0.02, {"f0": 200, "v": "u", "amp": 0.7, "breath": 0.95}),
                      (0.14, {"f0": 200, "v": "u", "amp": 0.0, "breath": 0.95})], 0.14), 0.8))),
    # happy: a short smile, "hehe"
    "pico_mood_happy": finish(mix(0.4, *giggle(2, 600))),
    # playful: a giggle, "hehehehe"
    "pico_mood_playful": finish(mix(0.7, *giggle(5, 640))),
    # excited: "yaay!" and a sparkle
    "pico_mood_excited": finish(mix(0.85,
        (0.0, voice([(0.0, {"f0": 520, "v": "i", "amp": 0.0, "breath": 0.08}), (0.03, {"f0": 540, "v": "i", "amp": 0.8, "breath": 0.08}),
                     (0.15, {"f0": 700, "v": "a", "amp": 1.0, "breath": 0.08}), (0.4, {"f0": 820, "v": "e", "amp": 0.8, "breath": 0.1}),
                     (0.5, {"f0": 840, "v": "i", "amp": 0.0, "breath": 0.1})], 0.5, vibrato=(6.0, 0.02)), 1.0),
        *sparkle(0.35))),
    # surprised: "oooh!"
    "pico_mood_surprised": finish(mix(0.55,
        (0.0, voice([(0.0, {"f0": 380, "v": "o", "amp": 0.0, "breath": 0.1}), (0.03, {"f0": 400, "v": "o", "amp": 1.0, "breath": 0.1}),
                     (0.32, {"f0": 760, "v": "u", "amp": 0.9, "breath": 0.12}), (0.45, {"f0": 700, "v": "u", "amp": 0.0, "breath": 0.15})],
                    0.45), 1.0))),
    # confused: "hmm?" going up at the end, like a question
    "pico_mood_confused": finish(mix(0.7,
        (0.0, voice([(0.0, {"f0": 420, "v": "m", "amp": 0.0, "breath": 0.06}), (0.04, {"f0": 420, "v": "m", "amp": 1.0, "breath": 0.06}),
                     (0.3, {"f0": 380, "v": "m", "amp": 0.9, "breath": 0.06}), (0.55, {"f0": 590, "v": "m", "amp": 0.9, "breath": 0.08}),
                     (0.65, {"f0": 620, "v": "m", "amp": 0.0, "breath": 0.1})], 0.65), 1.0))),
    # scared: two tiny "eep!"s, trembling
    "pico_mood_scared": finish(mix(0.5,
        (0.0, voice(syllable(760, 900, "i", "i", 0.13, breath=0.15, attack=0.01), 0.13, tremolo=(18, 0.4)), 1.0),
        (0.2, voice(syllable(800, 960, "i", "i", 0.16, breath=0.15, attack=0.01), 0.16, tremolo=(18, 0.4)), 1.0))),
}

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, samples in SOUNDS.items():
        with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, v)) * 32767)) for v in samples))
        print(f"wrote {name}.wav ({len(samples) / RATE * 1000:.0f} ms)")
