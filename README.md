# Forma — Android Transcode

**A native Android app for making video, audio and images fit upload limits.**
Kotlin/Jetpack Compose is the UI. In-process **FFmpeg for Android is required**
for actual exports. Hardware video acceleration uses checked Android MediaCodec
components; GPU filtering and NPU inference are separate, later capabilities.

## Implementation agent: start here

Read **[the agent entry point](docs/AGENT-START.md)**, then the
[Android handoff](docs/android-handoff.md),
[acceleration design](docs/android-acceleration.md) and
[ordered implementation plan](docs/superpowers/plans/2026-09-28-native-android.md).

| Component | Location | Actual status |
| --- | --- | --- |
| Native Android app | `app/`, `core/`, `engine-ffmpeg/` | Compose/document/queue/service foundation; FFmpeg adapter; newly checked explicit H.264/H.265 MediaCodec preparation. Real native bundle/device qualification is required. |
| Upload-first reference | [`studio/`](studio/README.md) | Working desktop byte-fit retries, video/audio/still images, URL import and bracket editor. These newer workflows still need a native port. |
| Acceleration policy and package checks | `core/Acceleration.kt` (under Kotlin sources), `tools/verify_android_native.py` | Tested host-side policy and static native payload/alignment gate; neither is a phone speed or compatibility result. |

**Studio is not the product runtime.** Do not wrap it in a privileged WebView or
ship its Python server as the Android solution. A UI-only APK, Kotlin/API-only AAR
or desktop encode is not an FFmpeg-enabled Android app. No native binaries are
stored in this repository. The new Auto policy is ready for native upload-job
integration; existing quality presets are not silently switched to hardware.

## Required Android build and qualification

Use JDK 17, Android SDK 36, Build Tools 35.0.0 and the pinned source-build toolchain.
Minimum Android version remains 8.0/API 26. Start with physical arm64 devices.

```bash
./tools/build-ffmpeg.sh
NATIVE_REPO="$PWD/vendor/ffmpeg-kit-next/prebuilt/bundle-android-aar-24-maven"
python3 tools/verify_android_native.py \
  "$NATIVE_REPO/com/arthenica/ffmpeg-kit-next/9.0.0/ffmpeg-kit-next-9.0.0.aar"
./gradlew -PffmpegEnabled=true -PffmpegRepo="$NATIVE_REPO" \
  :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 tools/verify_android_native.py app/build/outputs/apk/debug/app-debug.apk
```

The helper now explicitly enables `--enable-lib-android-media-codec` alongside
x264. The source pin and existing GPL profile are retained. Read
[FFmpeg setup](docs/ffmpeg.md) for the supported build host and licensing decisions.
Read the handoff for static alignment failures, the explicit native smoke-test
command and physical-device acceptance. App licensing is not silently assigned.

For host-only tests:

```bash
./tools/check-core.sh
./tools/check-android-readiness.sh
```

The ordinary `./gradlew :app:assembleDebug` is still a **no-native UI-development
build**, not a shippable transcode build. Release preparation refuses a missing
`ffmpegEnabled=true` flag; the payload verifier still checks the actual binaries. Existing API-contract CI intentionally
compiles a wrapper without .so files; never distribute that artifact as FFmpeg.
Windows can use `gradlew.bat` and Python for APK checks; follow upstream's supported
host instructions for the native source build.

## User workflow to preserve during the native port

Home defaults to **10 MB**, with 20/25/50/100/500 MB and Custom. Select media or
paste a direct media URL, inspect, then Convert. Show detailed settings only when
requested; keep the expandable left shelf and bracket-based trimming. Changing
a goal or opening Advanced must not discard edits.

Limits use decimal bytes. Only verified output strictly below the selected cap
is ready to share. Re-encode valid oversized candidates from the original with
stronger compression (four video/audio attempts; seven image attempts). Do not
truncate, silently remove sound/transparency or publish an oversized success.
See [upload-size contract](docs/upload-limits.md) and
[timeline contract](docs/timeline-editor.md).

## Run the Studio reference, not the Android runtime

With Python 3.10+, desktop FFmpeg and FFprobe on PATH:

```bash
python studio/server.py
# Optional local samples and standalone layout:
python studio/build.py --samples
```

The reference supports MP4/H.264+AAC and M4A/AAC upload goals, PNG/JPEG/WebP stills,
and non-destructive single-source editing. It does not imply native parity.
Generated media and builds are excluded from git; its queue is session-only,
whereas the native queue is persisted.

[Architecture](docs/architecture.md) · [Native testing](docs/testing.md) ·
[Studio tests](studio/README.md) · [Touchscreen contract](docs/touchscreen.md)

## Native responsiveness update

The native UI now starts with media selection, uses an expandable left shelf and
separate queue, and keeps cancellation available during other work. A process-wide
run coordinator protects native cleanup from overlapping starts; bounded progress
updates are collected only by a small progress view. The service supports Stop,
Finish current, completion notifications and bounded screen-off wake handling.
See [native UX, threading and performance validation](docs/native-ux.md).
Run `tools/check-ux.sh` for the host concurrency checks. This does not close the
remaining native upload-fit/URL/image/full-editor or physical-device test gates.
