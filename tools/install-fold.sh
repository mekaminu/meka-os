#!/usr/bin/env bash
# Builds MEKA OS for Android and installs it on a USB-connected phone (ADR-010: ADB installs are exempt from
# Android developer verification). Run from the repo root on the Mac:  ./tools/install-fold.sh
set -euo pipefail
cd "$(dirname "$0")/.."

# Use Android Studio's bundled JDK and SDK unless already configured.
if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
[ -x "$ADB" ] || { echo "adb not found at $ADB — open Android Studio once so it installs the SDK."; exit 1; }
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

DEVICES=$("$ADB" devices | awk 'NR>1 && $2=="device"{print $1}')
if [ -z "$DEVICES" ]; then
  cat <<'MSG'
No phone found. On the Fold:
  1. Settings → About phone → Software information → tap "Build number" 7 times (enables Developer options).
  2. Settings → Developer options → turn on "USB debugging".
  3. Plug it into the Mac with a data cable and tap "Allow" on the "Allow USB debugging?" prompt.
Then run this script again.
MSG
  "$ADB" devices
  exit 1
fi
echo "Phone: $("$ADB" shell getprop ro.product.model | tr -d '\r') (Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r'))"

./gradlew :android:app:installDebug
"$ADB" shell am start -n os.meka.android/.MainActivity >/dev/null
echo "Installed and launched MEKA OS on the phone."
