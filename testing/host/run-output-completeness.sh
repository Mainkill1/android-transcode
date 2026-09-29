#!/usr/bin/env bash
# Real desktop signal checks; no Android execution or simulated native codec claim.
set -euo pipefail
cd "$(dirname "$0")/../.."
for tool in kotlinc java ffmpeg ffprobe; do command -v "$tool" >/dev/null; done
pr3_run="build/output-completeness/$(date +%s)-$$"
mkdir -p "$pr3_run"
pr3_lib="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
pr3_coroutines="${KOTLIN_COROUTINES_JAR:-$pr3_lib/kotlinx-coroutines-core-jvm.jar}"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/$pr3_run"
kotlinc -cp "$pr3_coroutines" core/src/main/kotlin \
  engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/{FfmpegBridge,OutputValidation}.kt \
  testing/host/OutputCompletenessMain.kt -include-runtime -d "$pr3_run/checks.jar"
pr3_input=()
if [[ $# -gt 0 ]]; then pr3_input+=("$1"); fi
java -cp "$pr3_run/checks.jar:$pr3_coroutines" dev.forma.ffmpeg.OutputCompletenessMainKt "$PWD/$pr3_run/media" "${pr3_input[@]}"
