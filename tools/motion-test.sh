#!/usr/bin/env bash
# Runs the Fold app's instrumentation tests on the connected emulator (the motion workflow) and republishes the
# MekaMotion samples and any test failure as one annotation, readable through the API like ci-run.sh's.
./gradlew :android:app:connectedDebugAndroidTest
status=$?
adb logcat -d -v brief -s MekaMotion:I TestRunner:E AndroidRuntime:E | grep -v "^--" | cut -c1-300 > /tmp/logcat-all.txt
# The failing tests' names first (a stack trace's tail alone doesn't say which test it was), then the latest lines.
adb logcat -d -v brief -s TestRunner:* | grep "failed: " | cut -c1-200 | head -20 > /tmp/logcat.txt
grep -E "Caused by|Exception|Error:" /tmp/logcat-all.txt | grep -v "^\s*at " | sort | uniq -c | sort -rn | head -8 | cut -c1-240 >> /tmp/logcat.txt
tail -30 /tmp/logcat-all.txt >> /tmp/logcat.txt
printf '::notice title=logcat::%s\n' "$(sed ':a;N;$!ba;s/%/%25/g;s/\r//g;s/\n/%0A/g' /tmp/logcat.txt)"
exit "$status"
