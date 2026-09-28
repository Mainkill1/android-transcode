# Forma — Android Transcode

A native Android media transcoder: simple first, detailed when needed. Kotlin, Jetpack Compose, and FFmpeg. No WebView, root requirement, upload service, or simulated conversions.

**Status: application foundation, not a production-ready transcoder.** The default build opens the UI, imports supported media, edits settings, and persists jobs. Actual encoding is deliberately disabled until a source-built FFmpegKitNext artifact is supplied. See [native integration](docs/ffmpeg.md).

## The layout

One editor, not separate basic and expert applications. Start with **Make it smaller**, **Easy to share**, **Keep the detail**, or **Just the audio**, then choose Smaller / Balanced / Clearer. **Advanced** reveals controls on the same page without resetting settings.

Advanced sections cover video/format, dimensions/filters, audio, per-file trim, and metadata. The queue stays on the same screen. Originals open in another installed media viewer; an embedded comparison preview is a later feature. System light/dark appearance and a bounded, responsive layout are included.

## Project structure

```text
app/             Compose screens, editor ViewModel, Android document access,
                 atomic queue persistence, foreground service, output verification
core/            Pure Kotlin settings, presets, validation, FFmpeg argument planning,
                 color-policy checks and queue state rules
engine-ffmpeg/   FFmpeg interface + explicit missing/native implementations
  src/native/   FFmpegKitNext adapter, compiled only with ffmpegEnabled=true
  src/missing/  Honest unavailable state; never reports successful conversion
tools/           Build helpers and SDK-free core checks
docs/            Architecture, FFmpeg setup and qualification checklist
```

Dependency direction: `app -> engine-ffmpeg -> core`; `app -> core`. The core never imports Android or a native binding. Manual constructor injection keeps the first skeleton small.

## Build the application shell

Use **JDK 17**, Android SDK **36**, and Build Tools **35.0.0**. Minimum Android version: **8.0 / API 26**. Open the repository root in Android Studio, or set `ANDROID_HOME` / an untracked `local.properties` and run:

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Windows: use `gradlew.bat` with the same arguments. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. This default APK is the **no-native shell**, not an encoder.

The text launchers download the official Gradle 8.13 wrapper JAR on first use and verify its pinned SHA-256; the distribution is also checksum-pinned. First build requires network access. Android Studio users should run the launcher once before the first Gradle import, because the wrapper JAR is intentionally not checked in.

Pure Kotlin checks, without Gradle or an Android SDK, require `kotlinc` and Java:

```bash
./tools/check-core.sh
```

## Enable FFmpeg

The original FFmpegKit is retired. This repository targets the original author's source-built **FFmpegKitNext 9.0.0** API rather than assuming the old Maven binaries still exist. The source revision is pinned in `tools/build-ffmpeg.sh`.

On a supported Linux/macOS build host with Nix installed:

```bash
./tools/build-ffmpeg.sh
./gradlew -PffmpegEnabled=true \
  -PffmpegRepo="$PWD/vendor/ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven" \
  :app:assembleDebug
```

The supplied native build profile enables GPL and x264 so the default H.264 presets have an encoder. **Choose the application's license and fulfill the selected native bundle's distribution obligations before distributing it.** No application license or native binary license is silently assigned by this skeleton. See [the exact integration boundary](docs/ffmpeg.md).

## What is implemented

- Native simple/advanced editor, multi-file selection, per-file trim, immutable queued settings, and versioned atomic queue storage.
- MP4, MKV, WebM, and M4A planning; software H.264/H.265/VP9/AV1 choices; CRF or bitrate; resizing without intentional upscaling; frame-rate choice; denoise/deinterlace; selected audio track and stereo mixdown. Every encoder/filter/muxer still depends on the supplied FFmpeg build.
- Private input staging, argument-array execution, actual native progress, cancellation, and a sequential foreground queue. Process interruption is recorded as interrupted, not automatically resumed or marked complete.
- Structural output checks before publication, then Open / Share / Save as through Android. The source is never selected as FFmpeg's output.

## Explicit next stages

The initial execution slice exports the first video track and one selected audio track. Subtitles, attachments and chapters are explicitly excluded. Multi-track authoring, subtitle burn-in, chapter editing, crop/rotation, sample previews, two-pass/strict-size jobs, HDR/color preservation, and device-qualified hardware encoding are not implemented yet. They should extend this planner and engine contract, not create a second workflow.

Import currently uses Android's `MediaExtractor`; unusual formats that FFmpeg could decode may still need a broader FFprobe-based import path. Editor drafts survive rotation but are not restored after process death; **queued jobs are persisted**. Completed outputs/history do not yet have storage-management controls. Native conversion, provider failures, process-death behavior, 16 KB native compatibility and long-running device behavior still require real-device qualification.

## Verification

`core` contains **39 executable checks**, also run through JUnit. Android unit tests cover queue serialization; Compose instrumentation tests cover the default mode and preserving settings across mode switches.

GitHub Actions builds/lints the no-native shell, compiles instrumentation tests, and separately compiles the adapter against the pinned upstream Kotlin API. That API-only check does **not** prove native encoding works. Device UI tests can be enabled with the manual workflow's `run_device_tests` input. See [testing and acceptance](docs/testing.md) for the remaining release gates and the limits of bootstrap-time validation.
