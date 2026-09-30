# Software video codec qualification — 2026-09-29

The source-built arm64 lab APK was tested on OnePlus 9 Pro (LE2125, Android API 36, 4096-byte pages). It is an arm64 device build, not a universal multi-ABI release. The normal `dev.forma.transcode` installation and its running queue were not replaced.

| Layer | Result |
| --- | --- |
| Pinned wrapper | `5e51b2da4c3593c0f2f9b49f53eeb497d93e39d3` |
| Direct Android toolchain | NDK `28.2.13676358`, API 24, arm64-v8a; 16 KB linker flags |
| FFmpeg | `bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa` |
| x264 / x265 | `b35605ace3ddf7c1a5d67a2eb553f034aef41d55` / `e444744c03978c1fb4e037168967020cf2648427` |
| libvpx / SVT-AV1 / dav1d | `1024874c5919305883187e2953de8fcb4c3d7fa6` / `c04f951541ad600e0d9c10836f2ab7b9bc69816d` / `b546257f770768b2c88258c533da38b91a06f737` |
| AAR SHA-256 | `bdc30cc6f8be9be926b866ac48d24655b03e0145c10dd2dad711a46c28417220` |
| Lab APK SHA-256 | `16bed85e008aad272a33a78b94a8854d99340fe2dafccf524e0d32b021e3d82d` |

The build enables GPL, x264, x265, libvpx, SVT-AV1, dav1d, Android MediaCodec, Android zlib and WebP. `tools/patch_ffmpeg_static_cxx.py` temporarily uses the NDK static C++ runtime for the C++ codecs and disables x265 DOTPROD/I8MM instructions on arm64. The pinned wrapper files were restored after building. Native AAR and APK verification passed for arm64; `zipalign -c -P 16 -v 4` passed the APK.

The original `SoftwareVideoCodecsDeviceTest` made a one-second 128×96 H.264 source and exported the following files through Forma's bridge. Android's MediaExtractor confirmed the output codec, native FFmpeg decoded every output fully, and the app's Media3 player reached end of playback for each. The native AV1 decode check explicitly selected `libdav1d`. That run was **bridge smoke evidence only**: it bypassed the foreground service, durable queue completion and unmodified production decoder selection. Its Media3 player had no rendering surface, so `STATE_ENDED` did not establish a displayed frame.

| Selection | Output codec | Bytes | Duration |
| --- | --- | ---: | ---: |
| H.265 software (`libx265`) | `hevc` | 42,834 | 1,000 ms |
| VP9 software (`libvpx-vp9`) | `vp9` | 35,928 | 1,000 ms |
| AV1 software (`libsvtav1`) | `av1` | 71,223 | 1,000 ms |

The original x265 build crashed in I8MM instructions on this phone. The pinned wrapper compiled I8MM without Android runtime CPU detection. The revised arm64 native build disables DOTPROD and I8MM while retaining baseline ARM NEON. This restriction is arm64-only; x86 builds retain their native instruction set.

## Production-path follow-up

The revised opt-in test retains the direct bridge smoke, and adds a separate service export for each software family. Each service job starts from a two-second H.264/AAC original, retains 250–1750 ms at an explicit 24 fps, and must reach `COMPLETED` in the persisted queue before its output is accepted. It asserts the exact software encoder reported by the production attempt, H.265/AAC or WebM/Opus when `libopus` is packaged, output codec and track count, 128×96 geometry, 36 decoded frames, retained duration, and the unchanged original SHA-256. If `libopus` is absent, the two WebM jobs are deliberately video-only and the report records `opusPackaged: false`; WebM audio remains unqualified. Both smoke and service outputs must pass the ordinary production decoder selection, `inspectStreams(countFrames = true)`, and the same stream-completeness contract as the exporter. Playback requires an owned surface, a selected video track and named decoder, a rendered-first-frame callback, and an error-free end state.

Run only on an idle isolated `dev.forma.transcode.lab` installation with matching source-built app and test APKs. The test does not install or modify the normal app:

```bash
adb -s "$SERIAL" shell am instrument -w -r \
  -e class dev.forma.app.SoftwareVideoCodecsDeviceTest \
  -e formaSoftwareCodecs true \
  dev.forma.transcode.lab.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$SERIAL" shell run-as dev.forma.transcode.lab ls files/native-readiness/software-codecs-*.json
```

Each invocation writes fresh `software-codecs-smoke-<UUID>.json` and `software-codecs-service-<UUID>.json` reports under the lab package's private `files/native-readiness` directory. Require `OK (2 tests)` and `status: PASS` in both reports; a compilation pass alone is not device qualification. The follow-up device run, its installed APK hashes, and its observed decoder and audio results remain to be recorded after integration with the current base.

A physical 16 KB page-size device and long-form encode throughput remain unqualified. GPL and codec source-distribution obligations still apply before a public binary release.
