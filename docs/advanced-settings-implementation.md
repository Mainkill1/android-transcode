# Advanced settings: implemented foundation and remaining gates

Updated 2026-09-29. This is the current status for PR #4, not a claim that all
64 proposed settings are finished. The original [catalog](advanced-settings-catalog.md),
[design](superpowers/specs/2026-09-29-advanced-settings-design.md), and
[plan](superpowers/plans/2026-09-29-advanced-settings.md) remain the target and
historical design handoff; their original E/N/D labels and design-only status
paragraphs are not current runtime status. Read this page first.

## What now exists in code

| Area | Implemented in this draft | Boundary |
| --- | --- | --- |
| Registry | 64 stable IDs; eight categories; typed values, ranges, defaults, help, search and per-choice availability | 18 currently available rows; 46 Planned rows cannot save active values |
| Native menu | Shelf Settings; editor Defaults & overrides; searchable category/detail layout; All/Changed/Planned; native choice sheets; reset field/section/all; explicit Save/Apply/Discard | Android UI qualification remains a separate gate |
| Appearance | System/light/dark/pure-black theme, five accents including gated dynamic color, Settings density and technical labels | Contrast/high-contrast, haptics and reduced-motion adapters still Planned |
| Persistence | Bounded versioned UTF-8 documents, immutable copies, revision compare-and-swap, atomic replacement, serialized IO and Flow publication | Corrupt/future/incompatible data stays untouched and reports an error |
| Media bindings | 14 fields adapt to existing Settings and are validated before applying; CPU-only convenience action sets three explicit values | Existing FFmpeg execution and native preparation remain the owner |
| Overrides | Factory/app/preset/job resolver with origin; explicit equal values and Auto remain distinct from inheritance | Full provenance persistence in Editor/JobSpec is not migrated yet |
| Power logic | Pure inclusive thresholds, charging states, hysteresis, thermal severity, combined blocking reasons, manual stop and saver requests | No Android power monitor or worker enforcement is connected yet; every power row stays Planned |
| Test seams | Shared pure Kotlin assertions, JUnit adapter, Compose tests, instrumentation-only scenario allowlist and Python ADB driver | Synthetic inputs prove policy, not actual battery/thermal/native execution |

### The 18 available rows

Appearance: `ui.theme`, `ui.accent`, `ui.density`, `ui.technical_details`.

Video: `video.codec`, `video.rate_control`, `video.quality`, `video.bitrate_kbps`,
`video.max_height`, `video.frame_rate`, `video.deinterlace`.

Audio: `audio.codec`, `audio.bitrate_kbps`, `audio.channels`.

Engine/container: `engine.encode_backend`, `engine.decode_backend`,
`engine.filter_backend`, `export.container`.

Availability here means an implemented settings/media consumer, **not** a newly
qualified native codec. Decode and filter rows currently accept CPU only; their
alternative choices remain disabled. Fractional frame rates, detected-only
deinterlacing, MP3/copy audio and mono likewise remain planned choices within
otherwise bound rows. Native capability checks can still reject an export.

## Decisions that the next agent must preserve

**No queue migration in this slice.** Existing JobSpec/JobCodec values are not
rewritten. Opening legacy editor settings captures its 14 mapped fields as
explicit values rather than inventing provenance. Resetting inside that settings
session restores inheritance; Apply writes the resolved values back. Reopening
captures them as explicit again. Persisting origins across reopen/presets/queue
is the next model/serialization task, not a capability claimed here.

**Defaults seed a new editor once.** Preferences are read after AppGraph initialization
and applied only to an untouched new ViewModel/editor. Refreshing capabilities or
saving defaults cannot rebase existing drafts, queued jobs or running work. A newly
created editor uses source-sized video, AAC 128 kb/s and source channels from the
new defaults. Existing queued 1080p/160 kb/s/stereo or explicit hardware jobs keep
their concrete settings. Removing sources is not a new editor session.

**Auto currently means conservative software.** It is labeled that way. An
explicit hardware request maps only to the existing H.264/H.265 MediaCodec paths,
requires bitrate mode and a known integer frame rate, and never silently falls
back or coerces CRF. Decoder, filters and encode policies are separate. Automatic
hardware selection and classified native fallback still need executor integration.

**AtomicFile, not a new DataStore dependency.** The existing project already uses
atomic queue files, so this slice uses AtomicFile behind a small SettingsStorage
interface, an app-owned SettingsRepository, serialized access and transactional
revision checks. IO does not run on Main. The alternative in the original plan was
not silently installed or given a floating dependency version. AtomicFile does not
supply locking; this adapter supplies it [1]. A later store migration must preserve
schema/revision and failed-write behavior.

**Stop is independent.** The settings surface exposes the existing RunCoordinator
Stop action even when preference saving is busy. No raw native progress is collected
at the Settings root. Neither the UI nor preference repository starts another
encoder or owns cancellation cleanup. Dialog system-Back is forwarded explicitly
to category/search navigation before the dirty-close prompt; it does not depend on
a child Activity BackHandler receiving the dialog's window event [2].

**Planned is not a fake toggle.** Planned rows are searchable with an explanation,
but the save boundary also rejects them. The pure PowerPolicy reducer does not
make the battery switches operational. No CPU/GPU/NPU proof, partial-file resume,
size-cap guarantee, HDR conversion or loudness measurement is invented by the menu.
Existing denoise, trims, source tracks and metadata controls remain in the editor;
the adapter preserves those unmapped values.

## Verification performed during this update

Local environment: Kotlin CLI 1.9.0, OpenJDK 21.0.11, Python 3.13. A partial
verification mirror was built from the inspected PR files; this was **not** a full
Android checkout/build environment. No SDK, Gradle distribution or ADB was available
locally. Repository-wide legacy tests were not claimed from this mirror.

| Check | Result / meaning |
| --- | --- |
| `bash testing/settings/host/check.sh` | **39 passed**; registry, types, immutable layers, codec, legacy media mapping, storage transactions/corruption and power-policy boundaries |
| `python3 -m unittest discover -s testing/settings/host -p 'test_*.py' -v` | **5 passed**; report validation, stale/failure/empty rejection, safe revision argument, synthetic/native distinction |
| Kotlin red/green evidence | Missing implementation first; additional bad stored codec/container regression failed before the semantic-load fix; fixed suite passes |
| Android compile against real wrapper API | GitHub run `36563320833` passed `native-api-contract` for code commit `8f1102e8b9a40a1164f76745edc6e46e562ef936`; this is API compilation, not bundled native execution |
| Full Android build, unit tests and lint | **Passed** in GitHub run `36563320833` for `8f1102e8b9a40a1164f76745edc6e46e562ef936`; no-native debug and test APKs built |
| First emulator run | Settings UI cases ran without reported failures, but the overall run **failed** at scenario report-directory creation (`SettingsScenarioTest.kt:42`). Instrumentation was incorrectly writing into the test APK's sandbox; the follow-up uses the target app context [3] and verifies the report round trip. The dialog Back bridge is also covered by a new case. Check the current commit's rerun before calling this fixed on-device. |
| Physical phone export, actual power events, screen-off lifecycle, release artifact/test exclusion | **Not run** in this session; still explicit acceptance gates |

The shared suite runs through normal `:core:test` via the JUnit adapter; its CLI
entry fails if no tests match. Compose test sources cover draft/discard, planned
rows, Stop while settings are busy, and the dialog Back-request bridge. No local
Compose execution is claimed. Code was self-reviewed; no independent reviewer or
physical-device certification is claimed.

## Build and ADB entry points

From a full checkout with the existing Android toolchain:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm list instrumentation
python3 testing/settings/host/run_adb.py battery_low --app-revision "$(git rev-parse HEAD)"
```

PowerShell from the repo uses `python testing/settings/host/run_adb.py battery_low
--app-revision (git rev-parse HEAD)` on one line. Native export qualification still
requires the separately built real FFmpeg bundle and the flags/verification commands
in `android-handoff.md`; the command above can build a no-native UI test APK.

Cases: `catalog`, `overrides`, `battery_low`, `charging_exception`, `thermal_wait`,
`storage_roundtrip`. `--serial`, `--timeout`, `--adb`, and `--output` are supported.
The revision argument is an exact lowercase Git ID (or explicit `unrecorded`), not
arbitrary shell text. A unique run ID is matched to a versioned JSON report from
the target app's dedicated **`files/settings-tests/`** directory, so an old report
cannot make a failed run pass. The writer and assertions exist only in the test
APK; no production code reads this directory. The supplied
revision is caller-reported metadata, not independently extracted APK provenance.

These cases use synthetic samples and an in-memory storage test double even on a
phone. They do not impersonate a live power monitor or exercise actual AtomicFile
failure on-device. The normal full instrumentation run additionally executes the
Compose cases. Native/physical power cases must be added explicitly, not mapped to
a synthetic case while claiming they are equivalent.

## Tests remain removable

All new tests and drivers are under `testing/settings/`. `core/build.gradle.kts`
references `core` and `junit` only from its **test** Kotlin source set.
`app/build.gradle.kts` references `android` and shared `core` only from
**androidTest**. No production manifest receiver, intent command handler, asset,
endpoint or extra test dependency was added.

To remove this suite, remove those four source-directory references and the
`testing/settings/` directory. Keep production `core/.../settings` and
`app/.../settings` intact. Actual APK/AAB contents and release dependency graphs
still need the release-isolation gate; source-set separation alone is not artifact
proof. Coordinate with editor/test draft #2 without changing its branch or adding
a competing app/test-runner module.

## Remaining work, in dependency order

1. Run/resolve current Android build, lint and instrumentation checks; add full-dialog
   rotation/process-restoration, 200% font/RTL/TalkBack, theme contrast and real
   AtomicFile tests. Keep exact commit and discovered-test counts in evidence.
2. Version Editor/JobSpec/JobCodec for persisted origins, an explicit override chip,
   preset override-preservation and source/capability-dependent effective-route rows.
3. Implement AndroidPowerMonitor and connect PowerPolicy to TranscodeService and
   RunCoordinator at each attempt boundary. Define user-resume clearing of latches,
   deduplicated warnings, monotonic timers, combined reasons, cancellation cleanup,
   legal service/wake-lock lifetime and process-death behavior before enabling power
   rows. Add a test for oversized output waiting before any retry.
4. Connect native upload-size defaults and automatic encoder/fallback policy using
   the existing planner/prepare/byte-verification contracts. Qualify actual phone
   component, output correctness, duration and size; then enable those rows.
5. Complete the other deferred media, queue, storage/privacy and accessibility
   consumers independently, with per-row behavior evidence and release isolation.

[1] Android AtomicFile: https://developer.android.com/reference/android/util/AtomicFile

[2] AndroidX Dialog source: https://android.googlesource.com/platform/frameworks/support/+/d463c995fe1c3128619cc6e8039fddef46ad890a/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/window/AndroidDialog.android.kt

[3] Android Instrumentation contexts: https://developer.android.com/reference/android/app/Instrumentation#getTargetContext()
