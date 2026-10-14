#!/usr/bin/env bash
# Baja al PC las grabaciones nuevas del celu (REC) a ~/Música/DroidNoise/.
set -euo pipefail
cd "$(dirname "$0")"
source ./adb-env.sh
./connect.sh >/dev/null
export ANDROID_SERIAL=$(<.adb_target)
dest="$HOME/Música/DroidNoise"
mkdir -p "$dest"
n=0
while read -r f; do
    [[ -z $f ]] && continue
    name=$(basename "$f")
    if [[ ! -f "$dest/$name" ]]; then
        adb pull "$f" "$dest/$name" >/dev/null && echo "↓ $name" && n=$((n + 1))
    fi
done < <(adb shell 'ls /sdcard/DroidNoise/rec/*.wav 2>/dev/null' | tr -d '\r')
echo "$n grabaciones nuevas en $dest"
