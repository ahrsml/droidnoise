#!/usr/bin/env bash
# Sube una carpeta de parches a /sdcard/DroidNoise/. La app recarga sola (FileObserver).
# Uso: ./push-patch.sh [carpeta]   (por defecto pd/droidnoise: lib/ + bank/)
set -euo pipefail
cd "$(dirname "$0")"
source ./adb-env.sh
dir=${1:-pd/droidnoise}
[[ -d $dir ]] || { echo "No existe la carpeta $dir" >&2; exit 1; }
./connect.sh >/dev/null
export ANDROID_SERIAL=$(<.adb_target)

adb shell mkdir -p /sdcard/DroidNoise
adb push "$dir"/. /sdcard/DroidNoise/
echo "Listo. Mira la recarga con: adb logcat -s DroidNoise:*"
