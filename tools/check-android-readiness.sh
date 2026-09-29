#!/usr/bin/env bash
# Host checks only; actual native AAR/APK and physical-device testing are separate gates.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
command -v kotlinc >/dev/null || { echo 'Kotlin CLI is required.' >&2; exit 1; }
command -v java >/dev/null || { echo 'Java is required.' >&2; exit 1; }
command -v python3 >/dev/null || { echo 'Python 3 is required.' >&2; exit 1; }
kotlinc "$ROOT/core/src/main/kotlin/dev/forma/core/Acceleration.kt" \
  "$ROOT/core/src/main/kotlin/dev/forma/core/CodecRanking.kt" \
  "$ROOT/core/src/main/kotlin/dev/forma/core/MediaCodecCommand.kt" \
  "$ROOT/testing/core/unit/dev/forma/core/AccelerationChecks.kt" \
  "$ROOT/testing/core/unit/dev/forma/core/MediaCodecCommandChecks.kt" \
  -include-runtime -d "$TMP/readiness.jar"
java -jar "$TMP/readiness.jar"
python3 -m unittest discover -s "$ROOT/tools/tests" -v
