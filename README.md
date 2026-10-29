# MatePet for temi

An offline AI companion for the [temi](https://www.robotemi.com) robot. A VRM avatar on temi's screen chats using a small on-device LLM (SmolLM2 360M via llama.cpp), and **temi speaks every reply with its own voice**. Say "open YouTube" and it launches the app. It also works with the [IAQ/IEQ monitoring app](https://github.com/napatkitiprasanpong/IAQ-IEQ-Mornitoring): ask about the air, start or stop missions and routines, or switch its screens by voice.

Targets temi V2 (Android 6.0.1, API 23) and V3 (Android 11). English only.

## Features
- Voice chat: temi speech-to-text -> local LLM -> temi text-to-speech. No cloud needed.
- Open apps by voice: "open / launch / start / run <app name>" (fuzzy-matched against installed apps).
- VRM 0.x and 1.0 avatars (Filament renderer): blinking and lip movement while temi talks. Falls back to a simple 2D face if no VRM is installed.
- Ignores temi's own voice and overlapping questions; strips markdown before speaking.

## Setup
1. Open in Android Studio (it will offer to install NDK and CMake 3.22.1). First sync downloads llama.cpp (pinned tag) from GitHub.
2. On temi: Settings > temi Developer Tools > ADB Port Opening.
3. Install the model and avatar: `scripts/install-assets.sh <temi-ip> path/to/avatar.vrm`
4. Run the app on temi. Say "Hey temi", then talk, or use the on-screen text box.

CI builds a debug APK on every push (`.github/workflows/build.yml`).

## Working with the IAQ monitoring app
Say (after "Hey temi"):
- "How is the air?" / "What is the CO2?" / "What's the temperature?" - answered from live sensor data
- "Open the monitoring app" - launches it
- "Start mission on Route A" / "Start inspection" / "Stop mission"
- "Start routine" / "Stop routine"
- "Show the dashboard" / "Open the mission tab"
- Any question about the room ("should I open a window?") - the local AI receives the live readings as context.

Commands are matched by rules, not by the small LLM, so they are fast and reliable. The IAQ-app side of the bridge is already built into the updated IAQ app; see [`iaq-bridge/INTEGRATION.md`](iaq-bridge/INTEGRATION.md).

## VRM tips
- Keep the file small (about 25 MB or less, textures 1024 px or smaller): temi V2 has limited RAM. `python3 tools/vrm_lite.py in.vrm out.vrm` drops morph targets no expression uses and downsizes textures (needs `pip install pillow`); a 101 MB model became 22 MB. Polygon count is not reduced: use Blender's Decimate for that.
- MToon toon shading is not supported; materials render as standard glTF.
- Needs blink and "A" (VRM 0.x) / "aa" (VRM 1.0) expressions for facial animation.
- VRM models have their own licenses. Never commit them (`*.vrm` is git-ignored).

## Limitations
- Listens only while MatePet is in the foreground. Mission, routine and data commands work from MatePet while the IAQ app stays in the background; if you bring the IAQ app to the front, return via the temi launcher.
- No conversation memory (each question stands alone). Small models often get facts wrong.
- Apps are matched by name; speech-to-text spelling ("you tube") may miss.

## Credits
[llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT), [Filament](https://github.com/google/filament) (Apache 2.0), [temi SDK](https://github.com/robotemi/sdk) (Apache 2.0), SmolLM2 by Hugging Face (Apache 2.0). Licensed MIT.
