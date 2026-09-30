# Verification cancellation check

The common FFprobe source patch and coroutine adapter are also used by PR #3, #4 and #6. The pinned native wrapper does not interrupt `-count_frames` without this patch. `tools/build-ffmpeg.sh` applies it temporarily and restores the pinned checkout after the build.

On 2026-09-29, the OnePlus LE2125/API 36 lab package `dev.forma.transcode.lab` passed `NativeProbeCancellationTest` (2/2) using a four-hour, 600,000-frame FLAC file. The test cancels before native start and during a running full frame-count scan, confirms native return code 255, then starts a new probe. The shared arm64 AAR SHA-256 was `09bd962f7d3ad66b9bc29c2df67681b55b6dc6c2472135d48490e94ea51a7d3a`; this branch's lab APK SHA-256 was `3bc9742c4f3e101a0a804a676d82c2759bcf3448eafb2641c11809acf2bf2e56`. Both passed native payload/16 KB alignment checks. Raw output is in ignored `app/build/device-evidence/ffprobe-cancel-v4-instrument.log`.

`MovieRenderSessionTest.stopDuringFrameCountRetainsCandidateUntilNativeEndsAndNextRenderStarts` covers the production renderer's cleanup and next-job behavior with a controlled verifier. The physical test exercises the real native scan; the renderer test uses a controlled bridge so callback timing can be asserted. A complete service Stop scenario on this exact branch has not been independently run.
