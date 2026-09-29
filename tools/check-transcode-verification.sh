#!/usr/bin/env bash
# Exercises the real app transcoder with host-only storage and native boundaries.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLIN_LIB="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc "$ROOT"/core/src/main/kotlin/dev/forma/core/*.kt \
  "$ROOT/engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/FfmpegBridge.kt" \
  "$ROOT/app/src/main/kotlin/dev/forma/app/data/FfmpegTranscoder.kt" \
  "$ROOT"/testing/acceleration/appHost/*.kt \
  -cp "$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" -include-runtime -d "$TMP/verification.jar"
java -cp "$TMP/verification.jar:$KOTLIN_LIB/kotlinx-coroutines-core-jvm.jar" dev.forma.app.data.TranscodeVerificationChecksKt
