#!/usr/bin/env bash
# Runs a CI command; on failure, republishes the key error lines as a GitHub annotation so they are readable
# through the API (job logs live on blob storage some environments cannot reach). Usage: tools/ci-run.sh <cmd...>
set -o pipefail
LOG="$(mktemp)"
"$@" 2>&1 | tee "$LOG"
status=${PIPESTATUS[0]}
if [ "$status" -ne 0 ]; then
  summary=$(grep -E -A3 "^e: |error:|What went wrong|Could not|FAILED|Caused by|expected:|Exception|AssertionError|requires|minCompileSdk|^\s+at .*Test" "$LOG" \
    | grep -v -E "Run with --|^> Task|Get more help|^--$" | awk '!seen[$0]++' | head -80 | cut -c1-400)
  # %0A encodes newlines inside a single annotation.
  printf '::error title=%s::%s\n' "$(echo "$*" | cut -c1-80)" "$(echo "$summary" | sed ':a;N;$!ba;s/%/%25/g;s/\r//g;s/\n/%0A/g')"
fi
exit "$status"
