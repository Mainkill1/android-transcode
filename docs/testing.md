# Testing and acceptance

## Bootstrap evidence

On 2026-09-28, the SDK-free `tools/check-core.sh` run compiled the actual Kotlin core and passed **39 checks**. Initial validation and an NV12 color-classification regression were observed failing before their fixes. The host had Java/Kotlin but no Android SDK/Gradle, and outbound container downloads failed, so a local APK build, Android lint, Android JUnit and device execution were **not** claimed.

This file records the bootstrap host, not a substitute for the status of the current commit's GitHub Actions run. Check the workflow result for Android compilation and lint. The queue-codec JUnit tests and Compose tests are authored tests until their respective Gradle/device runs pass.

## Repeatable commands

```bash
./tools/check-core.sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Core cases cover validation, argument boundaries, preset isolation, per-job immutability, trim duration, no overwrite/upscale intent, codec/rate-mode compatibility, color gates, progress and state transitions. `CoreTest` wraps the 39 named checks in one JUnit method; the command-line runner prints each case.

`JobCodecTest` covers full round-trip, null trim end, unsupported schema and duplicate IDs. Instrumentation tests cover default Simple mode and preserving custom settings across Advanced toggles. They are not coverage of Android service correctness.

CI builds the no-native shell, runs JVM tests, runs lint and compiles the instrumentation APK. A separate native API job compiles the adapter against the actual pinned upstream Kotlin wrapper, without pretending native libraries exist. Manual `run_device_tests=true` starts an emulator UI-test job. Native media qualification remains separate.

## Required device/media matrix before a release

| Area | Evidence to retain |
|---|---|
| Basic encode | Short AVC/AAC sample to MP4; FFprobe before/after; actual playback; duration/dimensions |
| Other codecs | Enabled x265/VP9/AV1/Opus/FLAC profiles individually, not inferred from an encoder listing |
| Audio/track mapping | Silent video, audio-only source, two language tracks, selected-track output |
| Trim/filter | Non-zero trim, odd dimensions, source-size/no-upscale, deinterlace, denoise, requested frame rate |
| Capability failure | Missing encoder/muxer/filter; corrupt or unsupported source; unavailable native ABI |
| Lifecycle | Rotation, screen off, STOP during staging/encode/verification, process kill, foreground-service timeout |
| Storage | Revoked grants, cloud providers, changed source, short write, low space, export cancellation; original hash unchanged |
| Native packaging | arm64, applicable emulator ABI, 16 KB page-size device; full real `.so` bundle, not API-only AAR |
| Long jobs | Actual thermal, battery and storage behavior; no invented ETA or guaranteed output size |

Retain commands/settings, source and output hashes, native build identity, Android/device version and failure logs with each qualification run. Current structural verification tolerates up to max(1 second, 5% of expected duration); it is not a strict accuracy or perceptual quality threshold.
