#!/usr/bin/env bash
# Baja al PC las grabaciones nuevas del celu: /sdcard/DroidNoise/rec/<parche>/<fecha>.wav
# → ~/Música/DroidNoise/<parche>/<fecha>.wav (una carpeta por parche, como en el celu).
set -euo pipefail
cd "$(dirname "$0")"
source ./adb-env.sh
./connect.sh >/dev/null
export ANDROID_SERIAL=$(<.adb_target)
dest="$HOME/Música/DroidNoise"
n=0
while read -r f; do
    [[ -z $f ]] && continue
    rel=${f#/sdcard/DroidNoise/rec/}
    if [[ ! -f "$dest/$rel" ]]; then
        mkdir -p "$dest/$(dirname "$rel")"
        adb pull "$f" "$dest/$rel" >/dev/null && echo "↓ $rel" && n=$((n + 1))
    fi
done < <(adb shell 'find /sdcard/DroidNoise/rec -name "*.wav" 2>/dev/null' | tr -d '\r')
echo "$n grabaciones nuevas en $dest"
