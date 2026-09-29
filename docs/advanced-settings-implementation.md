# Advanced settings: implemented foundation and remaining gates

Updated 2026-09-29. PR #4 now contains native implementation, not just a menu
proposal. This page is the current status; the original [64-option catalog](advanced-settings-catalog.md),
[design](superpowers/specs/2026-09-29-advanced-settings-design.md), and
[plan](superpowers/plans/2026-09-29-advanced-settings.md) describe the full target.
Their initial design-only paragraphs and E/N/D labels are historical, not current
runtime status. Start here, then use the [direct-ADB test guide](../testing/settings/README.md).

## Implemented scope

| Area | Actual implementation | Remaining boundary |
| --- | --- | --- |
| Registry | 64 stable typed IDs; eight categories; defaults, ranges, help, search and per-choice availability | 18 bound rows; 46 Planned rows cannot save active values |
| Native menu | Shelf Settings; editor Defaults & overrides; adaptive category/detail view; All/Changed/Planned filters; Save/Apply/Discard; field/section/all reset; dirty-close protection | Full-dialog rotation, accessibility, font-scale and device visual qualification remain separate gates |
| Choice sheets | One scroll owner for help, choices and reset; search for lists longer than eight choices; displayed inherited/factory value; keyboard dismissed on selection; full-row boolean target | No fixed footer outside the scrollable area; choice filtering never changes the draft |
| Appearance | System/light/dark/pure-black themes; mint/dynamic/blue/violet/amber accents; Settings spacing and technical labels | High contrast, haptics and reduced-motion adapters remain Planned |
| Persistence | Bounded versioned UTF-8 document; immutable values; revision conflict checks; AtomicFile replacement; serialized off-main IO; publication after successful write | Corrupt, future or incompatible data is not silently overwritten |
| Media bindings | 14 fields map to the existing Settings model; validation before Apply; CPU-pipeline action previews three overrides | Existing FFmpeg preparation/execution still owns exports |
| Overrides | Factory/app/preset/job resolver; explicit Auto/equal values remain distinct from inheritance | Full Editor/JobSpec/JobCodec provenance persistence is not migrated |
| Power logic | Pure threshold, charging, hysteresis, thermal, combined-reason and manual-stop decisions; last-known charging state survives unknown telemetry for unplug detection | No AndroidPowerMonitor or service enforcement yet; all power controls remain Planned |
| Tests | Kotlin assertions, parameterized JUnit, Compose regressions, real Android storage checks, direct ADB scenarios with per-run JSON | No production test receiver, Python ADB driver or claim of physical power/native export qualification |

### The 18 bound rows

Appearance: `ui.theme`, `ui.accent`, `ui.density`, `ui.technical_details`.

Video: `video.codec`, `video.rate_control`, `video.quality`, `video.bitrate_kbps`,
`video.max_height`, `video.frame_rate`, `video.deinterlace`.

Audio: `audio.codec`, `audio.bitrate_kbps`, `audio.channels`.

Engine/container: `engine.encode_backend`, `engine.decode_backend`,
`engine.filter_backend`, `export.container`.

Bound means the setting has a consumer; it does **not** mean a native codec has
been qualified on every phone. Decode/filter currently accept CPU only. Fractional
frame rates, detected-only deinterlacing, MP3/copy audio and mono remain disabled
choices within otherwise available rows. Native preparation may reject an export.

## Decisions the next agent must preserve

**Queued jobs are unchanged.** No JobSpec/JobCodec migration occurs in this slice.
The legacy editor has concrete settings but no saved origins. Opening its settings
captures all 14 mapped fields explicitly rather than guessing inheritance. Reset
inherits within that menu session; Apply writes resolved values back. Reopening
captures concrete values again. Persistent origins and the final override-count
chip require the next model migration, not a misleading UI-only approximation.

**Defaults seed an untouched new editor once.** Saving defaults or refreshing
capabilities cannot rebase an existing draft or queued/running job. A fresh editor
uses the new source-resolution/AAC-128/source-channel defaults; old queued
1080p/160-kb/s/stereo and explicit-hardware values retain their concrete settings.
Removing media is not automatically a new editor session.

**Auto is currently conservative software, and labeled that way.** Hardware
required maps to the existing H.264/H.265 path and needs bitrate mode and an explicit
integer frame rate. It does not silently coerce CRF or fall back. Decode, filter and
encode policies remain independent. Integrate the automatic hardware/fallback
executor with its owning acceleration work before changing this behavior.

**AtomicFile avoids a new dependency.** The repository already uses atomic queue
files. SettingsStorage/SettingsStore and the process-owned Android repository add
serialization, revision checks and IO dispatch around AtomicFile. AtomicFile alone
does not provide locking [1]. This is a deliberate deviation from the original
DataStore proposal, not an unpinned extra library. Preserve schema, conflict and
failed-write behavior in any later storage migration.

**Stop stays independent of preference saving.** The settings screen exposes the
existing RunCoordinator stop action without collecting raw native progress or
owning a second encoder. Dialog window Back is routed into sheet/search/category
navigation before the unsaved-changes prompt. The settings screen does not own
native cleanup or release the active run ticket.

**Unknown is not charging and does not erase history.** The power reducer retains
the last known charging state only to detect a later transition. Eligibility still
uses the current sample, so charging-only work does not become eligible on Unknown.
The sequence Charging → Unknown → Discharging must still trigger an explicit unplug
policy. Unknown samples reset continuous recovery timers; critical thermal latches
cannot disappear merely because telemetry becomes unavailable.

**Planned is not an active switch.** All power settings remain Planned until the
worker can enforce them. A pure policy test is not a battery monitor or safe
checkpoint-resume implementation. Deferred HDR, normalization, automatic hardware,
size-fit, preview, privacy and queue features must not appear to save successfully
without a consumer. Existing denoise, trims, source tracks and metadata behavior
remain available in the editor and are preserved by the settings adapter.

## Verification record

Verification must be bound to the exact code revision. The PR description contains
the latest observed workflow result; do not inherit a previous revision's green
status. Repository CI checks the PR merge revision, and its head/base identities
are recorded in each run.

| Revision / test | Observed evidence |
| --- | --- |
| `8f1102e8b9a40a1164f76745edc6e46e562ef936` | First implementation: build/unit/lint and wrapper API compile passed in run `36563320833`; emulator failed report-directory placement |
| `fa750e1e02f5a4dca3c0f41413f2f3f55f83cd4e` | Report-directory/Back follow-up: workflow `36564368660` completed successfully, including emulator |
| `dc73e431a2b2a34a17594bddc8057c8a797d56ac` | Test-first commit: workflow `36566079104` compiled, but the emulator reproduced both new failures—missing long-choice search and Reset lacking a scrollable parent |
| `ceab4fc1657b14133ec6c82ae67edb1f7f6539ee` | Implements both sheet fixes, charging-transition fix, actual Android storage checks and direct-ADB reports; verify this implementation and its descendants against current CI |
| Power transition regression | Exact original PowerPolicy blob reproduced the lost-unplug failure locally; patched reducer passed all four targeted regressions |
| Pure settings suite | 39 existing settings assertions plus four new power regressions; both run through Kotlin CLI and parameterized JUnit |
| Actual Android storage | Four added checks: reopen persisted values, malformed UTF-8 rejection, oversized read/write rejection, and prior-value preservation after an aborted AtomicFile write |
| Physical-device power/native media, full accessibility/rotation, release isolation | Not qualified by this work; explicitly separate acceptance gates |

The local session used an isolated partial Kotlin mirror without Android SDK or
ADB; Android compile/lint/instrumentation evidence comes from the repository's
existing jobs. No local emulator or physical-phone run is claimed. The no-native
CI mode skips the opt-in native smoke test; wrapper API compilation is not bundled
FFmpeg execution. Code was self-reviewed; no independent review is claimed.

## Direct ADB: no Python driver

The Python driver and its driver-only tests have been removed. Use the existing
instrumentation APK directly; there is no extra test app/module or production
command receiver. Full build, installation, scenario names and result-validation
instructions are in [testing/settings/README.md](../testing/settings/README.md).

From PowerShell after installing matching debug app/test APKs:

```powershell
$runId = [guid]::NewGuid().ToString()
$revision = (git rev-parse HEAD).Trim()
adb shell am instrument -w -r -e class dev.forma.app.settings.SettingsScenarioTest -e formaSettingsCase all -e formaSettingsRunId $runId -e formaAppRevision $revision dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as dev.forma.transcode cat "files/settings-tests/$runId.json"
```

Confirm the installed component with `adb shell pm list instrumentation`. Add
`-s SERIAL` to each ADB command when multiple devices are connected. This branch
uses `dev.forma.transcode`, not a differently suffixed application ID from another
draft. Require successful instrumentation **and** a matching fresh runId/case with
`result=PASS` and positive matching selected/passed counts. ADB transport exit
status by itself is not a test result [2].

Each request writes `files/settings-tests/<UUID>.json`; reused IDs are rejected.
Reports are atomically written and read back before emitting a passing status.
Unknown scenario names fail; no arbitrary input paths/commands are accepted.
`callerReportedAppRevision` is clearly caller metadata, not installed APK identity
proof. Retain the matching app/test APK hashes when collecting evidence.

`all` executes 43 pure checks and four real Android storage checks. Power samples
remain synthetic and `nativeExecution` remains false. `storage_roundtrip` now uses
the production Android storage adapter in a disposable test directory;
`storage_memory` retains the original in-memory contract cases. An aborted atomic
write test is not a power-loss or process-death certification.

## Tests remain removable

All new test code, fixtures and instructions are under `testing/settings/`.
`core/build.gradle.kts` references `core/` and `junit/` from its test source set;
`app/build.gradle.kts` references `android/` and shared `core/` only from androidTest.
Remove those four references and this test tree without deleting production
settings sources. Source-set separation is not a substitute for inspecting a real
release APK/AAB and dependency graph. Coordinate with the editor/test-runner draft
without changing its branch or creating another runner.

## Remaining implementation gates

1. Verify the current revision's Android build/lint/instrumentation; extend full-dialog
   rotation/process-restoration, 200% font/RTL/TalkBack and per-theme contrast coverage.
   The small-window sheet regressions and real AtomicFile adapter cases now exist.
2. Version Editor/JobSpec/JobCodec for persistent origins, an accurate override-count
   chip, preset preservation and source/capability-dependent effective-route details.
3. Connect AndroidPowerMonitor to PowerPolicy and TranscodeService/RunCoordinator:
   explicit resume, deduplicated warnings, monotonic recovery timers, combined reasons,
   safe cancel/wait/restart, service/wake-lock lifetime and process death. Test a size
   overshoot waiting before another attempt. Only then enable power controls.
4. Integrate native upload-size defaults and the automatic hardware/fallback executor
   with the existing planner/prepare/byte-verification contract and acceleration work.
   Qualify actual phone components, full duration, output decoding and strict bytes.
5. Implement remaining deferred media, appearance, queue, storage/privacy and preview
   consumers independently, with per-row behavior tests and release artifact isolation.

[1] https://developer.android.com/reference/android/util/AtomicFile

[2] https://developer.android.com/studio/test/command-line
