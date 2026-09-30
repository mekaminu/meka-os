#!/usr/bin/env bash
# Offline verification of the correctness kernel (ADR-011).
# Compiles core/{sync,domain,policy,wire,testing} commonMain + commonTest with the Kotlin compiler bundled in a local
# Gradle distribution and runs every *Test class under JUnit4. No network, no Maven.
#
# CI runs the same test sources with the real kotlin.test via `./gradlew check`.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_HOME="${GRADLE_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v gradle)")")")}"
LIB="$GRADLE_HOME/lib"
KV=$(ls "$LIB" | sed -n 's/^kotlin-compiler-embeddable-\(.*\)\.jar$/\1/p' | head -1)
[ -n "$KV" ] || { echo "kotlin-compiler-embeddable not found in $LIB" >&2; exit 2; }

jar() { ls "$LIB"/$1 2>/dev/null | head -1; }
COMPILER_CP="$(jar "kotlin-compiler-embeddable-$KV.jar"):$(jar "kotlin-stdlib-$KV.jar"):$(jar "kotlin-reflect-$KV.jar"):$(jar "kotlin-daemon-embeddable-$KV.jar"):$(jar 'trove4j-*.jar'):$(jar 'annotations-*.jar'):$(jar 'kotlinx-coroutines-core-jvm-*.jar')"
RUNTIME_CP="$(jar "kotlin-stdlib-$KV.jar"):$(jar 'junit-4*.jar'):$(jar 'hamcrest-core-*.jar'):$(jar 'kotlinx-serialization-core-jvm-*.jar'):$(jar 'kotlinx-serialization-json-jvm-*.jar')"

OUT="${TMPDIR:-/tmp}/meka-core-verify"
rm -rf "$OUT" && mkdir -p "$OUT"

MAIN_SRC=$(find "$ROOT/core"/{sync,domain,policy,wire,testing}/src/commonMain -name '*.kt')
TEST_SRC=$(find "$ROOT/core"/{sync,domain,policy,wire}/src/commonTest -name '*.kt')

echo "Kotlin $KV — compiling $(echo "$MAIN_SRC" | wc -l) main + $(echo "$TEST_SRC" | wc -l) test files"
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -Xallow-kotlin-package -Xmulti-platform -opt-in=kotlin.ExperimentalStdlibApi \
  -cp "$RUNTIME_CP" -d "$OUT" \
  "$ROOT/tools/kotlin-test-shim/KotlinTestShim.kt" $MAIN_SRC $TEST_SRC 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS' || true
[ -n "$(find "$OUT" -name '*Test.class' | head -1)" ] || { echo "compilation failed" >&2; exit 1; }

CLASSES=$(cd "$OUT" && find . -name '*Test.class' ! -name '*$*' | sed 's|^\./||; s|\.class$||; s|/|.|g' | sort)
java -cp "$OUT:$RUNTIME_CP" org.junit.runner.JUnitCore $CLASSES 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'
