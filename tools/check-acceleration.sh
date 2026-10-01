#!/usr/bin/env bash
# Host policy, lab-command, report and production-verifier checks; no device qualification.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
for tool in kotlinc java python3; do
  command -v "$tool" >/dev/null || { echo "$tool is required." >&2; exit 1; }
done
KOTLIN_LIB="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
kotlinc "$ROOT/core/src/main/kotlin/dev/forma/core/Acceleration.kt" \
  "$ROOT/core/src/main/kotlin/dev/forma/core/CodecRanking.kt" \
  "$ROOT/tests/acceleration/CodecRankingChecks.kt" -include-runtime -d "$work/ranking.jar"
java -jar "$work/ranking.jar"
kotlinc -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" \
  "$ROOT/core/src/main/kotlin" "$ROOT"/testing/acceleration/shared/*.kt \
  "$ROOT/testing/acceleration/core/HardwareAccelerationChecks.kt" \
  "$ROOT/testing/acceleration/core/EncoderConfigurationChecks.kt" \
  "$ROOT/testing/acceleration/core/AccelerationLabChecks.kt" \
  -include-runtime -d "$work/lab.jar"
java -cp "$work/lab.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" dev.forma.core.HardwareAccelerationChecksKt
python3 -m unittest discover -s "$ROOT/testing/acceleration/host" -v
bash "$ROOT/tools/check-transcode-verification.sh"
