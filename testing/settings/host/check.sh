#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
mkdir -p "$ROOT/testing/results/settings"
OUT="$(mktemp -d "$ROOT/testing/results/settings/host-XXXXXXXX")"
trap 'rm -rf "$OUT"' EXIT
mapfile -t SOURCES < <(rg --files "$ROOT/core/src/main/kotlin" "$ROOT/testing/settings/core" -g '*.kt' | sort)
kotlinc "${SOURCES[@]}" -include-runtime -d "$OUT/settings.jar"
java -cp "$OUT/settings.jar" dev.forma.core.settings.SettingsChecks "$@"
