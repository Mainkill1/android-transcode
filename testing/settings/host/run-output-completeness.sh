#!/usr/bin/env bash
# Real desktop signal checks; no Android execution or simulated native codec claim.
set -euo pipefail
cd "$(dirname "$0")/../../.."
for tool in kotlinc java ffmpeg ffprobe; do command -v "$tool" >/dev/null; done
pr4_run="build/output-completeness/$(date +%s)-$$"
mkdir -p "$pr4_run"
pr4_lib="$(cd "$(dirname "$(command -v kotlinc)")/../lib" && pwd)"
pr4_coroutines="${KOTLIN_COROUTINES_JAR:-$pr4_lib/kotlinx-coroutines-core-jvm.jar}"
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$PWD/$pr4_run"
kotlinc -cp "$pr4_coroutines" core/src/main/kotlin \
  engine-ffmpeg/src/main/kotlin/dev/forma/ffmpeg/{FfmpegBridge,OutputValidation}.kt \
  testing/settings/host/OutputCompletenessMain.kt -include-runtime -d "$pr4_run/checks.jar"
pr4_input=()
if [[ $# -gt 0 ]]; then pr4_input+=("$1"); fi
java -cp "$pr4_run/checks.jar:$pr4_coroutines" dev.forma.ffmpeg.OutputCompletenessMainKt "$PWD/$pr4_run/media" "${pr4_input[@]}"
