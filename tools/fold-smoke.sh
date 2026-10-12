#!/usr/bin/env bash
# Smaller phone updates: the published `fold` build is shrunk by R8, which can strip something only found at run time.
# Installs the emulator's copy of that build, opens MEKA and keeps it open for 30 s; fails if it isn't running then or
# Android logged a crash for it. The crash (or the last lines) goes out as an annotation, readable through the API.
set -u
APK=android/app/build/outputs/apk/fold/app-fold.apk
PKG=os.meka.android
adb install -r "$APK" || { echo "::error title=Fold smoke::the fold build didn't install on the emulator"; exit 1; }
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
sleep 30
PID=$(adb shell pidof "$PKG" | tr -d '\r')
adb logcat -d -v brief AndroidRuntime:E '*:S' > /tmp/crash.txt
adb logcat -d -v brief | grep -E "FATAL|$PKG" | tail -40 | cut -c1-300 > /tmp/tail.txt
enc() { sed ':a;N;$!ba;s/%/%25/g;s/\r//g;s/\n/%0A/g' "$1"; }
if [ -z "$PID" ] || grep -q "FATAL EXCEPTION" /tmp/crash.txt; then
  printf '::error title=Fold smoke::MEKA (fold build) crashed or closed%%0A%s%%0A%s\n' "$(head -60 /tmp/crash.txt | cut -c1-300 | enc /dev/stdin)" "$(enc /tmp/tail.txt)"
  exit 1
fi
echo "::notice title=Fold smoke::MEKA (fold build) opened and ran 30 s without a crash"
