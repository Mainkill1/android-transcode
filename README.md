# Forma — Android Transcode

A simple-first media transcoder with detailed video/audio controls and a lightweight editor. The native Android application uses Kotlin, Jetpack Compose, and FFmpeg. The latest interactive layout and working desktop FFmpeg reference now live in this repository, not only in chat attachments.

## Start here

| Component | Location | What works today |
| --- | --- | --- |
| Touch-first UI and desktop runner | [`studio/`](studio/README.md) | Local files and direct HTTP(S) media URLs; real video/audio conversion; single-source trim, split, rearrange, crop, rotate, speed, volume and fades. |
| Native Android application | `app/`, `core/`, `engine-ffmpeg/` | Original application/queue foundation. Encoding requires the separately source-built native bundle. Material controls now reserve at least 52 dp for interaction. |

**The desktop workbench is not a WebView, APK, or finished native editor.** Its newer URL import and editing workflows still need to be ported into the native app. The two execution paths are documented separately; a desktop FFmpeg pass does not qualify Android encoding.

## Try the latest layout and working conversion

With Python 3.10+, FFmpeg and FFprobe on PATH:

```bash
python studio/server.py
```

The app opens in your browser. It binds to loopback only. Choose media or paste a direct-media URL, then use **Video**, **Audio**, or **Edit**. Advanced controls stay behind More settings and the expandable left shelf.

Optional generated samples and a standalone HTML preview:

```bash
python studio/build.py --samples
```

Open `studio/forma-studio.html` for local previews and settings without starting the runner. Actual encoding and URL download require the runner. Generated media and builds are deliberately excluded from git. See [touch behavior and native handoff](docs/touchscreen.md).

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

The workbench has separate pure planning, loopback execution and browser presentation layers. It is a reference for native implementation, not a replacement engine. Its queue is session-only; native queued jobs are persisted.

[Architecture](docs/architecture.md) · [Native testing](docs/testing.md) · [Studio tests and limitations](studio/README.md) · [Touchscreen contract](docs/touchscreen.md)

Native HDR processing, qualified hardware encoding, multi-track authoring, subtitles, chapters and comparison preview remain later stages. Keep device/emulator results separate from JVM, browser and desktop FFmpeg results.
