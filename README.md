# Forma — Android Transcode

A simple-first media transcoder with upload-size goals, detailed video/audio controls and a lightweight editor. The native Android foundation uses Kotlin, Jetpack Compose and FFmpeg. The latest working desktop reference and interactive layout live alongside it in this repository.

## Start here

| Component | Location | What works today |
| --- | --- | --- |
| Upload-first Studio and desktop runner | [`studio/`](studio/README.md) | Size presets, automatic settings, measured oversize retries, real video/audio and PNG/JPEG/WebP image conversion, direct HTTP(S) media import, and the bracket-based single-source editor. |
| Native Android application | `app/`, `core/`, `engine-ffmpeg/` | Original application/queue foundation. Encoding requires the separately source-built native bundle. Material controls reserve at least 52 dp for interaction. |

**The Studio workbench is not a WebView, APK, or completed native editor.** The new size-goal, still-image, URL-import and editing workflows are implemented in Studio; they still need a native port. A desktop FFmpeg test does not qualify Android encoding.

## Make a file fit an upload limit

With Python 3.10+, FFmpeg and FFprobe on PATH:

```bash
python studio/server.py
```

The home screen defaults to **10 MB**, with **20, 25, 50, 100, 500 MB and Custom** goals. Select media or paste a directly downloadable media URL. After inspection, Forma chooses compatible output settings; press **Make it under 10 MB** to encode.

The runner measures the complete output, not an estimate. Valid oversized files are re-encoded from the original with stronger compression, up to four attempts for video/audio or seven for images. Only an output strictly below the byte limit is marked ready and exposed for download. Impossible goals fail clearly rather than silently shortening the clip, removing sound or publishing an oversized file. Each queued job retains its own goal and editing settings.

Video size goals use MP4/H.264 with AAC; audio goals use M4A/AAC. Still images support WebP, JPEG and PNG, with transparency preserved where supported. GIF/APNG/animated WebP are rejected rather than flattened. Limits use decimal MB. Presets are generic size goals: Discord currently documents 20 MB for free uploads, while Forma retains the requested 10 MB default. See the [size-goal contract, source references and limitations](docs/upload-limits.md).

Detailed controls remain under **More settings**, including an explicit **Use manual settings — no size limit** choice. The left shelf provides quick navigation. Existing bracket trimming, crop/rotation, split/reorder, speed and sound edits remain in the same workflow.

Optional generated samples and a standalone HTML preview:

```bash
python studio/build.py --samples
```

Open `studio/forma-studio.html` for local previews and settings. Actual encoding and URL download require the loopback-only runner. Generated media and builds are deliberately excluded from git.

## Native Android build

Use JDK 17, Android SDK 36 and Build Tools 35.0.0. Minimum Android version: 8.0 / API 26.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Windows uses `gradlew.bat`. The default build does not bundle native FFmpeg; conversion remains unavailable rather than reporting simulated progress. The checksum-pinned launcher downloads the Gradle 8.13 wrapper on first use.

The pinned FFmpegKitNext 9.0.0 source build and selected codec profile are unchanged:

```bash
./tools/build-ffmpeg.sh
./gradlew -PffmpegEnabled=true \
  -PffmpegRepo="$PWD/vendor/ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven" \
  :app:assembleDebug
```

Read [FFmpeg setup](docs/ffmpeg.md) for prerequisites and distribution/licensing decisions. The native profile enables GPL and x264; this repository does not silently assign an application license. Do not distribute native binaries before meeting the selected bundle's obligations.

## Architecture and validation

`app -> engine-ffmpeg -> core`, with `app -> core`. The core never imports Android APIs or a native binding. Queue entries own immutable settings and per-file trims. The native service stages input privately, executes argument arrays, verifies output, and exposes Open/Share/Save. Native interrupted jobs are not automatically resumed.

Studio has separate pure planning, loopback execution and browser presentation layers. Its queue is session-only; native queued jobs are persisted. Missing codecs never produce simulated success. Tests cover both normal fitting and forced oversize retries, plus source files genuinely larger than the default 10 MB limit.

[Architecture](docs/architecture.md) · [Native testing](docs/testing.md) · [Studio tests](studio/README.md) · [Upload limits](docs/upload-limits.md) · [Touchscreen contract](docs/touchscreen.md)

Native HDR processing, qualified hardware encoding, multi-track authoring, subtitles, chapters and comparison preview remain later stages. Keep device/emulator results separate from JVM, browser and desktop FFmpeg results.
