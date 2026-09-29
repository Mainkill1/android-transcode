package dev.forma.core

/** Encoder policy only. Decode, filters and neural inference are separate stages. */
enum class AccelerationMode { AUTO, SOFTWARE_ONLY, HARDWARE_REQUIRED }
enum class Support { YES, NO, UNKNOWN }
enum class EncodeBackend { SOFTWARE, MEDIACODEC, UNAVAILABLE }
enum class ProcessingBackend { CPU, NONE }
enum class DeviceBitrateMode(val ffmpeg: String) { VBR("vbr"), CBR("cbr") }
enum class BufferFormat(val ffmpeg: String) { YUV420P("yuv420p"), NV12("nv12") }
enum class VideoFormat(val mime: String, val software: String, val device: String) {
    H264("video/avc", "libx264", "h264_mediacodec"),
    HEVC("video/hevc", "libx265", "hevc_mediacodec"),
    VP9("video/x-vnd.on2.vp9", "libvpx-vp9", "vp9_mediacodec"),
    AV1("video/av01", "libsvtav1", "av1_mediacodec")
}

data class EncodeRequest(
    val format: VideoFormat,
    val width: Int,
    val height: Int,
    val fps: Double,
    val bitrate: Int,
    val constantQuality: Boolean = false,
    val bitDepth: Int = 8,
    val hdr: Boolean = false,
    val rotationDegrees: Int = 0
) {
    init {
        require(width in 2..16384 && height in 2..16384 && width % 2 == 0 && height % 2 == 0) { "Even output dimensions are required." }
        require(fps.isFinite() && fps > 0 && fps <= 240) { "A known, finite output frame rate is required." }
        require(bitrate in 1..1_000_000_000) { "A positive bounded bitrate is required." }
        require(bitDepth in 8..16) { "Invalid bit depth." }
    }
}

/** Advertised support for one exact request, NOT proof of a successful device export. */
data class CodecCandidate(
    val name: String,
    val format: VideoFormat,
    val encoder: Boolean,
    val hardware: Support,
    val configuration: Support,
    val bufferFormat: BufferFormat?,
    val reason: String,
    val request: EncodeRequest,
    val performanceHint: CodecPerformanceHint? = null,
    val platformRank: Int = Int.MAX_VALUE
) {
    init {
        require(name.isNotBlank() && '\u0000' !in name) { "Invalid codec component name." }
        require(platformRank >= 0) { "Invalid platform codec rank." }
    }
}

data class EncodeDecision(
    val backend: EncodeBackend,
    val encoder: String?,
    val codecName: String? = null,
    val bufferFormat: BufferFormat? = null,
    val reason: String,
    val deviceQualified: Boolean = false,
    val decoder: ProcessingBackend = ProcessingBackend.CPU,
    val filters: ProcessingBackend = ProcessingBackend.CPU,
    val npu: ProcessingBackend = ProcessingBackend.NONE,
    val configuration: EncodeRequest? = null,
    val bitrateMode: DeviceBitrateMode = DeviceBitrateMode.VBR,
    val hardwareSupport: Support = Support.UNKNOWN
) {
    /** Append only to a validated bitrate plan with no existing video encoder/quality flags. */
    fun videoOptions(request: EncodeRequest): List<String> {
        require(backend == EncodeBackend.MEDIACODEC && !request.constantQuality && configuration == request)
        require(encoder == request.format.device && !codecName.isNullOrBlank() && bufferFormat != null)
        return listOf("-c:v", encoder, "-codec_name:v", codecName, "-bitrate_mode:v", bitrateMode.ffmpeg,
            "-b:v", request.bitrate.toString(), "-bf:v", "0", "-pix_fmt", bufferFormat.ffmpeg)
    }
}

enum class FailureKind { CODEC_INITIALIZATION, CANCELLED, IO, INVALID_INPUT, OVERSIZE, UNKNOWN }

object AccelerationPolicy {
    fun choose(request: EncodeRequest, mode: AccelerationMode, compiledEncoders: Set<String>,
               candidates: List<CodecCandidate>): EncodeDecision {
        fun software(reason: String): EncodeDecision = if (request.format.software in compiledEncoders)
            EncodeDecision(EncodeBackend.SOFTWARE, request.format.software, reason = reason)
        else EncodeDecision(EncodeBackend.UNAVAILABLE, null, reason = "$reason Software encoder is not compiled.")
        fun rejected(reason: String): EncodeDecision = if (mode == AccelerationMode.HARDWARE_REQUIRED)
            EncodeDecision(EncodeBackend.UNAVAILABLE, null, reason = reason) else software(reason)
        if (request.hdr || request.bitDepth != 8)
            return EncodeDecision(EncodeBackend.UNAVAILABLE, null, reason = "HDR/high-bit-depth needs a separately qualified color pipeline.")
        if (mode == AccelerationMode.SOFTWARE_ONLY) return software("Software explicitly selected.")
        if (request.constantQuality) return rejected("CRF is not a portable MediaCodec quality scale.")
        if (request.rotationDegrees % 360 != 0) return rejected("Autorotation geometry is not qualified for this device route.")
        if (request.format.device !in compiledEncoders) return rejected("The FFmpeg MediaCodec encoder wrapper is not compiled.")
        val candidate = candidates.asSequence().filter {
            it.request == request && it.format == request.format && it.encoder && it.hardware == Support.YES &&
                it.configuration == Support.YES && it.bufferFormat != null
        }.sortedWith(CodecRanking.preference).firstOrNull()
            ?: return rejected("No Android hardware encoder advertises the exact size, rate, bitrate and buffer format.")
        return EncodeDecision(EncodeBackend.MEDIACODEC, request.format.device, candidate.name,
            candidate.bufferFormat, "Advertised-compatible hardware encode; output verification and device qualification still required.",
            configuration = request, hardwareSupport = candidate.hardware)
    }

    /** Executor must classify errors structurally; never treat every FFmpeg failure as a codec failure. */
    fun mayRetryInSoftware(mode: AccelerationMode, failure: FailureKind): Boolean =
        mode == AccelerationMode.AUTO && failure == FailureKind.CODEC_INITIALIZATION
}
