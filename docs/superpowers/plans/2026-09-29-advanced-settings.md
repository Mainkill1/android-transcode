# Advanced Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Checkboxes below are future work, not completed implementation.

**Goal:** Build the native advanced-settings menu and all honest, capability-gated preference behavior described by the 64-option catalog.

**Architecture:** Android-free typed settings resolution and power policy in `core`; Android preference storage, battery/thermal monitoring and native Compose presentation in `app`. Reuse the existing FFmpeg preparation/execution and process-wide RunCoordinator. Freeze media settings into queue entries, but evaluate separately versioned live device-safety policy.

**Tech stack:** Existing Kotlin, Compose Material 3, coroutines/Flow, in-process FFmpeg, Android battery/PowerManager APIs, and a reviewed/pinned AndroidX Preferences DataStore adapter. No HTML replacement, new remote service, root dependency or mandatory GPU/NPU runtime.

**Spec:** [Native design](../specs/2026-09-29-advanced-settings-design.md) and [64-option catalog](../../advanced-settings-catalog.md). Inspected base: `89ceb3b362872f193a54feacc99225c80e77f176`.

## Global constraints

- Preserve API 26 minimum, SDK 36 compile/target, JDK 17, current FFmpeg source/licensing pins and the `app`/`core`/`engine-ffmpeg` boundaries.
- Preserve source-first Home, visible direct-media URL input, 10,000,000-byte goal, shelf, bracket trimming and immutable queued snapshots. Do not reset hidden settings.
- All application attempts call `FfmpegBridge.prepare` after staging and again after changed retry settings; missing native payload is unavailable, not success.
- Keep actual actionable bounds at least 52 dp; preserve system text scaling and non-color status. No false CPU/GPU/NPU badges.
- Do not promise checkpoint resume. Cooperative cancellation holds the run slot until native cleanup, and retries read the staged original.
- Catalog Foundation E means related code exists, not that the proposed preference is integrated. Deferred choices stay explicitly Planned and cannot be saved as active.
- Keep test code/fixtures in `testing/settings/`, referenced only by test source sets. Add no production ADB command receiver, test fixture bundle, scheduled automation or new GitHub workflow.
- Do not remove existing controls merely because this new catalog is not exhaustive: retain denoise, source trim, cancellation and existing editor operations in contextual More settings.

## Review focus

1. An old saved H.264 hardware job must remain an explicit hardware request, not become Auto or a silent software retry. Task 2 owns the migration fixture.
2. A default equal to an explicit job override still has different future inheritance semantics. Task 1 owns equality and reset tests.
3. An oversized attempt that reaches a power stop boundary must not consume unlimited retries or publish invalid output. Tasks 4–5 own joint assertions.
4. A reopened Settings Activity must not create a second native worker, reset unsaved values or release a still-cancelling run ticket. Tasks 3 and 5 own lifecycle tests.
5. Revoked URI grants, imported component IDs from another phone and shortened retention periods must not reroute jobs or delete active/recoverable data. Tasks 2, 4 and 6 own these tests.

## Parallel-work boundaries

The repository has a separate [editor framework / isolated ADB testing draft (#2)](https://github.com/Mainkill1/android-transcode/pull/2). Inspect its current state before implementation. Reuse its runner/source-set conventions if adopted; do not create a second competing instrumentation app or move its tests blindly. This design branch intentionally changes documents only and does not modify that branch.

Tasks 1–2 establish the shared contract. Task 3 can then proceed independently of Task 5. Task 4 owns media/encoder integration and must coordinate with audio and acceleration work. Task 6 owns queue/storage/privacy adapters. Task 7 reconciles evidence and release isolation. Each task's implementer owns only its named paths unless a reviewed interface change is required.

## File map

All `settings/` paths below are **proposed**, not files this design PR claims to implement. Existing integration paths were checked on the base.

| Owner | Paths | Responsibility |
| --- | --- | --- |
| Core contract | `core/src/main/kotlin/dev/forma/core/settings/{PreferenceModels,SettingCatalog,SettingsResolver}.kt` | Typed values, scopes, 64 IDs, validation, provenance and availability |
| Core power | `core/src/main/kotlin/dev/forma/core/settings/PowerPolicy.kt` | Pure event/state/timer decisions, no Android imports |
| Storage | `app/src/main/kotlin/dev/forma/app/settings/{SettingsRepository,DataStoreSettingsRepository}.kt` | Transactional versioned app defaults; one process-wide store instance |
| Power adapter | `app/src/main/kotlin/dev/forma/app/settings/AndroidPowerMonitor.kt` | Battery/thermal lifecycle events mapped to pure samples |
| UI | `app/src/main/kotlin/dev/forma/app/ui/settings/{SettingsScreen,SettingRow,SettingChoiceSheet,SettingsViewModel}.kt` | Adaptive navigation, search, editing and provenance |
| Integration | `app/src/main/kotlin/dev/forma/app/{FormaApplication,TranscodeViewModel}.kt`; `ui/{FormaScreen,AdvancedControls,FormaTheme}.kt` | AppGraph wiring, entry points and shared contextual controls |
| Queue/execution | `app/src/main/kotlin/dev/forma/app/data/{JobCodec,QueueRepository,FfmpegTranscoder}.kt`; `service/TranscodeService.kt`; `core/src/main/kotlin/dev/forma/core/{Models,Planner,Acceleration,WorkPolicy}.kt` | Deliberate migration, snapshot construction, actual route and bounded work |
| Tests | `testing/settings/{core,app,android,fixtures,host}/` | Unit, instrumentation, fixture and artifact checks, excluded from production |
| Build wiring | `app/build.gradle.kts`, `core/build.gradle.kts`, dependency catalog as needed | Explicit removable test references; reviewed dependency pin only |

## Task 1 — Typed catalog and layered resolution

**Interfaces:** Define section data classes with concrete enum/numeric types; `SettingId` contains exactly the catalog's 64 keys. Define `OverrideValue<T>` as Inherit or Explicit(value), `ValueOrigin` as Factory/App/Preset/Job, and availability as Working/Planned/Unavailable/Blocked(reason). `ResolvedSetting<T>` retains requested, origin, effective and reason. Define `resolveSettings(defaults: AppDefaults, preset: PresetDefaults?, overrides: JobOverrides, context: ResolutionContext): ResolutionResult`; context contains optional source facts and capability facts, never Android objects. Result carries resolved values, warnings and blocking errors. No raw user-supplied FFmpeg strings.

- [ ] Add `testing/settings/core/dev/forma/core/settings/SettingsResolverTest.kt` and `SettingCatalogTest.kt`. Assert 64 unique IDs, category counts 8/10/8/8/10/8/6/6, valid defaults, category acceptance IDs and complete availability reasons.
- [ ] Run `./gradlew :core:test --tests '*SettingsResolverTest' --tests '*SettingCatalogTest'` after wiring this external test source set. Confirm failure because the new contract is missing, not because test discovery found zero tests.
- [ ] Implement the contract/resolver. Pin cases: App=Software and explicit Job=Auto resolves Auto/Job; resetting Job returns Software/App; explicit false differs from Inherit; equal-value overrides remain explicit; preset application cannot mutate P/G-only settings. Source absent resolves Auto as undecided, not device-qualified.
- [ ] Add range/conflict tests: negative target bytes, numeric overflow, 20%+5% recovery, codec/profile mismatch, copy-audio plus normalization, hard hardware plus software CRF, invalid language rule. Every catalog row gets valid/invalid/boundary/reset coverage or a verified Planned state.
- [ ] Rerun tests, record discovered test count and commit only the contract/test paths.

## Task 2 — Persistence, migration and immutable snapshots

**Interfaces:** `SettingsRepository` exposes `val defaults: Flow<VersionedDefaults>` and `suspend fun save(expectedRevision: Long, changes: DefaultsPatch): SaveResult`. SaveResult is Saved(newRevision), Conflict or Invalid(errors). DefaultsPatch is restricted to registered typed IDs and allowed global/default scopes. Never use global state as the executing job's mutable media configuration.

- [ ] Add external `SettingsRepositoryTest.kt` and migration fixtures under `testing/settings/fixtures/`. Assert atomic multi-category save, no write on Discard, conflict on stale revision, and recovery/reporting for corrupt storage instead of silently erasing user choices.
- [ ] Add legacy JobCodec round-trip tests before changes. The inspected base uses `video` including hardware enum values, `maxHeight=1080`, `audioKbps=160`, `stereo=true`, `fps=0`, `keepMetadata=false`. Preserve those concrete legacy values even though proposed new-default values differ. In particular H264_HW/H265_HW become explicit required-hardware policies.
- [ ] Implement the repository with a single app-owned Preferences DataStore instance, using typed adapters and schema/revision keys. Update all related keys in one transaction; the preference boundary rejects invalid values. Pin a reviewed compatible dependency rather than using a floating version. DataStore supports asynchronous consistent transactions; preferences themselves still need typed validation [1].
- [ ] Extend versioned queue serialization deliberately for values/provenance/source-resolved track/component identity. A malformed or unsupported future queue version cannot silently become factory defaults. Imported settings are size-bounded, validated, previewed and allowlisted; device-local component IDs and SAF grants require local reselection. Add per-field migration tests.
- [ ] Run `:core:test :app:testDebugUnitTest`, prove a saved-default change cannot alter already queued job JSON, and commit. Do not make current drafts silently adopt newly saved defaults.

## Task 3 — Native Settings screen and shared contextual controls

**Interfaces:** `SettingsScreen(state: SettingsScreenState, onAction: (SettingsAction) -> Unit)` is the single presentation entry. State includes scope (AppDefaults/Job), draft revision, category/query/filter, dirty/error sets and resolved rows. Actions include SetValue, ResetField, ResetSection, ResetAll, SelectCategory, SetQuery, Save and Discard. Only the ViewModel/repository save boundary persists. `AdvancedControls` uses the same field definitions and does not maintain a competing default store.

- [ ] Add `testing/settings/android/dev/forma/app/settings/SettingsScreenTest.kt` and `SettingsAccessibilityTest.kt`. Assert Home remains minimal, shelf Settings works without media, More settings opens job scope, explicit override chip survives collapse, and a Planned value cannot claim to save.
- [ ] Run discovered instrumentation tests on an available emulator; record the expected red state before screen integration. Lack of SDK/device is Unrun, not Pass.
- [ ] Implement category list/detail, query/synonyms, All/Changed/Planned filters, readable choice sheets, provenance text and dirty-only save bar. Wire theme preview with Discard restore. Keep original denoise and trim controls available. Never nest an unbounded settings Column inside another unbounded scroll.
- [ ] Test portrait/landscape/tablet/RTL, 200% font scale, keyboard arrows/Enter/Back, touch bounds ≥52 dp, TalkBack state semantics, unsaved navigation, scrolling focus and contrast for every theme/accent combination. A live progress update must not recompose the whole Settings list or block Stop.
- [ ] Run host tests plus `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`; commit with screenshots tagged by device configuration. No screenshot counts as export proof.

## Task 4 — Media, encoder and upload-default integration

**Interfaces:** `resolveJobSettings` consumes the Task 1 result and inspected source and produces the existing versioned JobSpec extension. `PreparedRouteSummary` exposes requested decode/filter/encode policies, selected components, reasons, capability identity and per-attempt actual result. Adapt to existing AccelerationPolicy/prepare contracts rather than building a parallel encoder router.

- [ ] Add table-driven T-VIDEO/T-AUDIO/T-ENGINE/T-EXPORT tests. Assert software-only encode never chooses MediaCodec; whole-pipeline CPU action makes three explicit overrides; manual component/Hardware required never silently falls back; hardware codecs never receive CPU CRF/preset flags.
- [ ] Confirm tests fail for missing integration. Cover every catalog row 9–33 and 45–52 either with functioning behavior or explicit blocked/Planned-state assertions. MP3, stream copy, rational frame rates, HDR/bit depth and loudness are not all existing base capabilities; do not label the whole menu ready because AAC works.
- [ ] Connect existing eligible controls first. Coordinate software-effort maps, codec/profile/color validation and loudness processing with the relevant implementation owners. Unsupported mappings remain Planned. Separate CPU encode from GPU filters; do not enable an unqualified decoder route through Auto.
- [ ] Integrate native size-fit only when that planner exists and passes its existing contract. Verify 0 < bytes < 10,000,000, full chosen duration, source reread, exact prepared settings after every retry and at most four total video/audio attempts including permitted codec-init fallbacks. An oversized active attempt followed by power drain waits before any retry. No source/duration/audio omission may disguise size success.
- [ ] Run core/planner/native-readiness checks. For each newly supported route require actual phone export, output probe/decode, byte cap and component evidence before changing availability to Working. Commit; keep unqualified routes explicitly deferred.

## Task 5 — Battery/thermal monitoring and worker integration

**Interfaces:** `PowerMonitor` exposes `Flow<PowerSample>` with level, eligible-charging status, thermal status and Battery Saver, including Unknown values. Pure `evaluatePower(policy: PowerPolicy, sample: PowerSample, previous: PowerState, nowMs: Long): PowerDecision` returns updated state, block reasons, Continue/Warn/Drain/Cancel action and earliest recovery eligibility. State includes active-attempt and user-cancel status. Time is monotonic and injectable. AppGraph owns the monitor; Compose does not control it.

- [ ] Add `PowerPolicyTest.kt` under external core tests with exact cases: 20% discharging triggers; 21% does not; 10% actively charging is exempt when configured; 10% plugged-not-charging triggers; 100% unplugged does not satisfy charging-only; missing/zero scale is Unknown; 24% does not clear a 20+5 episode; 25% needs 10 stable seconds; Severe invokes selected thermal action; Critical cannot be bypassed; thermal recovery needs 30 stable seconds; manual Cancel prevents continuation.
- [ ] Run the pure tests to red before implementing. Add combined-reason tests so charging recovery cannot clear a thermal/user wait, and unplug action cannot bypass charging-only or critical protection. Define Battery Saver On as optional-preview reduction and next-attempt CPU request capped at two within the existing budget; preserve requested versus effective thread values and all content settings.
- [ ] Implement runtime battery events, valid level/scale handling, and API-gated PowerManager listener registration/removal. Preserve the no-Android core boundary. No root-only temperatures, platform-throttling changes or permanent polling worker.
- [ ] Integrate into TranscodeService/RunCoordinator: no start before gate; Drain completes only current attempt; Cancel waits for native completion before cleanup/new work; waiting releases wake locks/service after cleanup; legal auto-continuation or tap-to-resume only. Test duplicate events, rotation, user Stop races, OS timeout, process death, low battery during staging/verification/publication and unavailable thermal telemetry. Publication and Stop must not produce contradictory completion state.
- [ ] Run host/instrumented policy tests, then real unplug/replug/screen-off tests. Simulated battery/thermal inputs are policy evidence only, never thermal or battery-drain qualification. Commit results with exact platform/app/native identities.

## Task 6 — Queue, notifications, storage and diagnostics

**Interfaces:** Add small Android adapters for preview policy, queue-boundary preferences, destination resolution and reference-aware retention. They consume repository snapshots at their documented boundary and never receive unrestricted shell/file-deletion commands. Keep catalog rows 34 and 53–64 owned here; URL-import policy remains Planned until the actual downloader exists.

- [ ] Write T-QUEUE/T-DIAGNOSTICS tests first: Add-to-queue does not start under the default; explicit Convert still works; notification detail is redacted by default; Debug resets on next process; 7-day retention never deletes current/recoverable jobs; log rotation respects the 10 MiB cap; screen-awake affects visible UI only.
- [ ] Add adversarial storage tests: source name `../clip`, revoked SAF grant, existing output with the same name, import with foreign component ID, and a cleanup candidate still read by native code. Assert no source overwrite, path traversal, global settings re-route or delete-before-cleanup.
- [ ] Implement each adapter independently, keeping mandatory foreground notification and OS sound/channel rules intact. Shortening retention previews affected completed data; exporting diagnostics previews exact contents and keeps tokens/paths redacted even when filenames are opted in.
- [ ] Run relevant tests and no-native UI gates. Keep metered-download behavior explicitly Planned until native URL import can enforce it without changing local exports. No fake HTTP progress or blanket cleartext permission.
- [ ] Commit with a catalog row-to-behavior coverage report. A control with no implemented effect must still be labeled Planned/unavailable, not Working.

## Task 7 — Isolated ADB cases and release-evidence gates

**Files:** `testing/settings/android/dev/forma/app/settings/SettingsScenarioTest.kt`, `testing/settings/fixtures/`, `testing/settings/host/check_settings_contract.py`, plus explicitly removable Gradle test-source references. Coordinate the actual layout with draft #2 before adding wiring. These are proposed files; no runner is added by this docs PR.

- [ ] Make the host checker compare all 64 catalog IDs against the Kotlin registry and row coverage; fail on duplicate/missing defaults, invalid ranges, absent status reasons, no tests discovered or catalog drift.
- [ ] Implement an instrumentation-only scenario allowlist: `defaults_roundtrip`, `job_override`, `battery_low`, `charging_exception`, `thermal_wait`, `cancel_race`, `native_software`, `native_hardware_required`. Reject unknown names and arbitrary paths/commands. Use a replaceable app interface for power input, not a production test endpoint or broadcast receiver. Remove/reset fixture state in finally blocks.
- [ ] Require a versioned JSON result with run ID, app commit, device/OS/ABI, settings/policy revision, requested/effective/actual route, assertions, Pass/Fail/Unrun, failure reason and applicable native/input/output hashes. Return a failing instrumentation result for failed assertions. Once a native case is explicitly requested, missing native libraries/device capabilities are failures, not skipped success. Do not fabricate per-frame telemetry.
- [ ] Build and execute cases with the following contract **after the runner is implemented**. First confirm the installed component using `adb shell pm list instrumentation`; the component below follows the current app ID and AndroidJUnitRunner. Example case arguments are a proposed test interface, not commands working in the base today.

```bash
# Existing build tasks; real FFmpeg flags/repository required for native cases.
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm list instrumentation

# FUTURE scenario class/argument, implemented by this task only.
adb shell am instrument -w \
  -e class dev.forma.app.settings.SettingsScenarioTest \
  -e formaSettingsCase battery_low \
  dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner
```

- [ ] Verify release APK/AAB content and dependency graphs exclude test classes, fixtures, test receivers, scenario dispatch and instrumentation dependencies. Removing `testing/settings/` plus its test references must leave release production sources intact. Report each gate below independently; commit only after the corresponding evidence is recorded.

## Acceptance matrix

| Group | Required evidence | Never infer |
| --- | --- | --- |
| T-CATALOG | 64 unique IDs; typed valid defaults; scopes; reset/inheritance; every row covered | Runtime behavior from a registry count |
| T-UI | Native screens, search, default/override labels, planned-state honesty, 52 dp targets, font scale, TalkBack, contrast | Touch usability from desktop HTML |
| T-VIDEO / T-AUDIO | Planner mappings, copied/processed track correctness, rational timing, deferred-feature gating | HDR/loudness/codec support from a dropdown |
| T-ENGINE | Requested/prepared/actual route and failure classification; device component proof | Hardware execution from a vendor flag |
| T-POWER | Deterministic policy tests plus real monitor/lifecycle/unplug/cancel tests | Real battery/thermal performance from fake samples |
| T-EXPORT | Original-source retries, full duration, strict decimal byte cap, no source overwrite | Upload compatibility from rounded display size |
| T-QUEUE | Immutable snapshots, one active worker, cancellation cleanup, restart state | Partial output resume from saved progress |
| T-DIAGNOSTICS | Redaction, bounded retention, reference-aware cleanup, no secret/path leakage | Safe deletion from filename age alone |
| T-RELEASE | Artifact/dependency scan and removable test references | Release debloating from a source folder name |

## Baseline commands and honest reporting

After future implementation, retain existing repository checks:

```bash
./tools/check-core.sh
./tools/check-android-readiness.sh
./tools/check-ux.sh
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
```

For actual bundled-native builds, use the full `ffmpegEnabled` / `ffmpegRepo` / native-payload validation commands in `docs/android-handoff.md`. Keep host policy, Android compile/lint, emulator UI, native packaging and physical-device media/power qualification separate. Include exact commands, discovered test counts, errors, unrun gates and evidence artifact identities. A no-native preview build cannot pass the native readiness milestone.

**Verification of this design PR:** document/spec review only. No Kotlin runtime change, SDK build, instrumentation execution, battery experiment or phone export is claimed by these files. Keep the PR Draft for design review and agent implementation planning; do not mark its planned acceptance checkboxes complete merely because the documents merge.

[1] [Android DataStore documentation](https://developer.android.com/topic/libraries/architecture/datastore), checked 2026-09-29. Its transactional behavior does not make untyped preference keys a replacement for validation.
