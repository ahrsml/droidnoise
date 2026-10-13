#!/usr/bin/env bash
# Conecta adb por WiFi al celu. Uso: ./connect.sh [IP:PUERTO]
# El destino se guarda en .adb_target (no versionado).
set -euo pipefail
cd "$(dirname "$0")"
source ./adb-env.sh
TARGET_FILE=.adb_target

guide() {
    cat >&2 <<'TXT'

Para reconectar, en el celu: Opciones de desarrollador → Depuración inalámbrica.
  • Si solo cambió el puerto (pasa al reiniciar el celu): mira "Dirección IP y puerto"
    en esa pantalla y corre   ./connect.sh IP:PUERTO
  • Si se perdió el vínculo: toca "Vincular dispositivo con código" y corre
        adb pair IP:PUERTO_DE_VINCULACION     (pide el código de 6 dígitos)
    y después   ./connect.sh IP:PUERTO       (el puerto de la pantalla principal, no el de vincular)
TXT
}

try_connect() {
    local out
    out=$(adb connect "$1" 2>&1) || true
    echo "$out"
    [[ $out == *"connected to"* ]] && [[ $(adb -s "$1" get-state 2>/dev/null) == device ]]
}

if [[ $# -ge 1 ]]; then
    echo "$1" > "$TARGET_FILE"
fi
if [[ ! -s $TARGET_FILE ]]; then
    echo "No hay destino guardado en $TARGET_FILE." >&2
    guide
    exit 1
fi

target=$(<"$TARGET_FILE")
if try_connect "$target"; then
    exit 0
fi

# El puerto cambia entre reinicios: buscarlo por mDNS en la misma IP.
ip=${target%:*}
found=$(adb mdns services 2>/dev/null | grep '_adb-tls-connect' | grep -oE "${ip//./\\.}:[0-9]+" | head -1 || true)
if [[ -n $found && $found != "$target" ]]; then
    echo "Puerto nuevo por mDNS: $found"
    if try_connect "$found"; then
        echo "$found" > "$TARGET_FILE"
        exit 0
    fi
fi

echo "No pude conectar a $target." >&2
guide
exit 1
