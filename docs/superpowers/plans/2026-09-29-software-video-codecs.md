# Software Video Codecs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make H.265, VP9 and AV1 software encoding available in the native Android build.

**Architecture:** Extend the pinned FFmpegKitNext build profile. Keep runtime capability-gating in the existing picker. Verify generated artifacts and execute real exports before marking the PR ready.

**Tech Stack:** FFmpegKitNext, Kotlin, Android Gradle, Python build-contract tests.

**Spec:** `docs/superpowers/specs/2026-09-29-software-video-codecs.md`

## Global Constraints

- Preserve FFmpegKitNext source pin and existing x264, MediaCodec, zlib, and WebP build flags.
- Do not commit native binaries, downloads, or media.
- Keep test artifacts in the workspace.
- Do not disturb the normal app while its conversion is active; use the lab package.

## Review Focus

- The build options use upstream's exact x265, libvpx and SVT-AV1 flag names.
- A source-only/API-only AAR cannot make a codec appear available.
- WebM exports use VP9 or AV1 with compatible audio.
- A selected software encoder does not silently become x264.
- Built libraries satisfy 16 KB alignment checks and produce playable outputs on the target phone.

### Task 1: Native build contract

**Files:** `tools/build-ffmpeg.sh`, `tools/tests/test_native_build_contract.py`, `docs/ffmpeg.md`

- [x] Write failing tests for the three profile flags and direct build route.
- [x] Add profile flags and direct route to the build helper; document the profile.
- [x] Run Python build-contract tests.

### Task 2: Encoder selection and device qualification

**Files:** `testing/core/unit/dev/forma/core/CoreChecks.kt`, `docs/ffmpeg.md`

- [x] Check that H.265, VP9 and AV1 software plans map to the exact requested encoder.
- [x] Build and verify the native AAR and lab APK.
- [x] Convert generated sample media with each software encoder on the phone and inspect output streams.
- [x] Keep the PR draft until all three device conversions pass.
