# Settings and audio integration on the target phone

Product commit: `777f441de12768ef16e7497aa1f3860fe8066ffe`. This combines PR4's latest `bb084e6` settings foundation with PR3's `3082e1c` verified native audio app using regular merges. PR4 remains a draft for its remaining implementation gates. No merge to main is performed.

## Integration behavior

- App defaults seed an untouched editor once. Audio import retains seeded bitrate, routing and processing instead of replacing the whole settings value with a preset. AAC selects M4A; FLAC selects FLAC; PCM selects WAV. Unsupported audio-only Opus requests remain unchanged and show an explicit format-choice message. Audio-only sources can choose M4A/WAV/FLAC even if their saved defaults used a video container.
- WAV/PCM16/float and FLAC settings round-trip without dropping effects, sample rate, normalization or byte caps. Channel settings use the audio editor's Source/Stereo/Mono/Left/Right/Swap routing. The legacy stereo switch reads and edits that effective route. Unrelated legacy fallback values are retained so undo can restore their behavior.
- Undo history starts from seeded defaults without an artificial undo step. Applying an audio output change through Defaults & overrides records an audio-history edit; previews invalidate through the same graph identity. Presets preserve the current audio graph and Share imports preserve an explicitly selected preset.
- MainActivity combines saved theme with cold/warm Share handling. Home retains compact actions and statistics; detailed settings remain behind Settings or Defaults & overrides.
- All 64 catalog IDs and 18 bound rows remain. New choices extend existing audio/container rows; the other 46 rows remain Planned. Sample-rate/normalization defaults in the Settings catalog remain Planned even though the contextual audio editor supports those operations. Preferences do not claim a consumer that has not been connected.
- The latest choice-sheet navigation, unknown-power-transition regressions and direct ADB protocol are retained. Settings has no Python device driver. The host Kotlin script builds inside `testing/results/`; no host temporary directory outside the workspace is needed.

## Observed checks, 2026-09-29

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

Exact tested APK SHA-256:

```text
debug      a106ef1866c38f870017e6416349165d7ef2497b613223d61f35d35fe7141192
test       de2c88b609797eba61e606d2c48a5b09ea5666be00115c55b1619bff4c8b0086
release    0eb82458ec9e1726ddb56fa76640046ef0f5082edeaec59156ac31df2d405a98
UI FLAC    e10e6f76b103c8e9cf7d19ebdedbadc685fdac9228f5c16c6d208c8eade58eb5
```

The debug app is installed and exercised on the phone. The unsigned release is statically checked, not installed. Reports/media/APKs remain untracked under the workspace. Earlier CI records in the foundation handoff refer to their own revisions and are separate from these combined-tree results.

The remote PR4 branch received subsequent power-monitor/runtime commits while this checkpoint was being qualified. Those commits are not included in these APK identities or results. The verified integration is preserved on `feat/native-settings-integration` so the active PR4 work can incorporate it deliberately.

## Repeat

```bash
bash testing/settings/host/check.sh
./gradlew -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  -PgraphicsPathRepo="$GRAPHICS_REPO" \
  :core:test :engine-ffmpeg:testDebugUnitTest :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$SERIAL" shell am instrument -w -r -e formaNative true \
  dev.forma.transcode.test/androidx.test.runner.AndroidJUnitRunner
./gradlew -PaudioTests=false -PsettingsTests=false -PffmpegEnabled=true \
  -PffmpegRepo="$NATIVE_REPO" -PgraphicsPathRepo="$GRAPHICS_REPO" :app:assembleRelease
```

Use the [direct ADB guide](../testing/settings/README.md) for a fresh UUID settings scenario and its report. Require successful instrumentation and a matching PASS report with positive equal selected/passed counts; transport success alone is insufficient. Keep hashes of the installed APK pair alongside the report's caller-reported revision.

## Remaining work

Persisted app/preset/job origins and the override summary, live Android power-monitor/service enforcement, native size-fit defaults/automatic hardware routing and other Planned consumers remain implementation work. No synthetic battery test is described as live enforcement or thermal qualification. Full-dialog process restoration, wide/large-text/RTL/TalkBack/keyboard/contrast, real charging/thermal/screen-off events, sustained media, another device/API 26/16 KB runtime, headphone/Bluetooth/listening/A-V sync, AAB and exhaustive dependency-graph isolation remain separate gates. PR2's newer clip/timeline/queue model still needs reconciliation before both editor drafts merge.
