# Miss Minutes 2.0 (Pro)

Your personal Android voice agent. Say the wake word, a floating card with an animated minute dial
slides up, you speak Tamil, English or Tanglish, and she does it on your phone and answers out loud.

## What's new in 2.0
- **Best model by default:** Claude Fable 5.1. Pick Opus 5.5, Sonnet 5.5 or Haiku 5.5 in the app.
  If a model isn't available or is overloaded, she automatically moves to the next one.
- **Uses any app:** she can read the screen, tap, type, scroll and chain steps
  ("open Swiggy and search biryani", "turn on Wi-Fi hotspot in Settings").
- **Reads and replies to messages:** "any new messages?", "reply to Ravi: on the way".
- **Live answers from the web:** news, weather, scores, prices.
- **Memory:** "remember that Amma is saved as Mom". See and clear memories in the app.
- **More controls:** flashlight, volume, play/pause/next music, timers, YouTube search, battery and network.
- **New overlay:** a 60-tick minute dial that stretches with your voice (amber), sweeps while she works (teal)
  and pulses in sync with her voice (coral). Live transcript, typed-out replies, a chip for every action,
  and a glowing screen edge while she's working inside your apps. Tap to skip her speaking, swipe down to close.
- **Faster and cheaper:** her instructions are cached between requests. Music ducks while she talks.
- **Safer keys:** API keys are encrypted with the phone's secure keystore.
- **Redesigned app screen** with permission checklist, voice test and sensitivity slider.

## Things to try
- "Hey Miss Minutes, WhatsApp-la John-ku 'we meet morning 7 o'clock' nu anuppu"
- "Any new messages?" then "Reply to Priya: 5 minutes la varen"
- "Amma-ku call pannu"
- "Open YouTube and play Anirudh songs"
- "Naalai kaalai 6 manikku alarm vai" / "10 minute timer"
- "Flashlight on" / "Volume 40" / "Next song"
- "Chennai weather enna?"
- "Remember that my office leaves at 6 pm"

## Setup
1. **Keys:** Claude (console.anthropic.com), Picovoice (console.picovoice.ai), Sarvam (dashboard.sarvam.ai).
2. **Wake word (optional at first):** in Picovoice Console make "Hey Miss Minutes" for Android, rename the file to
   `hey_miss_minutes.ppn` and put it in `app/src/main/assets/`. Until then the wake word is **"Jarvis"**.
   The file's version must match the Porcupine library version in `app/build.gradle.kts` (v3).
3. **Build:** open this folder in Android Studio, let it sync, connect your phone with USB debugging on, press Run.
4. **On the phone:** paste the keys and tap Save keys, turn on everything in Permissions, then tap Start.
   - If Android says **Restricted setting** for Accessibility or Notification access: App info → ⋮ menu →
     Allow restricted settings, then try again.

## Cost and speed
Fable 5.1 gives the best results but is the slowest and most expensive. For quick everyday commands,
Sonnet 5.5 or Haiku 5.5 reply faster and cost much less. Switch any time in the app.

## Add a new power
1. Add a tool to `TOOLS_JSON` in `ClaudeBrain.kt` (name, description, inputs).
2. Add a matching case in `PhoneActions.run()`.
3. Optional: add a chip label in `ToolLabels.kt`.

## If something doesn't work
- Errors appear on the floating card and in Android Studio's Logcat (filter: MissMinutes).
- WhatsApp opens but doesn't send: Accessibility is off, or WhatsApp renamed its Send button (`SEND_IDS` in `MinutesAccessibility.kt`).
- Robotic voice: Sarvam failed and Android's voice was used. Use Test voice in the app to see the exact error.
- Model errors: pick another model in Brain; your account may not have access to every model.
- Sarvam model or field names changed: update them at the top of `SarvamClient.kt`.

Keep your keys private and don't share your installed app; your keys live inside it.
