# Android implementation agent: start here

Implement **Forma as a native Android application**, not another Studio mockup.

Read, in order:
1. [Android handoff](android-handoff.md): requirements, actual implementation status and stop conditions.
2. [Acceleration design](android-acceleration.md): MediaCodec first; GPU and NPU are separate stages.
3. [Implementation plan](superpowers/plans/2026-09-28-native-android.md): file ownership, interfaces and acceptance tests.
4. [Upload contract](upload-limits.md) and [timeline contract](timeline-editor.md): behavior to port, not Python to embed.

Baseline inspected for this preparation: `19599451fa0e9949f24ffdf5fa9a2e6f49c5632e`.
The handoff preparation adds actual capability/argument preparation code and tests;
read the current parent commit before starting and do not overwrite later work.

## Your first deliverable

Produce an **arm64 Android debug APK with actual source-built FFmpeg libraries**,
prove software H.264/AAC encoding and checked MediaCodec H.264 encoding on a
physical phone, then port the 10 MB native upload workflow. A UI-only APK, a
Kotlin-only AAR, a desktop FFmpeg output or green API-compilation CI is not that
deliverable. No binary is pre-bundled in this repository.

Use the existing `app`, `core`, `engine-ffmpeg` module boundaries. Preserve source
and licensing pins unless a separately documented native-build fix requires a
reviewed change. The native build now explicitly enables
`--enable-lib-android-media-codec`; upstream defaults this feature off.

Keep the default UI to limit selection, Select media and direct-media URL input.
After inspection, show one primary conversion action and optional Edit / More
settings. Port the bracketed timeline; do not restore independent start/end
sliders. All attempts read the original. Never publish an oversized result.

Report exactly what you changed, commands run, real artifact identities, device
identity, effective encoder component, remaining failures and unrun gates.
Do not call the native port complete until the acceptance gates in the plan pass.

## Native UX and background-work update

Read `docs/native-ux.md` before changing execution ownership. The current code
adds a process-wide RunCoordinator, a serialized/cached native bridge, thread
budgets, throttled progress and a source-first Compose workspace. Do not move
encoding back into ViewModel scope, collect raw progress at the editor root or
release the run slot before native cancellation cleanup completes. Keep Stop
independent of import/save and queue-mutation busy flags. Native upload-fit and
full editor parity are still required; host/Compose checks are not phone timing.

## Advanced settings implementation and handoff

Read the [current implementation status](advanced-settings-implementation.md) first.
The branch now contains the 64-key typed registry, native Settings screen, atomic
preference repository, 14 media bindings, four appearance controls and isolated
tests. This is no longer a documentation-only settings proposal.

The [layout design](superpowers/specs/2026-09-29-advanced-settings-design.md),
[64-option catalog](advanced-settings-catalog.md), and
[implementation plan](superpowers/plans/2026-09-29-advanced-settings.md) describe
the full target. Their original design-only paragraphs are historical; the status
page distinguishes implemented code, verified checks and remaining gates.

Preserve immutable queued media values. Full persistent preset/job provenance is
not migrated yet; the legacy editor adapter captures explicit values rather than
guessing their origin. PowerPolicy is a tested pure reducer, not live enforcement:
Android monitoring/service integration must be completed before enabling power
rows. Auto encoding is currently labeled conservative software, not hardware proof.

Keep tests under `testing/settings/` with test-only source-set references. Coordinate
with the editor framework draft rather than adding a production command endpoint or
another instrumentation runner. Do not promote a no-native or synthetic-policy test
to physical-device export or battery/thermal qualification.
