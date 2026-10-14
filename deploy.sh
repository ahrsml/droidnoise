#!/usr/bin/env bash
# Build + sube el APK al celu + espera a que lo instales desde la app + la lanza con logcat.
# HyperOS bloquea adb install sin cuenta Mi, por eso la instalación pasa por el instalador del sistema.
set -euo pipefail
cd "$(dirname "$0")"
source ./adb-env.sh
PKG=cl.droidnoise.player
REMOTE_APK=/sdcard/DroidNoise/apk/droidnoise.apk
TIMEOUT_S=600

./connect.sh >/dev/null
export ANDROID_SERIAL=$(<.adb_target)

installed_vc() {
    adb shell dumpsys package "$PKG" 2>/dev/null | grep -oE 'versionCode=[0-9]+' | head -1 | cut -d= -f2
}

vc=$(( $(date +%s) / 60 ))
before=$(installed_vc || true)
if [[ -n $before && $before -ge $vc ]]; then
    vc=$(( before + 1 ))
fi

./gradlew assembleDebug -PversionCode="$vc"

adb shell mkdir -p "$(dirname "$REMOTE_APK")"
adb push app/build/outputs/apk/debug/app-debug.apk "$REMOTE_APK"

if [[ -z $before ]]; then
    echo
    echo ">> DroidNoise no está instalada. Primera vez: abre el gestor de archivos del celu,"
    echo "   ve a DroidNoise/apk/droidnoise.apk e instálala a mano."
else
    adb shell am start -n "$PKG/.MainActivity" --ez install true >/dev/null
    echo
    echo ">> APK nuevo (versionCode $vc, instalado $before) en el celu."
    echo "   Se abrió el instalador; si no, toca UPD. Confirma en el celu."
fi

echo -n "Esperando la instalación"
deadline=$(( $(date +%s) + TIMEOUT_S ))
while :; do
    now_vc=$(installed_vc || true)
    [[ -n $now_vc && $now_vc -ge $vc ]] && break
    if (( $(date +%s) > deadline )); then
        echo; echo "Se acabó la espera (${TIMEOUT_S}s). Instalado: ${now_vc:-nada}" >&2
        exit 1
    fi
    echo -n .
    sleep 2
done
echo " ok (versionCode $now_vc)"

adb logcat -c
adb shell am start -n "$PKG/.MainActivity" >/dev/null
exec adb logcat -s DroidNoise:* AndroidRuntime:E
