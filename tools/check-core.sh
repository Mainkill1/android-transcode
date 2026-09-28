#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'Install Kotlin CLI, or run ./gradlew :core:test.' >&2; exit 1; }
mkdir -p core/build/host-checks
kotlinc core/src/main/kotlin/dev/forma/core/*.kt core/src/test/kotlin/dev/forma/core/CoreChecks.kt -include-runtime -d core/build/host-checks/checks.jar
java -jar core/build/host-checks/checks.jar
