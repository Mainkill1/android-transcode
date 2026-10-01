# Precision timeline qualification · 2026-10-01

Device: OnePlus 9 Pro (LE2125), Android 16, arm64-v8a; ADB `10.0.0.56:38477`. Tests ran on isolated `dev.forma.transcode.lab`. The final preview/gesture APK SHA-256 was `8fd798ca54379aaa32c5cd2fc7656fa6e8db5266ea4586cb842bd18a244992c0`; the earlier export APK was `e7929285ee2b2ecf029f0cb6b8ed0bb5fc75606b46e45692fce74948c20e78d6`. The normal Forma package and its data were not used for the lab tests.

The four initial timeline Compose tests passed on this phone: seek leaves the trim unchanged, exact entry retains a 1 ms difference on a three-hour timeline, a completed bracket drag emits one trim, pinch zoom/Fit restore the visible range, and a native source produced seven real bounded thumbnails. A fifth test passed on the final APK: a rejected bracket edit returns to the committed trim. The selected movie clip preview action mapped source 1,000 ms to movie 5,500 ms after a preceding 6-second clip and 500 ms transition. The five-second movie preview test generated two six-second sources, rendered the complete 11-second dissolve, then rendered only movie time 3–8 seconds. Its decoded duration was within 4,800–5,200 ms; sampled red/dissolve/blue pixels matched the corresponding full movie samples within 35 RGB levels. Strict decode passed, both source SHA-256 values remained unchanged, and no whole-source staging files appeared in work storage on the final APK. Preview inputs stayed on the registered SAF paths until native execution finished.

The production `FfmpegTranscoder` and verifier completed two direct exports from the isolated lab app. Each command round-tripped a queued job snapshot, traversed `RUNNING → VERIFYING → COMPLETED`, strictly decoded output, and confirmed the source hash unchanged:

| Source / request | Observed result | Local report |
| --- | --- | --- |
| 52.2-second sample, keep 10,123–14,789 ms | 4,666 ms, 140 frames at 30 fps | `source/vendor/precision-timeline/results/48e9a50f67bd4afda5e783308cfb891b/device.json` |
| 3:00:05 synthetic one-frame-per-second source, keep 2:00:00.123–2:00:05.123 | 5,000 ms, 150 frames at 30 fps | `source/vendor/precision-timeline/results/3c47297c1c734d50b47e5e14545343aa/device.json` |

The long source deliberately has one frame per second. Millisecond entry and export duration are exact, but source image changes can only occur at its real frame boundaries. The renderer does not promise frame-accurate seeking on variable-frame-rate sources. The seven filmstrip thumbnails use Android's sync-frame retrieval; they are navigation aids, while the requested millisecond times and verified export remain authoritative.

Host `:core:test :engine-ffmpeg:testDebugUnitTest :app:testLabUnitTest :app:assembleLabAndroidTest :app:lintLab` passed. The same four timeline Compose tests passed on the API 28 x86_64 emulator, with its native-only fixture skipped because the native bundle is arm64-only. Additional manual TalkBack, playback listening, variable-frame-rate and high-resolution source checks remain for review; this PR stays Draft until those checks and CI are complete.
