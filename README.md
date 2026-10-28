# Miss Minutes 2.1 (Pro)

Your personal Android voice agent. Say the wake word, an animated minute-dial orb appears at the bottom of
the screen, you speak Tamil, English or Tanglish, and she does it on your phone and answers out loud.

## Speed update
- **Streaming replies:** she starts speaking after Claude's first sentence instead of waiting for the whole answer.
- **Quick replies:** simple commands go to Claude Haiku 5.5 automatically (toggle in Behaviour). Fable removed.
- **Fillers:** if Claude is slow to start, she says a short cached "ஒரு நிமிஷம்…" or "One sec…".

## What's new in 2.1
- **Your own wake word:** type any English phrase in the app ("Hey Jarvis", "Ok Buddy") and tap Save.
  No training: the offline Vosk model already knows the words, and the app checks each one.
  Replaces Picovoice Porcupine, so there's no Picovoice key or .ppn file any more.
- **Reminders:** "remind me at 6:30 to call Amma". She says it out loud at that time and posts a
  notification with Snooze and Done. They survive a restart.
- **Notification alerts:** "tell me when Ravi messages", "alert me about any WhatsApp". She reads matching
  notifications out loud as they arrive, then listens in case you want to reply.
- **Interrupt her:** start talking while she speaks and she stops to listen (Behaviour → Let me interrupt her).
- **Faster voice:** Sarvam speech streams, so she starts talking in about 0.3 s. Bulbul v3 voices, plus faster
  v4-flash voices.
- **Better hearing:** Saaras v4 in Tamil code-mix mode, so Tamil, English and Tanglish never come out as Hindi.
- **Answers questions directly:** general questions from her own knowledge, live ones with Claude's built-in
  web search (never by opening Google).
- **New look:** a round orb instead of the bar; a panel with Yes / Cancel only when she asks to confirm; a
  welcome board, setup checklist and card-based settings in the Outfit font; new app and notification icons.

## What's new in 2.0
- **Models:** Claude Sonnet 5.5 by default. Pick Opus 5.5, Sonnet 5.5 or Haiku 5.5 in the app.
  If a model isn't available or is overloaded, she automatically moves to the next one.
- **Uses any app:** she can read the screen, tap, type, scroll and chain steps
  ("open Swiggy and search biryani", "turn on Wi-Fi hotspot in Settings").
- **Reads and replies to messages:** "any new messages?", "reply to Ravi: on the way".
- **Live answers from the web:** news, weather, scores, prices.
- **Memory:** "remember that Amma is saved as Mom". See and clear memories in the app.
- **More controls:** flashlight, volume, play/pause/next music, timers, YouTube search, battery and network.
- **New overlay:** a 60-tick minute dial that stretches with your voice (amber), sweeps while she works (teal)
  and pulses in sync with her voice (coral), with a glowing screen edge while she's working inside your apps.
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
- "Remind me in 20 minutes to take the clothes out" / "Tell me when Priya replies"

## Setup
1. **Keys:** Claude (console.anthropic.com), Sarvam (dashboard.sarvam.ai).
2. **Wake word:** "Hey Miss Minutes" by default, detected offline by Vosk; no key or file needed. Change it in the
   app under Wake word. The first time you tap Start she downloads the small English model (about 40 MB) once,
   so be online for that.
3. **Build:** open this folder in Android Studio, let it sync, connect your phone with USB debugging on, press Run.
4. **On the phone:** paste the keys and tap Save keys, turn on everything in Permissions, then tap Start.
   - If Android says **Restricted setting** for Accessibility or Notification access: App info → ⋮ menu →
     Allow restricted settings, then try again.

## Cost and speed
Sonnet 5.5 is the everyday default. Opus 5.5 is smartest for long tasks inside apps but slower.
With **Quick replies** on, simple commands (calls, alarms, flashlight, music) automatically use Haiku 5.5.

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
