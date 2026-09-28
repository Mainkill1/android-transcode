# FFmpeg on Android

## Binding and provenance

Use [arthenica/ffmpeg-kit-next](https://github.com/arthenica/ffmpeg-kit-next), pinned to source commit `5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3`. At this revision the Android artifact is `com.arthenica:ffmpeg-kit-next:9.0.0`. This is a source-built integration, **not** the retired `com.arthenica:ffmpeg-kit-full` download and not a randomly selected third-party binary mirror.

Upstream [Android instructions at the pin](https://github.com/arthenica/ffmpeg-kit-next/blob/5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3/android/README.md) describe the Nix `android-r27d` profile and generated local Maven repository. Upstream transitive source downloads are governed by upstream's build scripts; pinning the wrapper repository is not by itself a bit-reproducible supply-chain guarantee.

## Build once, consume locally

Install Nix on an upstream-supported host. Run `tools/build-ffmpeg.sh` from this repository. The helper checks out the pinned source under ignored `vendor/ffmpeg-kit-next`, refuses a different or dirty checkout, and invokes:

```bash
./nix-android.sh -p android-r27d --enable-gpl --enable-lib-x264
```

Additional upstream flags can be passed to the helper. Start with x264 + built-in AAC for the default presets. x265, libvpx, Opus and SVT-AV1 are optional build/profile additions; the application checks encoder availability rather than pretending all options ship.

Consume the resulting **Maven repository directory**, not the AAR file itself:

```bash
./gradlew -PffmpegEnabled=true \
  -PffmpegRepo=/absolute/path/to/prebuilt/bundle-android-aar-24-maven \
  :app:assembleDebug
```

Gradle restricts `com.arthenica:ffmpeg-kit-next` resolution to that local repository. Maven Central resolves the upstream POM's `smart-exception-java` dependency. The property deliberately has no fallback URL or old binary coordinate. Missing/bad configuration should fail the build, not silently replace the encoder implementation.

## Integration contract

- `executeWithArgumentsAsync`, not shell string construction.
- Local staging paths are seekable for MP4 faststart and provider-independent probing.
- FFprobe JSON supplies stream counts, duration, dimensions and color hints.
- Capabilities come from the supplied build's encoders, muxers and filters. A decoder is not proof of an encoder.
- One native session at a time; callbacks are scoped per session. Coroutine cancellation calls `cancel(sessionId)` and waits for the completion callback before cleanup.
- Hardware encoding is held for device qualification. CRF is never sent to a bitrate-only device encoder.

The source-set adapter is real integration code, but **no native AAR is supplied in this repository and native execution was not run during bootstrap**. The CI `native-api-contract` job compiles the upstream Kotlin wrapper without `.so` files and uses its AAR only for adapter compilation. That artifact must never be shipped or mistaken for the complete native package.

## Qualification and distribution

Record the source pin, native library revisions/configuration, enabled libraries, ABI list and package hashes for a release. Check actual encoding and cancellation on arm64 devices, Android 16 KB page-size support, large files, temperature throttling, denied/revoked provider grants, free space and foreground-service timeouts. See [testing](testing.md).

No license is chosen for the application source in this skeleton. FFmpeg/FFmpegKitNext and optional codec libraries retain their own terms. The helper's GPL/x264 profile has distribution implications: review [upstream licensing](https://github.com/arthenica/ffmpeg-kit-next#15-license) and [FFmpeg's legal page](https://ffmpeg.org/legal.html), select compatible application licensing and provide the required corresponding sources/notices before distributing binaries. Do not label the generated GPL-enabled bundle as LGPL-only.
