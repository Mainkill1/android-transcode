#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mapfile -t SOURCES < <(find "$ROOT/core/src/main/kotlin/dev/forma/core/settings" "$ROOT/testing/settings/core" -name '*.kt' -print | sort)
kotlinc "$ROOT/core/src/main/kotlin/dev/forma/core/Models.kt" "${SOURCES[@]}" -include-runtime -d "$OUT/settings.jar"
java -cp "$OUT/settings.jar" dev.forma.core.settings.SettingsChecks "$@"
java -cp "$OUT/settings.jar" dev.forma.core.settings.PowerRegressionChecks
java -cp "$OUT/settings.jar" dev.forma.core.settings.PowerRuntimeChecks
