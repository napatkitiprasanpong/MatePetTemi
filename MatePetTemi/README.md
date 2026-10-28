# MatePet for temi

An offline AI companion for the [temi](https://www.robotemi.com) robot. A VRM avatar on temi's screen chats using a small on-device LLM (SmolLM2 360M via llama.cpp), and **temi speaks every reply with its own voice**. Say "open YouTube" and it launches the app.

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

## VRM tips
- Keep the file small (about 15 MB or less, textures 1024 px or smaller): temi V2 has limited RAM.
- MToon toon shading is not supported; materials render as standard glTF.
- Needs blink and "A" (VRM 0.x) / "aa" (VRM 1.0) expressions for facial animation.
- VRM models have their own licenses. Never commit them (`*.vrm` is git-ignored).

## Limitations
- Listens only while the app is in the foreground; after opening another app, return via the temi launcher.
- No conversation memory (each question stands alone). Small models often get facts wrong.
- Apps are matched by name; speech-to-text spelling ("you tube") may miss.

## Credits
[llama.cpp](https://github.com/ggml-org/llama.cpp) (MIT), [Filament](https://github.com/google/filament) (Apache 2.0), [temi SDK](https://github.com/robotemi/sdk) (Apache 2.0), SmolLM2 by Hugging Face (Apache 2.0). Licensed MIT.
