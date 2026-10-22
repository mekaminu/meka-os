#!/usr/bin/env bash
# Runs a CI command; on failure, republishes the key error lines as a GitHub annotation so they are readable
# through the API (job logs live on blob storage some environments cannot reach). Usage: tools/ci-run.sh <cmd...>
#
# CI_RUN_WATCH_MIN=<minutes> (optional): if the command is still running after that long, annotate the log's last lines
# (with the native tests' "started" events, the test that hangs) and stop it, so the step fails with something readable
# instead of the job being cancelled at its limit with nothing (nightly Mac run 1087, 2026-10-22). Bash 3.2 safe (macOS).
set -o pipefail
LOG="$(mktemp)"
FLAG="$LOG.watched"

annotate() { # title, text
  printf '::error title=%s::%s\n' "$1" "$(printf '%s' "$2" | tr -d '\r' | awk '{gsub(/%/,"%25"); printf "%s%s", (NR>1 ? "%0A" : ""), $0}')"
}

WATCH_S=${CI_RUN_WATCH_SEC:-$(( ${CI_RUN_WATCH_MIN:-0} * 60 ))} # seconds, for trying it out
if [ "$WATCH_S" -le 0 ]; then
  "$@" 2>&1 | tee "$LOG"
  status=${PIPESTATUS[0]}
else
  "$@" >"$LOG" 2>&1 </dev/null &
  cmd=$!
  tail -n +1 -f "$LOG" &
  tailer=$!
  (
    sleep "$WATCH_S" </dev/null >/dev/null 2>&1
    kill -0 "$cmd" 2>/dev/null || exit 0
    touch "$FLAG"
    tail_lines=$(grep -v -E "^[[:space:]]*$|UP-TO-DATE$|NO-SOURCE$" "$LOG" | tail -40 | cut -c1-300)
    annotate "Still running after $((WATCH_S / 60)) min: $(echo "$*" | cut -c1-60)" "$tail_lines"
    # The test binaries first (they run under the Gradle daemon), then the command and anything it started.
    pkill -f 'test\.kexe' 2>/dev/null
    pkill -TERM -P "$cmd" 2>/dev/null; kill -TERM "$cmd" 2>/dev/null
    sleep 20 </dev/null >/dev/null 2>&1
    pkill -KILL -f 'test\.kexe' 2>/dev/null
    pkill -KILL -P "$cmd" 2>/dev/null; kill -KILL "$cmd" 2>/dev/null
  ) &
  watcher=$!
  wait "$cmd"
  status=$?
  pkill -P "$watcher" 2>/dev/null; kill "$watcher" 2>/dev/null
  sleep 1; kill "$tailer" 2>/dev/null
  if [ -f "$FLAG" ]; then
    echo "Stopped after ${WATCH_S} s (the annotation has the last lines)."
    exit 1
  fi
fi
if [ "$status" -ne 0 ]; then
  summary=$(grep -E -A3 "^e: |error:|What went wrong|Could not|FAILED|Caused by|expected:|Exception|AssertionError|requires|minCompileSdk|^\s+at .*Test" "$LOG" \
    | grep -v -E "Run with --|^> Task|Get more help|^--$" | awk '!seen[$0]++' | head -80 | cut -c1-400)
  # %0A encodes newlines inside a single annotation (awk, not GNU sed: the macOS runner has BSD sed).
  annotate "$(echo "$*" | cut -c1-80)" "$summary"
fi
exit "$status"
