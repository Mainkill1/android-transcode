# Forma Foundation Implementation Plan

Goal: a compact, expandable Android application foundation, committed to main as requested.
Architecture: app -> core and engine-ffmpeg -> core. Kotlin/Compose and Gradle Kotlin DSL. Native FFmpeg integration is optional at build time, never a simulated runtime.
Spec: ../specs/2026-09-28-foundation-design.md

## Global constraints
- Simple and Advanced share the same settings.
- Preserve sources; persist immutable queue snapshots and interrupted state.
- Arguments are a vector, not an interpolated shell string.
- No native binaries means an explicit unavailable state, not successful conversion.

## Tasks
- [x] Pin build configuration and add core tests; observe failure before implementation.
- [x] Implement models/planner/queue rules and rerun the same tests.
- [x] Add native binding seam, unavailable implementation, and source-built adapter.
- [x] Add Android storage, persistent queue, foreground runner and Compose layout.
- [x] Add developer docs, CI, instrumentation smoke tests; review and verify before final commit.

## Review focus
Invalid settings/trim; source URI loss; process death during native work; partial output; missing encoders or unsupported HDR. Tests own pure planning/transition cases; Android and physical-device validation are reported separately.

Verification boundary: 39 core checks passed locally; shell/XML/TOML/YAML checks passed. Android and native verification must be read from the relevant CI/device runs, not inferred from this task checklist.
