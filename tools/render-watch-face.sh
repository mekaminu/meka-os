#!/usr/bin/env bash
# Renders the watch face golden preview (watch face item, Meka 2026-10-13 12:13) from the core's WatchFaceLook into
# PNGs: tools/render-watch-face.sh [out dir] [sizeDp …] (default build/watch-face, 78 and 150 dp). Offline, like
# tools/core-verify.sh: the Kotlin compiler from the local Gradle distribution, java.awt for drawing.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTDIR="${1:-$ROOT/build/watch-face}"; shift || true
if [ -z "${GRADLE_HOME:-}" ]; then
  if command -v gradle >/dev/null; then GRADLE_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v gradle)")")")"
  else GRADLE_HOME="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*/*/gradle-* 2>/dev/null | head -1)"; fi  # CI: the wrapper's
fi
LIB="$GRADLE_HOME/lib"
KV=$(ls "$LIB" | sed -n 's/^kotlin-compiler-embeddable-\(.*\)\.jar$/\1/p' | head -1)
jar() { ls "$LIB"/$1 2>/dev/null | head -1; }
COMPILER_CP="$(jar "kotlin-compiler-embeddable-$KV.jar"):$(jar "kotlin-stdlib-$KV.jar"):$(jar "kotlin-reflect-$KV.jar"):$(jar "kotlin-daemon-embeddable-$KV.jar"):$(jar 'trove4j-*.jar'):$(jar 'annotations-*.jar'):$(jar 'kotlinx-coroutines-core-jvm-*.jar')"
RUNTIME_CP="$(jar "kotlin-stdlib-$KV.jar"):$(jar 'kotlinx-serialization-core-jvm-*.jar'):$(jar 'kotlinx-serialization-json-jvm-*.jar')"
CLASSES="${TMPDIR:-/tmp}/meka-watch-face-render"
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
MAIN_SRC=$(find "$ROOT/core"/{sync,domain,policy,wire}/src/commonMain -name '*.kt')
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -Xmulti-platform -opt-in=kotlin.ExperimentalStdlibApi \
  -Xcommon-sources="$(echo $MAIN_SRC | tr ' ' ',')" -cp "$RUNTIME_CP" -d "$CLASSES" \
  $MAIN_SRC "$ROOT/tools/watch-face-preview/RenderWatchFace.kt" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS\|^warning:' || true
java -Djava.awt.headless=true -cp "$CLASSES:$RUNTIME_CP" os.meka.tools.RenderWatchFaceKt "$OUTDIR" "$@"
