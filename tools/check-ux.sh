#!/usr/bin/env bash
# JVM checks only. Android compilation, Compose execution and phone performance are separate gates.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'Install Kotlin CLI or use ./gradlew :core:test :app:testDebugUnitTest'; exit 1; }
KOTLIN_LIB="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc core/src/main/kotlin/dev/forma/core/WorkPolicy.kt core/src/test/kotlin/dev/forma/core/WorkPolicyChecks.kt -include-runtime -d "$TMP/policy.jar"
java -jar "$TMP/policy.jar"
kotlinc app/src/main/kotlin/dev/forma/app/work/*.kt app/src/test/kotlin/dev/forma/app/work/WorkRuntimeChecks.kt -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" -include-runtime -d "$TMP/runtime.jar"
java -cp "$TMP/runtime.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" dev.forma.app.work.WorkRuntimeChecksKt
