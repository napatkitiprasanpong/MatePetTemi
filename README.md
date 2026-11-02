# MatePet for temi

An offline AI companion for the [temi](https://www.robotemi.com) robot. A VRM avatar on temi's screen chats using a small on-device LLM (SmolLM2 360M via llama.cpp), and **temi speaks every reply with its own voice**. Say "open YouTube" and it launches the app. It also works with the [IAQ/IEQ monitoring app](https://github.com/napatkitiprasanpong/IAQ-IEQ-Mornitoring): ask about the air, start or stop missions and routines, or switch its screens by voice.

Targets temi V2 (Android 6.0.1, API 23) and V3 (Android 11). English only.

## Features
- **Floating pet**: MatePet is a transparent window over every other app. It stays on screen, and you can drag it around.
- **Voice only**: no text box, no keyboard. Say **"hey mate"** (even when the pet is hidden) and talk; after an answer it keeps listening for 6 seconds, so you can just continue.
- **Hold the pet** to open a menu: **Quit**, **Settings**, **Status**.
- Voice chat: offline wake word and speech recognition (Vosk) -> local LLM -> temi text-to-speech. No cloud needed. temi's own "Hey temi" still works as a second way in.
- Open apps by voice: "open / launch / start / run <app name>" (fuzzy-matched against installed apps).
- VRM 0.x and 1.0 avatars (Filament renderer): blinking and lip movement while temi talks. Falls back to a simple 2D face if no VRM is installed.
- Ignores its own voice (the microphone is paused while temi talks or thinks); strips markdown before speaking.

## Setup
1. Open in Android Studio (it will offer to install NDK and CMake 3.22.1). First sync downloads llama.cpp (pinned tag) from GitHub.
2. On temi: Settings > temi Developer Tools > ADB Port Opening.
3. Install the APK (`adb install -r app/build/outputs/apk/debug/app-debug.apk`), then the assets:
   `scripts/install-assets.sh <temi-ip> path/to/avatar.vrm`
   This pushes the LLM, the offline voice model (about 40 MB) and the avatar, and grants the two permissions MatePet needs (**display over other apps**, **microphone**).
4. Open MatePet once from the launcher. It starts the floating pet and closes its own (empty) screen. From then on: say "hey mate".

### The menu
- **Quit** hides the pet. MatePet keeps listening, so "hey mate" brings it back. (If the wake word is switched off, Quit stops MatePet instead; start it again from the launcher.)
- **Settings**: pet size, graphics (Fast = rendered at 70% resolution, Sharp = full), wake word on/off, transparent background on/off, and **Exit MatePet completely**.
- **Status**: what the voice engine, the AI model and the avatar are doing right now, frames per second, how fast the last answer was, and whether the IAQ app is reachable. Look here first if something does not work.

## Why it was slow, and what changed
- The avatar was rendered full screen at 60 fps with Filament's default anti-aliasing, ambient occlusion, bloom and shadows. Now it is a small window rendered at reduced resolution with those effects off, and **it only redraws when something changes** (a blink or talking), so an idle pet costs almost nothing.
- Small talk (hello, thanks, time, date, name) is answered instantly without the language model.
- The model is warmed up at start, so the first question no longer waits for the weights to load from flash. Answers are capped at two short sentences, and each sentence is spoken as soon as it is ready.
- The microphone is paused while temi thinks and talks, so speech recognition and the language model never compete for the CPU.
- Still too slow? Try `MODEL=SmolLM2-360M-Instruct-Q4_0.gguf scripts/install-assets.sh ...` (Q4_0 usually runs faster than Q4_K_M on ARM), or a smaller model such as SmolLM2-135M. In Settings, "Graphics: Fast" is already the default.

## Working with the IAQ monitoring app
Say (after "hey mate"):
- "How is the air?" / "What is the CO2?" / "What's the temperature?" - answered from live sensor data
- "Open the monitoring app" - launches it
- "Start mission on Route A" / "Start inspection" / "Stop mission"
- "Start routine" / "Stop routine"
- "Yes" or "Come back later" - answers the routine popup ("Please drink water") when one is waiting
- "Show the dashboard" / "Open the mission tab"
- Any question about the room ("should I open a window?") - the local AI receives the live readings as context.

Commands are matched by rules, not by the small LLM, so they are fast and reliable. The IAQ-app side of the bridge is already built into the updated IAQ app; see [`iaq-bridge/INTEGRATION.md`](iaq-bridge/INTEGRATION.md).

## VRM tips
- Keep the file small (about 25 MB or less, textures 1024 px or smaller): temi V2 has limited RAM. `python3 tools/vrm_lite.py in.vrm out.vrm` drops morph targets no expression uses and downsizes textures (needs `pip install pillow`); a 101 MB model became 22 MB. Polygon count is not reduced: use Blender's Decimate for that.
- MToon toon shading is not supported; materials render as standard glTF.
- Needs blink and "A" (VRM 0.x) / "aa" (VRM 1.0) expressions for facial animation.
- VRM models have their own licenses. Never commit them (`*.vrm` is git-ignored).

## Limitations
- The wake word and the commands are recognized by a small offline model (Vosk, English). Expect the odd miss or false trigger; the accepted phrases are listed in `GRAMMAR` in `VoiceEngine.kt`. Speak towards temi, in a reasonably quiet room.
- The microphone is shared with temi's own "Hey temi" detection. On temi V2 (Android 6) this normally works. On V3 (Android 11) Android may silence a second app that records at the same time; if Status shows the voice engine listening but "hey mate" never triggers, say "Hey temi" instead (still handled), and tell me what Status shows.
- After a reboot, open MatePet once from the launcher (Android 11 does not let a boot-started service use the microphone).
- Mission, routine and data commands for the IAQ app now work from any screen, because MatePet is no longer tied to being the foreground app.
- No conversation memory (each question stands alone). Small models often get facts wrong.
- Apps are matched by name; speech-to-text spelling ("you tube") may miss.

## Troubleshooting
- **Black or solid rectangle instead of a see-through pet**: Settings > Transparent background: Off, then tell me; transparency depends on a Filament detail that I could not test without the robot. `adb logcat -s MatePet Filament AndroidRuntime` shows what happened.
- **Pet is very dark**: change `FRONT_LUX` in `VrmAvatar.kt` (the light that replaces Filament's overhead-only light).
- **Nothing appears**: Status > Avatar. "Overlay refused" means the overlay permission is missing: `adb shell appops set com.example.matepet SYSTEM_ALERT_WINDOW allow`.

## Credits
[llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT), [Filament](https://github.com/google/filament) (Apache 2.0), [temi SDK](https://github.com/robotemi/sdk) (Apache 2.0), SmolLM2 by Hugging Face (Apache 2.0). Licensed MIT.
