# Native editor and isolated ADB test implementation plan

## Goal and boundaries

Implement reusable native clip-edit logic and an ordered-timeline foundation while keeping Home source-first. Test the real production exporter from a removable instrumentation APK; do not add a shipping HTTP server, broadcast command receiver or alternate encoder. This is the first feature slice, not a full professional editing suite.

## Delivery sequence

| Step | Production/test files | Acceptance |
| --- | --- | --- |
| 1. Typed edits | `core/.../ClipEffects.kt`, `Models.kt`, `Planner.kt`; external `EditorChecks.kt` | Validated fields, trim-before-speed, preserved A/V offsets, filter availability, explicit hardware restrictions; red/green host tests |
| 2. Timeline/history | `EditTimeline.kt`; external `TimelineChecks.kt` | Immutable ordered clips, source-range operations, bounded undo/redo, invalid operations leave state unchanged |
| 3. Queue/export/UI | `ClipEffectsCodec.kt`, `JobCodec.kt`, ViewModel, exporter, service and UI | Schema-1 compatibility/schema-2 edits, per-source snapshots, edited duration, compact controls; no production dependency on tests |
| 4. Native preparation | `KitNextBridge.kt` | Preserve the existing qualified hardware route; block coded-pixel crops on unqualified display matrices |
| 5. Test separation | `testing/core/unit`, `testing/app/unit`, `testing/app/device`, `testing/shared`; Gradle source references | Relocate existing native tests and add new ones; update shell runner references; `formaTests=false` removes test-only source/dependency references and lab variant |
| 6. ADB commands | `EditorCommandTest.kt`, `adb/forma_device.py`, recipes | Isolated lab application ID, same exporter, typed requests, unique run IDs, JSON/argv/hashes, meaningful failures, no fake native passes |
| 7. Reproducible evidence | `testing/run_host.py`, shared cases, testing README | Host core/CLI tests and real desktop exports; report Android gates as unrun until actually executed |

## Review checklist

Preserve queue/service cancellation ownership; do not mutate source media or unrelated jobs. Reject missing native support for explicit native tests. Read old queues without silently discarding new effects. Keep filter parameters generated from typed values, not arbitrary user command strings. Apply deinterlacing before rotation; use edited-time fade/duration semantics. Verify both instrumentation status and matching report identity. Do not equate the host checkout revision with provenance of an installed APK.

## Execution and remaining gates

Host assertions and desktop export regressions passed as recorded in `docs/editor-framework.md`. Source changes and test isolation were implemented. Android/JVM dependency-backed builds, lab test packaging, physical-device commands, persistence execution, release APK inspection and visual/A/V qualification remain mandatory before readiness. The test README includes commands and failure/report procedures for those gates. No scheduled automation or new GitHub workflow is part of this work.
