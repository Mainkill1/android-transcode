#!/usr/bin/env bash
# Real desktop signal checks; no Android execution or simulated native codec claim.
set -euo pipefail
cd "$(dirname "$0")/../.."
for tool in kotlinc java ffmpeg ffprobe; do command -v "$tool" >/dev/null; done
pr6_run="build/output-completeness/$(date +%s)-$$"
mkdir -p "$pr6_run"
pr6_lib="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
pr6_coroutines="${KOTLIN_COROUTINES_JAR:-$pr6_lib/kotlinx-coroutines-core-jvm.jar}"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/$pr6_run"
kotlinc -cp "$pr6_coroutines" core/src/main/kotlin/dev/forma/core/*.kt \
  engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/{FfmpegBridge,ExportRetry,OutputValidation}.kt \
  testing/acceleration/host/OutputCompletenessMain.kt -include-runtime -d "$pr6_run/checks.jar"
java -cp "$pr6_run/checks.jar:$pr6_coroutines" dev.forma.ffmpeg.OutputCompletenessMainKt "$PWD/$pr6_run/media"
