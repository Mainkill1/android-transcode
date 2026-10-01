# Android hardware acceleration implementation plan

**Goal:** Extend the existing checked FFmpeg/MediaCodec export path across hardware vendors and make worthwhile faster paths measurable from ADB without shipping a test control plane.

**Architecture:** FFmpeg retains export ownership. Android capability checks bind an exact encoder, raw input layout and non-frame-dropping bitrate mode. Experimental API/decode/surface routes live only in the separate instrumentation source tree until physical-device qualification.

**Spec:** `docs/android-hardware-acceleration-research.md`.

## Constraints

API 26 minimum, SDK 36, JDK 17, existing FFmpeg source/licensing pin unchanged. Preserve explicit hardware failure, SDR restrictions, CPU filter semantics and native cancellation ownership. No silent quality/codec changes, unsupported HDR, frame-dropping bitrate modes, native binary downloads, scheduled jobs, production benchmark hooks or claims of measured speed without phone evidence.

## Work

1. Extend hardware encoder selections to VP9/AV1 with container validation and native preparation. Test that missing wrappers/device encoders remain unavailable and that software-only options never reach hardware.
2. Select a complete advertised VBR/CBR and planar/semi-planar configuration, not just the first color format. Keep unknown hardware, secure/tunneled components and unsupported requests rejected. Bind the selected mode into the actual FFmpeg command. Test stale request rejection and no frame-dropping mode.
3. Add an isolated ADB-driven test APK entry point and host runner for Java/NDK buffer encoding, NDK async and decoder/surface experiments. Generate known SDR fixtures; record exact component/configuration, native/app identity, frame count/PTS, full decode result, bytes, stage timing and thermal status. A requested missing route fails rather than passing or silently switching.
4. Document investment priorities and Qualcomm/MediaTek/Exynos/Tensor/budget test coverage from primary sources. Distinguish sales data from installed share and chip marketing from available app codecs.
5. Run available host checks, inspect the patch, publish a draft PR. Android build/lint, native packaging and physical-device qualification remain explicit unrun gates when no SDK/device is available.

## Review focus

No future AV1 decoder mistaken for an encoder; no surface-only component accepted as raw-buffer input; no stale bitrate/dimensions after retry; no dropped edits to enable a surface route; no stale or skipped test report interpreted as success. The test fixture surface route is not a production GPU editor.
