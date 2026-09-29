#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'Kotlin CLI is required for these isolated host checks.' >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
kotlinc core/src/main/kotlin/dev/forma/core/Acceleration.kt \
  core/src/main/kotlin/dev/forma/core/CodecRanking.kt \
  tests/acceleration/CodecRankingChecks.kt -include-runtime -d "$work/checks.jar"
java -jar "$work/checks.jar"
python3 -m unittest discover -s tests/acceleration -p 'test_*.py' -v
