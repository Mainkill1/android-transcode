# Android hardware acceleration: investment and implementation

Research date: **2026-09-29**. Baseline: `89ceb3b362872f193a54feacc99225c80e77f176`.
This is an implementation draft, not a measured fastest-phone leaderboard. Native
Android compilation, packaged FFmpeg libraries and physical-phone runs were not
available in the authoring environment. Do not promote a route from these host checks.

## Decision

Invest first in Android's **dedicated video codec engines through MediaCodec**,
then remove unnecessary frame transfers. Java and NDK are interfaces to the same
underlying codec services, not separate GPU/NPU encoders. The best export route
must be measured for a complete job, including decode, transforms, transfers,
encode, audio, mux and size retries. A camera's advertised recording rate is not
an offline export benchmark. FFmpeg remains the export owner. [1][2][3]

The fastest operation is avoiding a transcode when the source already satisfies
the exact output contract. Stream copy is only eligible when container, streams,
byte limit, trim accuracy, metadata and edits permit it; it cannot shrink an
oversized video by magic. It is a separate native planner milestone, not enabled
by this PR. For real re-encoding, compare these paths rather than enabling every
accelerator at once:

| Priority | Path | This PR | Promotion requirement |
| --- | --- | --- | --- |
| P0 | FFmpeg CPU decode/filter → MediaCodec buffer encode | Production selection broadened to H.264, HEVC, VP8, VP9 and AV1; actual components/configuration required | Native build plus per-family phone output tests |
| P0 | More complete configuration negotiation | Both planar and semi-planar YUV420; VBR first, non-dropping CBR fallback; OEM preferred component order | Real configure/encode/decode, not only advertised support |
| P1 | NDK synchronous encoding | Explicit, test-APK-only comparison against Java | Same codec/job; measured total benefit and lifecycle correctness |
| P1 | NDK asynchronous encoding | Test-only H.264/HEVC route with `extract_extradata` | Correct mux headers, output decode, cancellation and sustained timing |
| P1 | Hardware decode → software frames → CPU filters → hardware encode | H.264-source ADB experiment | Actual hardware decoder observed; transfers must not erase the gain |
| P1 | Persistent surface decode → encode without CPU filters | Identity-fixture ADB experiment | Surface observed, exact frames/PTS/audio, real-device qualification |
| P1 | GPU surface transforms between decoder and encoder | Detailed next implementation below; **not implemented** | GPU correctness, ownership, synchronization and FFmpeg integration |
| P2 | Operating-rate hints | Bounded test-only hint, runtime-option checked | Throughput gain without unacceptable heat or frame loss |
| P2 | Measured route cache, bounded Auto retry and quarantine | Design requirement; **not implemented** | Exact job/build identity and structural failure classification |
| P3 | NPU denoise/analysis | Optional separate enhancement work | Real quality/size/end-to-end benefit; never advertised as an H.264 encoder |

FFmpeg documents hardware wrappers for the five selected codecs, but compiling a
wrapper does not create a phone encoder. Its NDK async option reduces polling
overhead but has an extradata/global-header caveat; hence an experiment rather
than an unconditional default. VBR/CBR requests do not guarantee final bytes.
`cbr_fd`, hardware CRF and silent frame loss are not acceptable here. [1]

## What changed in production

`VideoEncoder` now exposes conditional VP8/VP9/AV1 device selections alongside
H.264/HEVC. Existing enum names, persisted settings and defaults are unchanged.
The existing advanced selector enumerates these entries; it still distinguishes
compiled wrappers from per-file device support. WebM accepts VP8/VP9/AV1 hardware
video with Opus or no audio. VP8 in the current MP4 profile is explicitly rejected.

`AndroidCodecCatalog` checks complete format/bitrate-mode combinations rather
than abandoning an encoder after the first raw layout fails. Surface-only and
flexible-YUV-only advertisements are not proof of raw buffer compatibility.
Its diagnostics include raw layouts, non-dropping modes, advertised encoder
surface input, OEM rank, instance limit and per-entry query errors. The instance
limit is diagnostic, **not a command to launch parallel encoders**.

`AccelerationPolicy` preserves the catalog's order instead of sorting codec names
alphabetically. Android specifically recommends its preferred component order.
Supported frame-rate ceilings are coding limits, not speed rankings; achievable
rate estimates are device/build-specific and can change with load and thermal
conditions. Do not rank different phones from those values. [2]

All five device selections still pass through `FfmpegBridge.prepare`, after
staging/probing. The concrete encoder, complete request, pixel layout and selected
rate-control mode are bound into tokenized FFmpeg arguments. HDR, high bit depth,
rotation/display matrices, missing wrappers, unknown hardware status and CRF
remain held as before. Explicit hardware selection does not silently use software.
This PR does **not** finish the native byte-cap fitting controller or editor PR #2.

## Which phones deserve coverage

This is a test allocation recommendation, not a chipset allow-list or a claim of
installed-base market share. Q2 2026 sell-through data puts Galaxy S26 Ultra first
among Android models while the Galaxy A07/A17 family also appears prominently.
Regional data and prior-year LATAM sales additionally justify Motorola G and
Redmi coverage. Quarter sales are not the same as all actively used phones. [4][5]

| Coverage bucket | Suggested devices/platforms | What it protects against |
| --- | --- | --- |
| High-volume entry/midrange | Galaxy A07/A16/A17 variants, Moto G, Redmi; include Helio/Dimensity, Snapdragon and an available Unisoc device | Limited encoder formats, memory, alignment, thermal headroom and software-only MediaCodec entries |
| Mainstream performance | Snapdragon 6/7/older 8 devices and Dimensity 7/8-series or POCO-class devices | Realistic scale/filter costs and heterogeneous vendor drivers |
| Qualcomm high end | User's OnePlus 15, another 8 Elite-generation OEM, Galaxy flagship variants where applicable | NDK vs Java, high resolution, surface negotiation and driver differences |
| MediaTek high end | Dimensity 9300/9400/9500 and newly announced 9600 Pro/9600M hardware as obtainable | Independent codec implementation and decode/encode sharing |
| Samsung Exynos | A current Exynos phone, including an Exynos 2600 model when available for testing | Do not assume Samsung model names identify the same SoC worldwide |
| Google Tensor | Older Pixel plus Pixel 10/G5 and Pixel 11/G6 | Separate codec implementation, OS updates and camera-source metadata |
| Compatibility boundary | API 26–28; modern API 29+; arm64; 4 KB and 16 KB devices | Old hardware identity APIs, native packaging and OS-specific behavior |

OnePlus's specification identifies the OnePlus 15 as Snapdragon **8 Elite Gen 5**.
Do not relabel it as Gen 6. Qualcomm announced **8 Elite Extreme Gen 6** and
**8 Elite Gen 6** on September 22, 2026; include them as new qualification targets,
not as already benchmarked app support. [6][7]

MediaTek now lists **Dimensity 9600 Pro** and **9600M**. The 9600 Pro specification
separates AVC/HEVC encoding from VVC/HEVC/AVC/VP9/AV1 decoding. Its H.266/VVC
**decoder** does not justify adding a VVC encoder selection. Similarly AV1 playback
is not AV1 encode capability. Conditional discovery is the right way to exploit
an actual AV1 encoder wherever an OEM exposes one. [8][9]

Google introduced Pixel 11 with Tensor G6 on August 12, 2026. Its AI/camera claims
are not proof of an app-accessible encoding configuration. Samsung's Exynos 2600
page highlights professional APV video; APV is not the default upload codec for
Forma's size-limited sharing workflow. Both still enter the same runtime inventory
and qualification process. [10][11]

## Surface/GPU follow-on: the highest-value unfinished work

For unedited, same-size media, FFmpeg's MediaCodec hardware context can create a
persistent input surface and share it with decoding/encoding. This PR adds a
bounded experiment using that mechanism. It does **not** claim that drivers never
copy internally, or that arbitrary FFmpeg CPU filters accept opaque frames. The
experiment intentionally rejects non-identity filters and trims instead of
silently discarding them. [3]

The present surface experiment starts with the same buffer-compatible encoder
used by the Java baseline. **Surface-only encoders are not covered yet.** A next
implementation needs a separate exact surface candidate query and an independent
baseline selection so it does not exclude otherwise useful surface-only hardware.

For actual editing, implement a focused optional native surface adapter:

1. FFmpeg demux and timestamp ownership feed a MediaCodec decoder. The decoder
   outputs to an owned SurfaceTexture/external texture, not CPU readback by default.
2. An EGL/OpenGL ES render stage applies scale, crop, rotation and later overlays.
   It renders into the encoder's EGL window surface. Use the original frame PTS,
   not wall-clock or display refresh rate. Media3's architecture is a useful
   reference for MediaCodec + OpenGL, not a replacement export engine. [12]
3. Surface lifetime, EOS, format changes and fences belong to one bounded worker
   and state machine. A decoder frame is released only after its consumer is done;
   cancellation waits for native termination before freeing surfaces or staging.
4. Return encoded packets and codec configuration to FFmpeg's mux path. Preserve
   audio selection and timestamps. Never run `hwdownload` implicitly while reporting
   a no-readback route. An unsupported selected effect chooses a correct existing
   route or fails explicitly, not an export that omits the effect.
5. Keep HDR/10-bit as a distinct color-managed milestone: transfer/range/matrix,
   chroma handling and metadata must be tested before advertising support.

OpenGL ES is the initial cross-vendor transform investment. Vulkan compute/Video
is an additional provider only after checking actual device extensions, surface
interop, native-build support and measured gains. A Vulkan checkbox alone supplies
neither an encoder nor a zero-copy FFmpeg graph.

## Work that is not worth making a default backend

Do not add desktop NVENC/CUDA, Intel QSV or Apple VideoToolbox switches to ordinary
Android export choices. Do not call an NPU inference delegate a standards-compliant
H.264/HEVC/AV1 encoder. NPU scene analysis or denoising can be useful separately,
but tensor conversion, model cost and quality must earn their place. No new model,
NPU SDK or vendor-private codec dependency is included here.

Keep CPU codecs as the correctness/compatibility path. Audit source-build SIMD
settings when profiling actually identifies software decode/filter cost; do not
replace quality with a fast preset and call that a like-for-like speed improvement.
Audio processing and muxing remain separate stages, not implied hardware benefits
from selecting a video encoder. Legacy MPEG-4 and professional APV are not initial
size-limited sharing priorities; camera capture is a different workload.

## Qualification and promotion contract

Use the [separate ADB lab](../testing/acceleration/README.md) for the first controlled
comparison. It runs ABBA, reports actual encoded bytes and decoded frames/PTS, binds
a fresh run ID, records the APK/source/output hashes and observes the decoder when
hardware decoding is requested. It fails instead of silently skipping missing
native support. It includes debug logging, fixture generation and only short,
synthetic SDR cases; it is **not** the final performance or picture-quality suite.

Before Auto promotion, add representative noisy/clean motion, portrait rotations,
long clips, real input codecs, VFR, seek/trim boundaries, supported editor effects,
audio sync, interrupted sessions, storage failures and sustained thermal runs.
Compare equivalent compatibility and quality under the same byte cap. A faster
file with worse picture quality, omitted frames or an oversized upload is a loss.
Keep the full selected duration and verify `0 < outputBytes < targetBytes`; never
use `-fs` to satisfy the cap by truncation.

An eventual measured route cache must key on OS fingerprint, APK/native build,
component, API path, codec/profile/bit depth, dimensions/rate, filter graph and
relevant job settings. Invalidate after updates or changed retry settings. Separate
advertised, smoke-passed, sustained-qualified and quarantined states. Retry Auto
only for structurally identified initialization failure, with bounded attempts
from the original input. Do not reinterpret cancellation, I/O, corrupt input or
oversize output as a hardware initialization failure.

## Primary sources

Checked 2026-09-29. Vendor ceilings are not Forma performance measurements.

1. [FFmpeg MediaCodec encoder options](https://ffmpeg.org/ffmpeg-codecs.html#MediaCodec)
2. [Android VideoCapabilities: preferred ordering, achievable rates and limits](https://developer.android.com/reference/android/media/MediaCodecInfo.VideoCapabilities)
3. [FFmpeg 8 MediaCodec hardware context](https://ffmpeg.org/doxygen/8.0/hwcontext__mediacodec_8c_source.html), [encoder](https://ffmpeg.org/doxygen/8.0/mediacodecenc_8c_source.html), [decoder](https://ffmpeg.org/doxygen/8.0/mediacodecdec__common_8c_source.html)
4. [Counterpoint Q2 2026 model sell-through](https://counterpointresearch.com/de/insights/iphone-17-global-best-selling-smartphone-in-q2-2026)
5. [Counterpoint LATAM model mix, 2025](https://counterpointresearch.com/en/insights/samsung-leads-latam-top10-Smartphones-list-with-4spots)
6. [OnePlus 15 specifications](https://www.oneplus.com/ca_en/15/specs)
7. [Qualcomm September 22, 2026 announcement](https://www.qualcomm.com/news/releases/2026/09/snapdragon-leads-the-agentic-ai-age-with-two-of-the-world-s-fast)
8. [MediaTek Dimensity 9600 Pro, separate encode/decode specifications](https://www.mediatek.com/products/smartphones/mediatek-dimensity-9600-pro)
9. [MediaTek Dimensity 9600M](https://www.mediatek.com/products/smartphones/dimensity-9600m)
10. [Google Pixel 11 / Tensor G6 announcement](https://blog.google/products-and-platforms/devices/pixel/google-pixel-11-pro-xl/)
11. [Samsung Exynos 2600](https://semiconductor.samsung.com/processor/mobile-processor/exynos-2600/)
12. [Android Media3 Transformer architecture](https://developer.android.com/media/media3/transformer)
