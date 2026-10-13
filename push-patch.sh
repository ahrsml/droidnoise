#!/usr/bin/env bash
# Sube una carpeta de parches a /sdcard/DroidNoise/. La app recarga sola (FileObserver).
# Uso: ./push-patch.sh pd/test
set -euo pipefail
cd "$(dirname "$0")"
dir=${1:?Uso: ./push-patch.sh <carpeta>}
[[ -d $dir ]] || { echo "No existe la carpeta $dir" >&2; exit 1; }
./connect.sh >/dev/null
export ANDROID_SERIAL=$(<.adb_target)

adb shell mkdir -p /sdcard/DroidNoise
adb push "$dir"/. /sdcard/DroidNoise/
echo "Listo. Mira la recarga con: adb logcat -s DroidNoise:*"
