#!/usr/bin/env bash
# Exercise the current production retry and output-verifier contracts on the host.
# The queued Android service path is covered by physical-device instrumentation.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
bash "$ROOT/tools/check-runtime-acceleration.sh"
