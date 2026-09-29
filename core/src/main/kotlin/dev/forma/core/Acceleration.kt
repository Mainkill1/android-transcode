package dev.forma.core

/** Encoder policy only. Decode, filters and neural inference are separate stages. */
enum class AccelerationMode { AUTO, SOFTWARE_ONLY, HARDWARE_REQUIRED }
enum class Support { YES, NO, UNKNOWN }
enum class EncodeBackend { SOFTWARE, MEDIACODEC, UNAVAILABLE }
enum class ProcessingBackend { CPU, NONE }
enum class BufferFormat(val ffmpeg: String) { YUV420P("yuv420p"), NV12("nv12") }
/** Only modes that do not explicitly permit dropping frames. Neither mode guarantees output bytes. */
enum class CodecBitrateMode(val ffmpeg: String) { VBR("vbr"), CBR("cbr") }
data class BufferConfiguration(val format: BufferFormat, val bitrateMode: CodecBitrateMode)

object EncoderConfigurations {
    /** A supported layout and rate-control mode do not imply their combination is supported. */
    fun firstSupported(formats: Set<BufferFormat>, modes: Set<CodecBitrateMode>,
                       supports: (BufferConfiguration) -> Boolean): BufferConfiguration? {
        for (mode in CodecBitrateMode.values()) {
            for (format in BufferFormat.values()) {
                if (format in formats && mode in modes) {
                    val configuration = BufferConfiguration(format, mode)
                    if (supports(configuration)) return configuration
                }
            }
        }
        return null
    }
}

enum class VideoFormat(val mime: String, val software: String, val device: String) {
    H264("video/avc", "libx264", "h264_mediacodec"),
    HEVC("video/hevc", "libx265", "hevc_mediacodec"),
    VP9("video/x-vnd.on2.vp9", "libvpx-vp9", "vp9_mediacodec"),
    AV1("video/av01", "libsvtav1", "av1_mediacodec"),
    VP8("video/x-vnd.on2.vp8", "libvpx", "vp8_mediacodec")
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
    val bitrateMode: CodecBitrateMode = CodecBitrateMode.VBR
) {
    init { require(name.isNotBlank() && '\u0000' !in name) { "Invalid codec component name." } }
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
    val bitrateMode: CodecBitrateMode = CodecBitrateMode.VBR
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
        }.firstOrNull() // Caller preserves MediaCodecList OEM preference; names are not a speed ranking.
            ?: return rejected("No Android hardware encoder advertises the exact size, rate, bitrate and buffer format.")
        return EncodeDecision(EncodeBackend.MEDIACODEC, request.format.device, candidate.name,
            candidate.bufferFormat, "Advertised-compatible hardware encode; output verification and device qualification still required.",
            configuration = request, bitrateMode = candidate.bitrateMode)
    }

    /** Executor must classify errors structurally; never treat every FFmpeg failure as a codec failure. */
    fun mayRetryInSoftware(mode: AccelerationMode, failure: FailureKind): Boolean =
        mode == AccelerationMode.AUTO && failure == FailureKind.CODEC_INITIALIZATION
}
