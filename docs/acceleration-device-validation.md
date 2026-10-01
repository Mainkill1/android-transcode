# Acceleration qualification and remaining blockers

This checkpoint builds the actual pinned, source-built FFmpeg n9.0.1 bundle on the target **OnePlus 9 Pro LE2125**, API 36, arm64, 4096-byte pages. It preserves the native source/licensing pins. The optional graphicsPathRepo selects a source-rebuilt 16 KB aligned Compose dependency. `-PformaLab=true` gives debug qualification its own `dev.forma.transcode.lab` package; release keeps the normal package.

## Observed

- Host: 17 JUnit tests, no failures/skips; 20 encoder selection, 58 configuration, 31 lab assertions, 14 Python report checks and real production-transcoder decode/cancellation checks.
- Matching native debug/app-test builds and lint passed. Minified native release passed with `testing/acceleration` physically absent and `accelerationTests=false`; release dex excludes the lab/diagnostics/runner/command. Real debug/release payload and 16 KB ELF/ZIP alignment checks passed. These are packaging checks, not a 16 KB runtime claim.
- Physical ordinary suite: 8 passed, one deliberate lab opt-in skip. Native H.264 encode and full decode ran.
- Five separate ABBA comparisons passed: H.264 Java vs software; H.264 NDK vs Java; H.264 NDK async vs Java; H.264 hardware decode-to-buffer vs Java; HEVC NDK async vs Java. Every comparison produced four independently decoded 90-frame, 3-second, 640×360/30 outputs, with monotonic PTS. Effective encoders were `c2.qti.avc.encoder` / `c2.qti.hevc.encoder`; observed buffer decoder was `c2.qti.avc.decoder`, cross-checked against Android hardware flags.
- Two important instrumentation fixes were verified: async-to-sync native fallback is rejected; decoder identity comes from the pinned native *successful-start* message, rather than an obsolete selection message. A Java/Java HEVC invocation was correctly rejected as an invalid comparison; valid HEVC Java baseline was exercised by the async ABBA comparison.
- Production output now fully decodes with strict error handling before publication. Decode failure and cancellation regressions prevent completed/partial publication and wait for native teardown.

Exact artifact SHA-256:

```text
debug    3e62ccd7fb62b2067ebca9a029d770133aec4c0aa379cb3f446ab9329863272b
test     8790eb62f1f3015a40f472bcec34b810b9cf6eae1e5d5885fe144d200bdc43dc
release  a1fcc1d5578340f99e2f55ca3c7ac8c0294c929bd0a84bf2fd0de5d69df48ec8
```

## Blockers

The required independent-vendor/OnePlus 15 qualification cannot run on the attached OnePlus 9 Pro. Both ADB transport names identify the same device. No MediaTek, Exynos, Tensor or OnePlus 15 phone is attached. Hardware availability is not simulated by an emulator or a vendor-name flag.

The requested SURFACE experiment also fails on this pinned native artifact: FFmpeg reports `Setting BufferSourceContext.pix_fmt to a HW format requires hw_frames_ctx to be non-NULL!` before any encoded frame. The graph already initializes and selects a MediaCodec hardware device and output pixel format; a real decoder surface and `c2.qti.avc.decoder` start are observed. Removing user edits, claiming zero-copy, or treating the empty output as success would not fix this. A separately qualified native hardware-frame/surface adapter is needed before this experiment is promoted.

Keep this PR Draft and marked blocked. Other supported conditional routes are exercised above; unavailable VP8/VP9/AV1 encode hardware and missing software/native codecs are not asserted to work. Short fixture timing is not equal-quality speedup, sustained thermal/energy, real-media A/V sync or broad-device certification. Runtime fallback/byte-cap orchestration belongs to the separate PR6 production implementation; GPU transforms and NPU effects remain research backlog.
