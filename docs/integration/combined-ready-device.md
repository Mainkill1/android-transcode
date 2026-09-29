# Combined ready-PR build

This integration branch produces one native Forma installation containing the reviewed clip/movie editor, audio editor, settings, runtime-codec/size-limit workflow, and image editor.

| PR | Included reviewed head |
| --- | --- |
| #2 Clip and movie editing | ea9f93603c4f8e55a50b4bfe278f4d805d967256 |
| #3 Audio editing | f837c85b31561d8327fb624f5814658370447b42 |
| #4 Settings and power safeguards | 9b0fa7fc73527f97b95734ffbaed69494375f2af |
| #6 Runtime codec trials and byte caps | d0f9409d2fd8844473cf7c41e9bbadbffd7b5993 |
| #8 Image editing | ae003a7916bf18efd0224635c4eaa7e9ae82d9ed |

PR #5 remains a draft and is excluded. The original branches and main are preserved.

The queue writes schema 4 with explicit AV/image tags, frozen preferences, completion times, and complete AV cap/sequence/settings intent. Readers distinguish the previously published schema-2 audio/clip/runtime and schema-3 image/settings/movie envelopes. Unknown or hybrid envelopes fail without rewriting the original file. Future audio graphs remain preserved and visible; unsupported processing stays blocked.

Single files and movies share staged-original execution, one bounded codec/size retry budget, native preparation, full decoding and stream-clock verification, audio analysis and publication rollback. Movie clips carry their own DSP and clip effects. Unsupported measured-normalization combinations and per-clip file caps fail clearly rather than being ignored. Fixed/lossless audio gets one unchanged verified candidate; the selected output format is retained.

The app remains Kotlin/Compose with in-process source-built FFmpeg. API26 minimum, SDK36, source pins and GPL/native licensing are unchanged. The image-capable bundle adds the already-reviewed PNG/JPEG/WebP support. Test-only sources are outside product source sets and can be disabled with `formaTests=false`.

Qualification, installed APK hashes and private device backups are retained under the workspace's `vendor/combined-ready/`; they are not committed. Host tests, APK alignment checks and physical media results are separate evidence. Installation updates `dev.forma.transcode` in place; the older lab/test packages are removed only after its data is archived and the replacement is qualified.

## 2026-09-29 device build

The retained installable build is `vendor/combined-ready/forma-ready-prs.apk` (SHA-256 `eeb96f846360660fe8306507e77834ca579b5a3a1b81ec177b175c3173f41509`). It is signed with the existing debug certificate, has application ID `dev.forma.transcode`, version code 2, and version name `0.1.0-ready.20260929`. The APK contains the arm64 FFmpeg and graphics libraries; the static native payload/alignment check passed.

The full Gradle build, Android test APK, lint, and 321 JVM tests passed without failures or skips. Host readiness, core, UX, acceleration, runtime verification, and desktop exports passed. On the OnePlus 9 Pro (API 36), the disposable lab package passed 26 image scenarios, 68 settings checks, 30 UI/share/editor tests, five single-file editor cases, seven movie cases, audio DSP/analysis/preview/export, image/audio/video/movie service publication, the combined movie/audio/timing regressions, and a downloaded-MP4 byte-cap retry/cancellation case. Its H.264 automatic route selected and verified a device encoder in one runtime trial; that result does not qualify every codec or configuration. Detailed reports and media are in `vendor/combined-ready/`.

The normal package was updated in place. All 18 pre-existing completed jobs and 18 output files remained after launch; the installed APK SHA-256 matches the retained build. Nine older lab/test packages were uninstalled, leaving only `dev.forma.transcode` in the device's Forma package list. Private app data and lab qualification archives remain in `vendor/combined-ready/device-backup/` and `vendor/combined-ready/lab-qualification-files.tar`.
