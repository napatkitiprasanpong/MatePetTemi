#!/usr/bin/env bash
# Usage: scripts/install-assets.sh <temi-ip> [avatar.vrm]   (default avatar: avatar/Phrolova_lite.vrm)
# Needs: temi Settings > Developer Tools > ADB Port Opening, and the MatePet APK already installed (adb install -r app-debug.apk)
# Optional: MODEL=SmolLM2-360M-Instruct-Q4_0.gguf scripts/install-assets.sh ...   (Q4_0 is often faster than Q4_K_M on temi's CPU)
set -euo pipefail
IP=${1:?usage: $0 <temi-ip> [avatar.vrm]}; VRM=${2:-}
PKG=com.example.matepet
DST=/sdcard/Android/data/$PKG/files
MODEL=${MODEL:-SmolLM2-360M-Instruct-Q4_K_M.gguf}
VOSK=vosk-model-small-en-us-0.15      # offline "hey mate" + speech recognition, about 40 MB
[ -f "$MODEL" ] || curl -L -o "$MODEL" "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/$MODEL"
[ -d "$VOSK" ] || { curl -L -o "$VOSK.zip" "https://alphacephei.com/vosk/models/$VOSK.zip" && unzip -q "$VOSK.zip"; }
adb connect "$IP:5555"
adb shell mkdir -p "$DST/vosk-model"
adb push "$MODEL" "$DST/model.gguf"
adb push "$VOSK/." "$DST/vosk-model/"
VRM=${VRM:-avatar/Phrolova_lite.vrm}
[ -f "$VRM" ] && adb push "$VRM" "$DST/avatar.vrm"
# let the app draw over other apps and use the microphone (temi has no easy settings page for this)
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow || echo "could not grant overlay permission (is the APK installed?)"
adb shell pm grant $PKG android.permission.RECORD_AUDIO || echo "could not grant microphone permission (is the APK installed?)"
echo "Done. Open MatePet once from the launcher; after that just say \"hey mate\"."
