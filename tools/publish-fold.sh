#!/usr/bin/env bash
# Builds MEKA OS for Android and hands the APK to MEKA on this Mac, which publishes it to your server; MEKA on the
# Fold then offers "MEKA update ready" and you tap Install once (build plan M1: self-updating phone app).
# Run from the repo root on the Mac that installed MEKA on the Fold (same signing key):  ./tools/publish-fold.sh
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build && exec > >(tee "build/publish-fold.log") 2>&1

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

# Debug builds are signed with this Mac's debug key, the same one install-fold.sh installs with, so Android accepts the
# published build as an update. Each build's versionCode is the commit count, so a newer commit is a newer build.
./gradlew :android:app:assembleDebug
APK="$PWD/android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "No APK at $APK"; exit 1; }
[ -f "$(dirname "$APK")/output-metadata.json" ] || { echo "Gradle didn't write output-metadata.json beside the APK"; exit 1; }

ENCODED=$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$APK")
open "mekaos://publish-fold-update?apk=$ENCODED"
echo "Built $(basename "$APK") (build $(git rev-list --count HEAD)). Confirm Publish in MEKA on this Mac."
