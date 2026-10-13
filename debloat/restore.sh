#!/usr/bin/env bash
# Reinstala para el usuario 0 lo que quitó debloat.sh (o los paquetes que pases como argumentos).
set -uo pipefail
cd "$(dirname "$0")"
source ../adb-env.sh
../connect.sh >/dev/null || exit 1
export ANDROID_SERIAL=$(<../.adb_target)
pkgs=("$@")
[[ ${#pkgs[@]} -eq 0 ]] && mapfile -t pkgs < <(sort -u removed.log disabled.log 2>/dev/null)
for pkg in "${pkgs[@]}"; do
    echo -n "$pkg: "
    adb shell cmd package install-existing "$pkg" </dev/null | tr -d '\r'
    adb shell pm enable --user 0 "$pkg" </dev/null >/dev/null 2>&1
done
