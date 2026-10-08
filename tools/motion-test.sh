#!/usr/bin/env bash
# Runs the Fold app's instrumentation tests on the connected emulator (the motion workflow) and republishes the
# MekaMotion samples and any test failure as one annotation, readable through the API like ci-run.sh's.
./gradlew :android:app:connectedDebugAndroidTest
status=$?
adb logcat -d -v brief -s MekaMotion:I TestRunner:E AndroidRuntime:E | grep -v "^--" | cut -c1-300 | tail -60 > /tmp/logcat.txt
printf '::notice title=logcat::%s\n' "$(sed ':a;N;$!ba;s/%/%25/g;s/\r//g;s/\n/%0A/g' /tmp/logcat.txt)"
exit "$status"
