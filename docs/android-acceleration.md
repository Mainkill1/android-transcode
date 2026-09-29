# Android acceleration: staged implementation

## Practical first route

FFmpeg remains the mux/demux/filter/encode execution owner. Use Android
MediaCodec video encoder wrappers for the first hardware path:

```
staged original -> FFmpeg software decode -> CPU filters/scale
                -> selected Android MediaCodec encoder -> FFmpeg mux -> verification
```

This is **hardware encoding**, not a GPU-only or zero-copy export. The core
`AccelerationPolicy` deliberately reports CPU decode, CPU filters and no NPU.
Software frames must be transferred to the codec. Benchmark those copies and
filters, not just the codec callback. FFmpeg exposes H.264/HEVC/VP8/VP9/MPEG-4/AV1
wrappers, but device encoder availability varies; an AV1 decoder is not proof of
an AV1 encoder. Existing native UI integration in this preparation covers explicit
H.264/H.265 device selections only. [1]

`AndroidCodecCatalog` enumerates regular components and separates encoders from
decoders. API 29+ hardware flags are vendor reports, not measured proof. API 26–28
hardware status is UNKNOWN; CPU fallback is the initial policy, not a guess based
on `OMX.*` or `c2.*` names. Exact requests check width/height alignment, supported
size/rate, bitrate range, VBR support and raw YUV420 buffer representation. Flexible
YUV/surface-only capability is not treated as proof for a planar-buffer path. [2]

`AUTO` can select an advertised-compatible hardware candidate; `SOFTWARE_ONLY`
never selects hardware; `HARDWARE_REQUIRED` fails explicitly if unavailable.
Constant-quality/CRF requests stay software. A stale capability result must not
be reused after retry dimensions, bitrate or frame rate change. The decision is
bound to the full `EncodeRequest`. Output checking and a successful short smoke
test do not automatically qualify all resolutions/profiles on that phone.

Device options are encoder-specific: explicit `-codec_name:v`, `-bitrate_mode:v
vbr`, `-b:v`, `-bf:v 0` and the supported raw pixel format. Never forward x264
`-preset` or CRF to MediaCodec. Do not use `cbr_fd` for a workflow that must preserve
all selected frames. Keep timestamps, mux headers and actual frame duration under
test; unsupported configurations are not resolved by widening tolerances. [1]

The current app calls `prepare` before explicit hardware execution; automatic UI
selection, persisted preference, device quarantine and fallback executor are next
agent work. `mayRetryInSoftware` is a policy helper, not a shipped retry loop.
A codec-init failure may justify a bounded Auto retry; cancellation, disk errors,
invalid input and generic nonzero exits do not. Oversized output is handled by
the size-fit controller, not a codec-failure detector.

## Hardware decoding and GPU transforms

Second phase: qualify MediaCodec decode independently on selected media types.
Do not add `-hwaccel auto` to every command and call the whole pipeline accelerated.
FFmpeg's documentation notes that transfer overhead can eliminate the expected
decode benefit. Keep the original CPU path for filters requiring software frames. [3]

A later surface route can connect a decoder surface, bounded GL/Vulkan transform
resources and an encoder input surface. Crop/scale/rotate/color conversion could
then avoid CPU frame copies, but this is a separate ownership/synchronization
implementation. FFmpeg MediaCodec opaque frames do not make arbitrary CPU
`avfilter` graphs work without readback. Prototype exact timestamps, surfaces,
fences, format changes, EOS, cancellation and mux extradata before enabling it.
Keep FFmpeg as export owner; a Media3 preview must not silently become a different
export engine. The original upstream zero-copy discussion explicitly notes
filter limitations. [4]

Do not build an automatic HDR-to-SDR path as an incidental performance change.
Preserve HDR correctly or report that a qualified color pipeline is required.
Measure color transforms and validate metadata rather than hiding incompatibility
behind a fast encoder. Use frame timestamps for VFR; average fps is not sufficient
proof for frame-preserving conversion.

## NPU: useful possibilities, not a codec replacement

An NPU runs supported neural model operations. It is not FFmpeg's H.264/H.265/AV1
entropy encoder and is not enabled by a universal `-hwaccel npu` option. The
practical candidate work is optional **pre-encode denoising** or **sparse
scene/complexity analysis** to improve compression decisions. Audio denoising is
another possible model-specific feature. Treat resulting quality/size/speed
benefits as hypotheses to measure, not guarantees.

Super-resolution and frame interpolation add processing, pixels or frames and
may work against the upload-size goal. Do not enable them by default. Automatic
crop/subject tracking alters framing and requires an explicit editing feature,
not a silent compressor optimization.

Current primary runtime direction: LiteRT `CompiledModel` with vendor dispatch,
including Qualcomm AI Engine Direct, MediaTek NeuroPilot, Tensor and Exynos
support documented by Google. Runtime compatibility, operators, tensor layout,
quantization and vendor libraries remain model/device-specific. NNAPI is
deprecated from Android 15; do not start a new NNAPI backend. [5][6]

No LiteRT/QNN dependency or model is bundled in this preparation. The optional
provider should be its own module so base API 26 installs and offline exports do
not depend on NPU runtime delivery. Current LiteRT NPU deployment examples require
API 31+ and arm64; gate the provider accordingly, and verify the chosen runtime's
actual requirements before adding it. Google lists LiteRT 2.2.0 in the currently
retrieved Android runtime table; pin a reviewed version and all vendor/model
artifacts rather than using `+` or fetching code from arbitrary mirrors. [5][7]

Do not report NPU use merely because initialization succeeded: partial delegation
can run unsupported operations on CPU/GPU. Report actual delegated backend and
profile timings. Use AOT/JIT model caches keyed by model SHA-256, runtime, vendor
backend, SoC/OS build and tensor shapes. Measure cold compilation separately. [5]

Suggested experiment, after Android size fitting works:
1. Pick one small licensed denoising model and a noise-heavy short source set.
2. Compare CPU-only export against CPU/GPU/NPU prefilter plus the same encoder.
3. Measure total wall time, memory, thermal behavior, real output bytes, quality
   and NPU delegated work, including YUV/RGB and tensor transfers.
4. Enable the provider only for explicit enhancement jobs with a proven benefit;
   offer plain FFmpeg export when unavailable. Never silently omit requested
   denoising or fall back while displaying an NPU badge.

## Primary references (checked 2026-09-28)

[1] https://ffmpeg.org/ffmpeg-codecs.html#MediaCodec

[2] https://developer.android.com/reference/android/media/MediaCodecInfo and https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities and https://developer.android.com/reference/android/media/MediaCodecInfo.EncoderCapabilities

[3] https://ffmpeg.org/ffmpeg.html#Advanced-Video-options

[4] https://ffmpeg.org/pipermail/ffmpeg-devel/2022-November/303968.html (historical design context, not proof of current arbitrary-filter support)

[5] https://developers.google.com/edge/litert/next/npu

[6] https://developer.android.com/ndk/guides/neuralnetworks/migration-guide

[7] https://developers.google.com/edge/litert/android
