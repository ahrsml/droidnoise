#!/usr/bin/env bash
# Vincula el celu sin tener que leer IP ni puertos: los descubre por mDNS.
# En el celu: Depuración inalámbrica → "Vincular dispositivo con código" (deja el diálogo abierto).
# Uso: ./pair.sh CODIGO
set -euo pipefail
cd "$(dirname "$0")"
source ./adb-env.sh
code=${1:?Uso: ./pair.sh CODIGO_DE_6_DIGITOS}

find_service() {  # $1 = tipo mDNS; imprime IP:PUERTO
    adb mdns services 2>/dev/null | grep "$1" | grep -oE '[0-9]+(\.[0-9]+){3}:[0-9]+' | head -1 || true
}

echo -n "Buscando el diálogo de vinculación por mDNS"
pairing=""
for _ in $(seq 30); do
    pairing=$(find_service _adb-tls-pairing)
    [[ -n $pairing ]] && break
    echo -n .; sleep 2
done
echo
if [[ -z $pairing ]]; then
    echo "No lo encontré. ¿Está abierto 'Vincular dispositivo con código' y el PC en la misma red?" >&2
    exit 1
fi

echo "Vinculando con $pairing"
adb pair "$pairing" "$code"

ip=${pairing%:*}
connect=""
for _ in $(seq 10); do
    connect=$(adb mdns services 2>/dev/null | grep _adb-tls-connect | grep -oE "${ip//./\\.}:[0-9]+" | head -1 || true)
    [[ -n $connect ]] && break
    sleep 2
done
if [[ -z $connect ]]; then
    echo "Vinculado, pero no encontré el puerto de conexión. Corre ./connect.sh IP:PUERTO" >&2
    exit 1
fi
exec ./connect.sh "$connect"
