#!/usr/bin/env bash
# Builds MEKA for the Galaxy Watch and installs it over Wi-Fi from the Mac (Galaxy Watch, slice 2; ADR-010: ADB installs
# are exempt from Android developer verification). Run from the repo root on the Mac:
#   ./tools/install-watch.sh                 (the watch already paired with this Mac)
#   ./tools/install-watch.sh <ip:port>       (first time: the address from the watch's "Pair new device" screen)
# On the watch first: Settings → About watch → Software → tap "Software version" 5 times (Developer options), then
# Developer options → turn on "ADB debugging" and "Wireless debugging" (same Wi-Fi as this Mac).
# Then open MEKA on the watch: it shows an 8-digit code; type it on the Fold in Ask → More → Watch.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build && exec > >(tee "build/install-watch.log") 2>&1

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || { echo "adb not found at $ADB — open Android Studio once so it installs the SDK."; exit 1; }
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

# MEKA's server address, built into the watch app (a watch has no room to type it). Asked once, kept on this Mac only.
URL_FILE="$HOME/.meka/server-url"
URL="${MEKA_SERVER_URL:-$(cat "$URL_FILE" 2>/dev/null || true)}"
if [ -z "$URL" ]; then
  echo "MEKA's server address: the one the Fold and this Mac were connected with (starts with https://)."
  printf "Address: "
  read -r URL
fi
case "$URL" in https://*) ;; *) echo "The address must start with https://"; exit 1 ;; esac
mkdir -p "$(dirname "$URL_FILE")" && printf '%s\n' "$URL" > "$URL_FILE"

# Pair the first time (the watch's Wireless debugging → Pair new device shows an address and a 6-digit code).
if [ -n "${1:-}" ]; then
  echo "Pairing with $1. Type the 6-digit code the watch shows, then press Return."
  "$ADB" pair "$1"
fi

watches() {
  for s in $("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}'); do
    if "$ADB" -s "$s" shell getprop ro.build.characteristics 2>/dev/null | grep -q watch; then echo "$s"; fi
  done
}
WATCH=""
for _ in $(seq 1 8); do
  WATCH=$(watches | head -1)
  [ -n "$WATCH" ] && break
  SVC=$("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF}')
  for a in $SVC; do "$ADB" connect "$a" >/dev/null 2>&1 || true; done
  sleep 2
done
if [ -z "$WATCH" ] && [ -t 0 ]; then
  echo "Couldn't find the watch. On the watch open Developer options → Wireless debugging and type the"
  printf "\"IP address & Port\" it shows (for example 192.168.1.80:5555), or just Return to stop: "
  read -r ADDR || ADDR=""
  [ -n "$ADDR" ] && { "$ADB" connect "$ADDR" || true; WATCH=$(watches | head -1); }
fi
if [ -z "$WATCH" ]; then
  echo "No watch found. Pair it once with: ./tools/install-watch.sh <ip:port from the watch's Pair new device screen>"
  "$ADB" devices
  exit 1
fi
export ANDROID_SERIAL="$WATCH"
echo "Watch: $("$ADB" shell getprop ro.product.model | tr -d '\r') via $ANDROID_SERIAL"

./gradlew :wear:app:installDebug -Pmeka.syncUrl="$URL"
"$ADB" shell am start -n os.meka.android/os.meka.wear.WatchActivity >/dev/null
echo "Installed and opened MEKA on the watch. Type the code it shows on the Fold: Ask → More → Watch."
