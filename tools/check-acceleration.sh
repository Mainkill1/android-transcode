#!/usr/bin/env bash
# Host policy/command/report tests only. Does not imply Android/device qualification.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
for command in kotlinc java python3; do
  command -v "$command" >/dev/null || { echo "$command is required." >&2; exit 1; }
done
kotlinc "$ROOT"/core/src/main/kotlin/dev/forma/core/*.kt \
  "$ROOT"/testing/acceleration/shared/*.kt \
  "$ROOT/testing/acceleration/core/HardwareAccelerationChecks.kt" \
  "$ROOT/testing/acceleration/core/EncoderConfigurationChecks.kt" \
  "$ROOT/testing/acceleration/core/AccelerationLabChecks.kt" \
  -include-runtime -d "$TMP/acceleration.jar"
java -jar "$TMP/acceleration.jar"
python3 -m unittest discover -s "$ROOT/testing/acceleration/host" -v
