#!/usr/bin/env bash
# Builds MEKA OS for Android and installs it on a USB-connected phone (ADR-010: ADB installs are exempt from
# Android developer verification). Run from the repo root on the Mac:  ./tools/install-fold.sh
set -euo pipefail
cd "$(dirname "$0")/.."
# Keep a full log so problems can be diagnosed without scrolling the terminal.
mkdir -p build && exec > >(tee "build/install-fold.log") 2>&1

# Use Android Studio's bundled JDK and SDK unless already configured.
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || { echo "adb not found at $ADB — open Android Studio once so it installs the SDK."; exit 1; }
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
# No cable? Look for an already-paired phone on Wi-Fi (Wireless debugging on, same network).
if [ -z "$DEVICES" ]; then
  for _ in 1 2 3 4 5 6; do
    SVC=$("$ADB" mdns services 2>/dev/null | awk '/_adb-tls-connect/ {print $NF}' | head -1)
    [ -n "$SVC" ] && "$ADB" connect "$SVC" >/dev/null 2>&1 || true
    DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
    [ -n "$DEVICES" ] && break
    sleep 2
  done
fi
if [ -z "$DEVICES" ]; then
  cat <<'MSG'
No phone found. For a Wi-Fi install: on the Fold turn on Settings → Developer options → Wireless debugging
(same Wi-Fi as this Mac) and run this again. For a cable install, on the Fold:
  1. Settings → About phone → Software information → tap "Build number" 7 times (enables Developer options).
  2. Settings → Developer options → turn on "USB debugging".
  3. Plug it into the Mac with a data cable and tap "Allow" on the "Allow USB debugging?" prompt.
Then run this script again.
MSG
  "$ADB" devices
  exit 1
fi
# The same phone can show up twice (USB plus Wi-Fi, or Wi-Fi by name and by address): target one connection.
export ANDROID_SERIAL="$(echo "$DEVICES" | head -1)"
echo "Phone: $("$ADB" shell getprop ro.product.model | tr -d '\r') (Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r')) via $ANDROID_SERIAL"

./gradlew :android:app:installDebug
"$ADB" shell am start -n os.meka.android/.MainActivity >/dev/null
echo "Installed and launched MEKA OS on the phone."
