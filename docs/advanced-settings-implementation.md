# Advanced settings implementation handoff

Updated 2026-09-29. This is the implementation status for draft PR #4, not a declaration that every catalog entry or native export path is finished.

Read with the [64-option catalog](advanced-settings-catalog.md), [native interaction design](superpowers/specs/2026-09-29-advanced-settings-design.md), and [implementation plan](superpowers/plans/2026-09-29-advanced-settings.md).

## Current snapshot

- The registry contains exactly **64 stable setting IDs** across eight categories.
- **26 settings have production consumers**: 18 appearance/media controls and eight live battery/thermal controls.
- The other **38 settings remain Planned** and cannot be saved as if active.
- App defaults are atomic/versioned. Media values are copied into immutable queued `Settings`; later default changes cannot rewrite queued work.
- Safety policy is global/live and separately evaluated at service start, before every queue claim, and when Android power or thermal state changes.
- A no-native build remains honest: settings can compile and render, but conversion stays unavailable without the real FFmpeg payload.

“Implemented” means a typed value reaches a concrete native-app consumer. It does not mean every codec, device component, thermal condition or physical phone has been qualified.

## Native Settings UI

The shelf opens a native Compose Settings surface. Phone layouts use category then detail navigation; sufficiently wide windows use list-detail. Search, All/Changed/Planned filters, per-field reset, section/all reset preview, dirty-only Save/Discard, theme preview rollback, job-scope provenance, and the CPU-pipeline convenience action are wired.

The same typed values feed contextual job settings. Running conversion Stop remains separate from preference save/load state. Planned controls remain visible with an explicit non-working label and disabled choices instead of accepting inert values.

## Typed storage and inheritance

The Android-free contract lives under `core/.../settings/`:

```text
factory → saved app defaults → selected preset → explicit job overrides
```

`PreferenceValues` distinguishes inheritance from explicit Auto/false/zero/equal values. `SettingsStore` serializes access; `AtomicSettingsStorage` bounds and validates UTF-8 input and replaces the file atomically. Stale writers, corrupt documents, unknown IDs, unsupported versions and unwired selections fail explicitly.

A settings read error is retained for review. The live power runtime now **fails closed**: it blocks new queue claims and drains an already-active run rather than silently substituting factory power safeguards.

Legacy queue JSON remains readable. Existing H.264/H.265 hardware enum values remain explicit hardware requests; no migration turns them into Auto or silently enables software fallback.

## Currently bound appearance/media rows

The original 18 bindings remain:

- Appearance: `ui.theme`, `ui.accent`, `ui.contrast`, `ui.density`.
- Video: `video.codec`, `video.rate_control`, `video.quality`, `video.bitrate_kbps`, `video.max_height`, `video.frame_rate`, `video.deinterlace`.
- Audio: `audio.codec`, `audio.bitrate_kbps`, `audio.channels`.
- Engine/export: `engine.encode_backend`, `engine.decode_backend`, `engine.filter_backend`, `export.container`.

CPU-only and explicit H.264/H.265 MediaCodec requests map to the existing route preparation. Decode/filter hardware choices remain Planned. Hardware required fails instead of silently coercing CRF, source frame-rate or unsupported codec requests. Auto stays conservatively software until the bounded fallback executor is integrated and device-qualified.

## Live battery and thermal policy

These eight rows now save and affect the foreground worker:

- `power.low_action`
- `power.low_percent`
- `power.only_when_not_charging`
- `power.charging_only`
- `power.resume_margin`
- `power.unplug_action`
- `power.thermal_action`
- `power.thermal_threshold`

`AndroidPowerMonitor` is process-owned in `AppGraph`; Compose does not own or poll it. Battery percentage rejects invalid level/scale data, and charging, full-while-plugged, plugged-but-not-charging, discharging and unknown remain distinct. Framework broadcast callbacks are treated as invalidation signals and protected sticky/system state is reread. Thermal severity is reported only when Android exposes it.

`PowerRuntime` combines settings, telemetry and `RunCoordinator` state. It uses monotonic time, a 10-second battery/unplug recovery debounce, a 30-second thermal recovery debounce, and synchronous refresh after settings load and before every queue claim. A just-loaded charging-only rule therefore cannot trail the first job by one Flow dispatch.

Worker semantics are explicit:

| Selection | Active attempt | Next queued attempt |
| --- | --- | --- |
| Warn | Continues with an ongoing notification warning | Allowed unless another rule blocks |
| Finish current | Current attempt may finish and verify | Not claimed; service drains and stops |
| Stop current | Cooperative cancellation; native cleanup still owns the run slot | Job is marked Interrupted, then returned to the waiting queue after cleanup |
| Ignore optional low-battery guard | Low-battery guard does not act | Charging-only and thermal rules still apply |

No action claims checkpoint resume. A power-stopped job later stages the original again. Explicit user Stop can replace a pending recoverable power-policy reason while cleanup still owns the run slot; later service/time-limit signals cannot overwrite user intent. Critical thermal severity always requests cancellation regardless of the optional threshold action.

Automatic background continuation and Battery Saver thread-budget integration remain Planned. Conditions recovering may clear the policy state, but this branch does not resurrect a stopped service, restart work on boot, or hold a foreground service/wake lock indefinitely while waiting. The user explicitly starts the waiting queue again.

## Isolated verification surfaces

Production code contains no ADB receiver, arbitrary command dispatcher, embedded fixture media or HTTP test endpoint. `testing/settings/` is added only to test source sets.

- Host/JVM: 48 pure assertions.
- Direct ADB `all`: 52 assertions, including four Android AtomicFile checks.
- Android instrumentation: Settings navigation/editing/accessibility behavior, monitor intent mapping, runtime fail-closed/startup behavior, service transition rules and storage replacement.
- Existing worker tests cover run exclusivity, drain, cancellation cleanup, stale stop protection, 100-way start races, progress throttling and user-stop precedence.

The direct ADB report remains versioned JSON and says `nativeExecution: false` and `powerInputs: synthetic`. It is policy/storage evidence, not physical-device qualification.

## Verification evidence

Fresh implementation verification before this handoff:

- Revision `7fcda00d7a05177e586819ed273601100e8f9598`, workflow run `36571847155`: wrapper API compilation, no-native core/app unit tests, APK and Android-test APK assembly, lint, and emulator instrumentation all passed.
- Test-only revision `cf87fc2c701865a70d59598ecf7a2e99b25befff`, workflow run `36572511109`: wrapper/API and no-native unit/lint jobs passed; emulator instrumentation failed only at the newly added unreadable-policy assertion, proving the prior fail-open behavior before the fix in this handoff commit.
- Earlier red/green slices separately proved missing monitor/worker contracts, stop-reason precedence, synchronous loaded-policy refresh and Android system-broadcast registration before their implementations were added.

The PR description records verification for the final documentation/harness head. CI does **not** prove a source-built FFmpeg payload, arm64 execution, real MediaCodec output, physical battery drain, actual thermal throttling or OEM background behavior.

## Remaining implementation gates

1. Build and package the pinned source-built FFmpeg AAR, then run real software and MediaCodec exports on an arm64 phone.
2. Exercise unplug/replug, plugged-but-not-charging, low-battery boundaries, thermal recovery, screen-off, process death and Android foreground-service timeout behavior on physical devices.
3. Integrate native upload-size fitting and bounded retry accounting; every retry must read the original and rerun preparation.
4. Add persisted preset identity and complete per-field provenance to the queue schema rather than inferring it from effective `Settings`.
5. Implement and qualify deferred decode/GPU/HDR/loudness/component-selection paths before changing their Planned status.
6. Add device-aware availability summaries for thermal telemetry and exact codec components; a saved policy is not proof a device can report or execute it.
7. Complete release-artifact scans, large-font/RTL/TalkBack/contrast sweeps and 100–200-row performance evidence.

## Handoff entry points

- Registry and validation: `core/src/main/kotlin/dev/forma/core/settings/SettingCatalog.kt`
- Layered values/storage: `Preferences.kt`, `SettingsCodec.kt`, `SettingsStore.kt`
- Media mapping: `NativePreferences.kt`
- Pure power reducer: `PowerPolicy.kt`, `PowerSettings.kt`
- Android settings storage: `app/.../settings/SettingsRepository.kt`
- Android telemetry/runtime: `AndroidPowerMonitor.kt`, `PowerRuntime.kt`
- Worker enforcement: `service/TranscodeService.kt`, `service/PowerServiceRules.kt`, `work/RunCoordinator.kt`
- Native UI: `ui/settings/SettingsPanel.kt`, `SettingsDialog.kt`
- External tests/direct ADB: `testing/settings/`

Keep PR #4 Draft until the remaining native and physical-device gates are attached. Passing the current no-native/emulator matrix is necessary but not sufficient for release or merge.
