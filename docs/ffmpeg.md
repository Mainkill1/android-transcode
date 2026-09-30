# FFmpeg on Android

## Binding and provenance

Use [arthenica/ffmpeg-kit-next](https://github.com/arthenica/ffmpeg-kit-next), pinned to source commit `5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3`. At this revision the Android artifact is `com.arthenica:ffmpeg-kit-next:9.0.0`. This is a source-built integration, not the retired `com.arthenica:ffmpeg-kit-full` download and not a third-party binary mirror.

Upstream [Android instructions at the pin](https://github.com/arthenica/ffmpeg-kit-next/blob/5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3/android/README.md) describe the Nix `android-r27d` profile and local Maven repository. Pinning the wrapper does not make all transitive source downloads bit-reproducible; record native revisions, configure flags, dependency versions and package hashes.

## Build and package the actual engine

`tools/build-ffmpeg.sh` checks out the pinned source under ignored `vendor/ffmpeg-kit-next`, refuses a different or dirty checkout, and invokes:

```bash
./nix-android.sh -p android-r27d --enable-gpl --enable-lib-x264 --enable-lib-android-media-codec \
  '--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
```

The MediaCodec flag is essential: upstream's Android build help defaults it off. The upstream FFmpeg Android script separately enables JNI. Do not pass raw FFmpeg `--enable-mediacodec` to the wrapper frontend. Additional supported wrapper options may be passed to the helper.

Consume the resulting Maven repository directory, not the AAR path:

```bash
./gradlew -PffmpegEnabled=true \
  -PffmpegRepo=/absolute/path/to/prebuilt/bundle-android-aar-24-maven \
  :app:assembleDebug
```

Gradle restricts the wrapper coordinate to that local repository. Maven Central resolves its `smart-exception-java` dependency. There is no fallback binary URL. H.264/AAC are the required initial software profile; x265/libvpx/Opus/SVT-AV1 remain explicit optional profile additions, with runtime capabilities determining availability.

Before treating an AAR or APK as a native result, run `tools/verify_android_native.py` on both artifacts. It rejects API-only packages and missing FFmpeg libraries and checks all 64-bit native payloads for 16 KB ELF/RELRO alignment and appropriate APK ZIP alignment. Follow the [Android handoff](android-handoff.md) for commands and the initial preview dependency finding. Static checks do not establish loading or encoding on a real device.

The pinned native FFprobe source needs `tools/patch_ffprobe_cancel.py` during source builds to interrupt full frame-count scans on Stop. The helper applies it temporarily and restores the checkout afterward. The physical native bridge qualification and its exact artifact hashes are recorded in PR #6.

## Execution and acceleration contract

- Use argument arrays, staged seekable local paths and scoped native callbacks.
- Probe real media. Keep one active native encode and bound session history.
- Every app export calls `FfmpegBridge.prepare` after staging, including after attempt settings change.
- The adapter reports compiled wrappers, then checks Android configuration support at preparation time. A decoder or compiled wrapper is not proof of a usable encoder.
- Explicit H.264/H.265 device selections use a concrete checked component, VBR, supported YUV420 input and zero B frames. Current integration requires explicit fps, 8-bit SDR and no display-matrix transform. CPU decode and CPU filters remain CPU work.
- `AccelerationPolicy.AUTO` is implemented/tested in core but its native preference UI, serialized jobs and fallback loop are the next-agent milestone. Existing quality presets are unchanged; CRF never becomes a device quality scale.
- Coroutine cancellation calls `cancel(sessionId)` and waits for native completion before deleting staging resources. Hung-session watchdog work remains explicit, not unsafe cleanup.

No native AAR is supplied or device-qualified by this preparation. The CI `native-api-contract` job compiles the upstream wrapper without .so files and uses that API-only AAR for adapter compilation. Never ship it. The opt-in `NativeAccelerationSmokeTest` explicitly requires real native execution once `formaNative=true` is supplied.

## Qualification and distribution

Record native source/library revisions, configure flags, enabled codecs, ABI list, package hashes and device identity. Test actual software/device exports, strict byte caps, frames/timestamps, color, cancellation, temperature, storage/provider errors, 4 KB/16 KB page sizes and foreground-service timeouts. See [acceleration design](android-acceleration.md), [handoff](android-handoff.md) and [testing](testing.md).

Application licensing remains undecided. FFmpeg/FFmpegKitNext and optional codec libraries retain their terms. The selected GPL/x264 profile has distribution implications: review [upstream licensing](https://github.com/arthenica/ffmpeg-kit-next#15-license) and [FFmpeg's legal page](https://ffmpeg.org/legal.html), select compatible application licensing and provide corresponding source/notices before distributing binaries. Do not label this GPL-enabled bundle LGPL-only.
