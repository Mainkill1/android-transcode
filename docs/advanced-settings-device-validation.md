# Settings and audio integration on the target phone

Current reviewed product head: `a113131`, with **64 catalog rows: 33 implemented and 31 Planned**. Current host/build and release-isolation evidence is recorded below; the matching final phone suite passed 50 tests without failures or assumption skips. The earlier `777f441de12768ef16e7497aa1f3860fe8066ffe` integration checkpoint combined the settings foundation with PR3 audio through regular merges. Its phone/media results remain historical and do not qualify the current head. No merge to main is performed.

## Integration behavior

- App defaults seed an untouched editor once. Audio import retains seeded bitrate, routing and processing instead of replacing the whole settings value with a preset. AAC selects M4A; FLAC selects FLAC; PCM selects WAV. Unsupported audio-only Opus requests remain unchanged and show an explicit format-choice message. Audio-only sources can choose M4A/WAV/FLAC even if their saved defaults used a video container.
- WAV/PCM16/float and FLAC settings round-trip without dropping effects, sample rate, normalization or byte caps. Channel settings use the audio editor's Source/Stereo/Mono/Left/Right/Swap routing. The legacy stereo switch reads and edits that effective route. Unrelated legacy fallback values are retained so undo can restore their behavior.
- Undo history starts from seeded defaults without an artificial undo step. Applying an audio output change through Defaults & overrides records an audio-history edit; previews invalidate through the same graph identity. Presets preserve the current audio graph and Share imports preserve an explicitly selected preset.
- MainActivity combines saved theme with cold/warm Share handling. Home retains compact actions and statistics; detailed settings remain behind Settings or Defaults & overrides.
- All 64 catalog IDs remain: 33 have production consumers and 31 remain Planned. The consumer map includes live power policy and queue/privacy behavior as well as media/UI settings. Sample-rate/normalization defaults in the Settings catalog remain Planned even though the contextual audio editor supports those operations. See the [consumer boundaries](advanced-settings-implementation.md); an implemented row does not certify every physical event or device.
- Queue schema 3 preserves frozen app/preset/job origins and requires complete saved fields with exact primitive types, including required nullable fields and complete known audio-node/EQ parameters. Sparse external recipes have a separate defaulting path; future audio effects remain opaque. Unsupported or malformed queue documents fail without rewriting their original bytes, while genuine schema-1/schema-2 legacy snapshots retain their semantics.
- Before publication, strict full decode and per-stream clocks/counts reject a truncated video hidden by full-length audio, lost offsets or wrong explicit-CFR counts. Clockless WAV origins are observed from native packets. Audio DSP/sample-count verification remains active. Failed durable completion rolls back only the new output; Stop after durable completion retains it. Save copy accepts only an observably empty destination before writing, protecting nonempty originals/aliases and rejecting unreadable destinations.
- The latest choice-sheet navigation, unknown-power-transition regressions and direct ADB protocol are retained. Settings has no Python device driver. The host Kotlin script builds inside `testing/results/`; no host temporary directory outside the workspace is needed.

## Current reviewed checkpoint, 2026-09-29

At `a113131`, **163 JVM tests passed**: core 98, engine 20, app 45. Native-enabled debug app/test assembly, pinned-native API compilation and lint passed. Fourteen desktop media cases cover retained-track truncation, CFR counts, offsets, trims, Matroska and real WAV-to-M4A/FLAC exports. Both debug APKs passed native payload and 16 KB ELF/ZIP alignment checks.

The parent built the unsigned minified release with `testing/` physically absent and both `audioTests=false` / `settingsTests=false`. Native payload/alignment and structural DEX exclusion checks passed: no runner, audio/settings/Share/Save fixtures or test commands remain. The release was statically checked, not installed.

```text
current debug APK  38911576d44992112bdd8b4ac2062960df49f5e5eba8174c30f5177656cab739
current test APK   4a43df76370b23c7934e9ba730162934ed988c105624c37175b949f83db2ca36
unsigned release   6c9ed01ee4cc7b284c4abd1a3cc6d20f9ce346ad0e59c0a30aa26188080f46f5
```

The matching `.lab.settings` pair passed **50 physical-phone tests**, with `formaNative=true`, no failures or assumption skips. This includes real Share/service exports, native audio DSP/analysis/preview/jobs and hardware smoke, settings/provenance/storage/queue controls and the Save original/alias regression.

The separate direct ADB settings `all` report passed **66/66 checks**, UUID `a5914b0a-18a5-47a2-8faf-7f4959f4b9b7`, with caller-reported revision `a113131`. Its power samples are synthetic and it explicitly reports `nativeExecution=false`; those 66 checks establish policy and Android storage behavior, separate from the native tests in the full suite. Real charging, unplug, thermal and sustained-load events remain unqualified. Evidence is in workspace `vendor/pr-readiness/pr4/final-save-<UUID>{-instrumentation.txt,.json}`.

## Historical phone checkpoint: `777f441`, 2026-09-29

Target **OnePlus 9 Pro LE2125**, reported API **36**, arm64, **4096-byte** pages. Actual packaged engine **FFmpegKitNext 9.0.0 / FFmpeg n9.0.1**, original source/licensing pin preserved.

| Gate | Observed result |
| --- | --- |
| Host JUnit | **117 passed**, zero failures: core 81, engine 11, app 25 |
| Settings CLI | **41 settings assertions + 4 power regressions passed** |
| Android build/lint | Matching debug app/test APKs built; zero lint errors |
| Physical instrumentation | **38 passed**, zero failures/skips; Share/preset/default/undo, settings sheets/search/reset, real storage adapter, native DSP/analysis/preview/jobs and existing touch/source-first checks |
| Direct ADB settings `all` | **49/49 checks passed**: 45 pure assertions plus four real Android storage checks. Schema 2 report UUID `0f5246a0-543c-4ca0-9466-d37524384f44`; power remains explicitly synthetic and `nativeExecution=false` |
| Downloaded media through real settings | Files Share → Forma → Defaults & overrides → Audio → Channels Mono → Apply to job → Audio output FLAC → Convert. **19.173878 s**, **845568 frames**, 44.1 kHz mono, **915944 bytes**; full independent decode passed and decoded mono exactly matched `(left + right) / 2` (maximum error **0**) |
| Storage | Fresh-store persistence, strict malformed UTF-8, read/write bounds, aborted atomic replacement and corrupt-document save refusal; disposable private directories preserve real preferences |
| Existing repository checks | Core, native readiness/payload verifier and UX/concurrency checks passed |
| Independent integration review | Seeded undo, codec/container import and effective stereo/recovery-control findings reproduced and fixed; final focused review found no residual Critical/Important issue |
| Release isolation | Unsigned minified release built with `testing/` physically absent and both `audioTests=false` / `settingsTests=false`; dex excludes instrumentation classes, runner, test provider and scenario commands |
| Native packaging | Debug/release actual native payload and 16 KB ELF/ZIP alignment checks passed; runtime on 16 KB pages remains unrun |

Historical tested APK SHA-256:

```text
debug      a106ef1866c38f870017e6416349165d7ef2497b613223d61f35d35fe7141192
test       de2c88b609797eba61e606d2c48a5b09ea5666be00115c55b1619bff4c8b0086
release    0eb82458ec9e1726ddb56fa76640046ef0f5082edeaec59156ac31df2d405a98
UI FLAC    e10e6f76b103c8e9cf7d19ebdedbadc685fdac9228f5c16c6d208c8eade58eb5
```

The historical debug app was installed and exercised on the phone. Its unsigned release was statically checked, not installed. Reports/media/APKs remain untracked under the workspace. Earlier CI records in the foundation handoff refer to their own revisions and are separate from these combined-tree results.

The branch subsequently incorporated power-monitor/runtime, provenance, queue/privacy and verification changes. They are not included in the historical APK identities or results above.

## Repeat

```bash
bash testing/settings/host/check.sh
./gradlew -PformaLab=true -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  -PgraphicsPathRepo="$GRAPHICS_REPO" \
  :core:test :engine-ffmpeg:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w -r -e formaNative true \
  dev.forma.transcode.lab.settings.test/androidx.test.runner.AndroidJUnitRunner
./gradlew -PaudioTests=false -PsettingsTests=false -PffmpegEnabled=true \
  -PffmpegRepo="$NATIVE_REPO" -PgraphicsPathRepo="$GRAPHICS_REPO" :app:assembleRelease
```

The optional lab build uses `dev.forma.transcode.lab.settings`; derive report paths and sender/instrumentation packages from that ID. The normal release ID is unchanged. Use the [direct ADB guide](../testing/settings/README.md) for a fresh UUID settings scenario and its report, adjusting its package to match the installed pair. Require successful instrumentation and a matching PASS report with positive equal selected/passed counts; transport success alone is insufficient. Keep hashes of the installed APK pair alongside the report's caller-reported revision.

## Remaining work

Persisted origins/override summaries, live Android power-monitor/service enforcement and the queue/privacy consumers are implemented. The 31 Planned rows, including native size-fit defaults and automatic hardware routing, still need consumers. Settings dialog layers/drafts survive Activity recreation; full process-death recovery of the whole unsaved editor remains a separate gate. No synthetic battery assertion certifies physical enforcement or thermal behavior. Wide/large-text/RTL/TalkBack/keyboard/contrast, real charging/thermal/screen-off events, sustained media, another device/API 26/16 KB runtime, headphone/Bluetooth/listening/A-V sync, AAB and exhaustive dependency-graph isolation remain separate gates. PR2's newer clip/timeline/queue model still needs reconciliation before the editor branches merge.
