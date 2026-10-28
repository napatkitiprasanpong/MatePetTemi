#!/usr/bin/env bash
# Usage: scripts/install-assets.sh <temi-ip> [path/to/avatar.vrm]
# Needs: temi Settings > Developer Tools > ADB Port Opening
set -euo pipefail
IP=${1:?usage: $0 <temi-ip> [avatar.vrm]}; VRM=${2:-}
DST=/sdcard/Android/data/com.example.matepet/files
MODEL=SmolLM2-360M-Instruct-Q4_K_M.gguf
[ -f "$MODEL" ] || curl -L -o "$MODEL" "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/$MODEL"
adb connect "$IP:5555"
adb shell mkdir -p "$DST"
adb push "$MODEL" "$DST/model.gguf"
[ -n "$VRM" ] && adb push "$VRM" "$DST/avatar.vrm"
echo "Done. Restart the app."
