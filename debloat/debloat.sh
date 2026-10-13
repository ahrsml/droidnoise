#!/usr/bin/env bash
# Quita el bloat de packages.txt para el usuario 0. Reversible: ./restore.sh
set -uo pipefail
cd "$(dirname "$0")"
source ../adb-env.sh
../connect.sh >/dev/null || exit 1
export ANDROID_SERIAL=$(<../.adb_target)

installed=$(adb shell pm list packages | sed 's/^package://' | tr -d '\r')
ok=0; dis=0; skip=0; fail=0
while read -r pkg; do
    [[ -z $pkg || $pkg == \#* ]] && continue
    if ! grep -qx "$pkg" <<<"$installed" || grep -qx "$pkg" disabled.log 2>/dev/null; then ((skip++)); continue; fi
    out=$(adb shell pm uninstall --user 0 "$pkg" </dev/null 2>&1 | tr -d '\r')
    if [[ $out == Success ]]; then
        echo "$pkg" >> removed.log; ((ok++))
    elif [[ $(adb shell pm disable-user --user 0 "$pkg" </dev/null 2>&1) == *disabled-user* ]]; then
        # HyperOS protege algunas apps propias (Failure [-1000]); deshabilitarlas sí se puede.
        echo "$pkg" >> disabled.log; ((dis++))
    else
        echo "FALLÓ $pkg: $out" >&2; ((fail++))
    fi
done < packages.txt
echo "quitados $ok, deshabilitados $dis, ya no estaban $skip, fallaron $fail"
adb get-state >/dev/null && echo "adb sigue conectado"
