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
  # %0A encodes newlines inside a single annotation (awk, not GNU sed: the macOS runner has BSD sed).
  printf '::error title=%s::%s\n' "$(echo "$*" | cut -c1-80)" "$(printf '%s' "$summary" | tr -d '\r' | awk '{gsub(/%/,"%25"); printf "%s%s", (NR>1 ? "%0A" : ""), $0}')"
fi
exit "$status"
