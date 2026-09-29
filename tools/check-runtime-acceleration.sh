#!/usr/bin/env bash
# Host policy/contract tests, not Android API compilation or device qualification.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v kotlinc >/dev/null || { echo 'Kotlin CLI is required.' >&2; exit 1; }
lib="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
coroutines="${KOTLIN_COROUTINES_JAR:-$lib/kotlinx-coroutines-core-jvm.jar}"
[[ -f "$coroutines" ]] || { echo 'Set KOTLIN_COROUTINES_JAR to the coroutines runtime JAR.' >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
kotlinc -cp "$coroutines" core/src/main/kotlin/dev/forma/core/*.kt \
  engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/{FfmpegBridge,ManagedFfmpegBridge,ExportRetry,OutputValidation}.kt \
  testing/acceleration/jvm/*Checks.kt -include-runtime -d "$work/checks.jar"
for main in dev.forma.core.RuntimeCodecChecksKt dev.forma.core.EncoderChoiceChecksKt dev.forma.ffmpeg.ExportRetryChecksKt dev.forma.ffmpeg.OutputValidationChecksKt; do
  java -cp "$work/checks.jar:$coroutines" "$main"
done
python3 -m unittest discover -s tests/acceleration -p 'test_*.py' -v
