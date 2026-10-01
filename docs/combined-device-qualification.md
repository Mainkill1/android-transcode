# Combined ready build — 2026-09-29

This local integration branch starts at the ready device build after PR #9/#10, merges the qualified software-video PR #11, applies the WebP preflight repair from PR #8, and uses the same native FFprobe cancellation adapter and source patch pushed to PR #2/#3/#4/#6. It does not change `main`.

| Artifact | SHA-256 |
| --- | --- |
| Source-built arm64 FFmpegKitNext AAR | `09bd962f7d3ad66b9bc29c2df67681b55b6dc6c2472135d48490e94ea51a7d3a` |
| Normal `dev.forma.transcode` APK | `b1cd021ef6383295860bb268d1736a02622117d5e8190726f327c2b55d2167a5` |
| Isolated lab APK | `7c5516a1a4bc87ca9c260e57e451460ec8ee747ca4804baf6fc9bad624d0b3b7` |
| Matching lab instrumentation APK | `4d38bf901647609592630f1947e67c4c59d89e6f5f0019129272bd36aaf4ff2f` |

The AAR and both application APKs passed `tools/verify_android_native.py --require-abi arm64-v8a`, including 16 KB ELF/RELRO and APK ZIP alignment. Gradle app/core/engine tests, lab lint, host core, runtime-acceleration and movie checks passed. The phone was a OnePlus LE2125, API 36, with 4096-byte pages; a physical 16 KB-page device remains untested.

On the isolated lab install, native FFprobe cancellation passed 3/3 twice with the final test APK, software codecs passed 2/2, the image scenario passed 27/27, audio export passed 1/1, movie service export passed 1/1, and the Queue/Finished screen passed 10/10. The cancellation test's third case stops a real queued renderer during a real frame-count scan, prevents publication, and completes a subsequent native AAC export; it substitutes the long fixture's earlier encode and strict decode to isolate Stop during the scan. The software service report (`app/build/device-evidence/combined-software-service.json`, SHA-256 `ca8cf54d212fd40b50439fece4ad5edecfcb3952b26eb88963cd6a5580697df7`) records durable `COMPLETED`, strict full decode, 36 counted frames/1500 ms and rendered playback for H.265, VP9 and AV1. H.265 retained AAC; this AAR lacks Opus, so the two WebM cases are video-only. The direct smoke report SHA-256 is `8735e4c97ce942c3c344333f2b5dc78725238c7cc74a4db1d03995f89800d85d`.

The image report (`app/build/device-evidence/combined-image-report.json`, SHA-256 `8788e57f5b110a31f734f6df2f926d358538c72ad0131a6557ded9ff26978760`) records the malformed WebP's 1×1 canvas and 64×48 payload rejected before native execution (`nativeExecuteCalls=0`), without queue success or output publication. The other 26 cases also passed. Raw instrumentation logs and local reports remain under ignored `app/build/device-evidence/`.

The normal package was updated in place and launched. Its `queue-v1.json` bytes remained unchanged (SHA-256 `106f775abf695ee6f5f64b63a7f7898bf3dbdc44f273d2de0899fabb02e3b2f7`): 24 saved jobs, with 1 waiting and 23 in Finished. The device showed separate Queue and Finished tabs. After testing, every lab/test package was uninstalled; only `dev.forma.transcode` remains. The existing three-hour AV1-device job was left waiting and was not started.

This run qualifies cancellation at the native bridge and `RunCoordinator`/renderer boundaries. It does not claim a full physical `TranscodeService` Stop during a long output-verification scan, sustained thermal policy behavior, or qualified AV1 device encoding. The hardware-acceleration PR #5 remains Draft for its separate blockers.
