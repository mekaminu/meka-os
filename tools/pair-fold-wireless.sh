#!/usr/bin/env bash
# Pairs the Fold with this Mac over Wi-Fi (Android "Wireless debugging"), then installs MEKA OS without a cable.
# Usage: tools/pair-fold-wireless.sh <ip:pairing-port>   (from Wireless debugging → Pair device with pairing code)
# Pairing is once per phone+Mac. Later, with Wireless debugging on and both on the same Wi-Fi, install-fold.sh finds
# the phone by itself.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build && exec > >(tee "build/pair-fold.log") 2>&1
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
TARGET="${1:?usage: pair-fold-wireless.sh <ip:port>}"

echo "Pairing with $TARGET. Type the 6-digit Wi-Fi pairing code shown on the phone, then press Return."
"$ADB" pair "$TARGET"

# The phone advertises its connect port over mDNS; adb usually connects by itself within a few seconds.
for _ in $(seq 1 15); do
  if "$ADB" devices | awk 'NR>1 && $2=="device"' | grep -q .; then break; fi
  SVC=$("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF}' | head -1)
  [ -n "$SVC" ] && "$ADB" connect "$SVC" >/dev/null 2>&1 || true
  sleep 2
done
"$ADB" devices
exec ./tools/install-fold.sh
