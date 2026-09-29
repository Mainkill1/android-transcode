package dev.forma.core

/** Pure policy checks. No emulator or FFmpeg binary is implied by these tests. */
fun accelerationChecks() {
    var checks = 0
    fun expect(condition: Boolean, message: String) { check(condition) { message }; checks++ }
    fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true }
        expect(rejected, "Expected invalid request to be rejected")
    }
    val request = EncodeRequest(VideoFormat.H264, 1280, 720, 30.0, 2_000_000)
    val candidate = CodecCandidate("c2.vendor.avc.encoder", VideoFormat.H264, true, Support.YES,
        Support.YES, BufferFormat.YUV420P, "Device advertises this exact configuration", request)
    val compiled = setOf("libx264", "h264_mediacodec")
    fun choose(mode: AccelerationMode = AccelerationMode.AUTO, r: EncodeRequest = request,
               c: List<CodecCandidate> = listOf(candidate), enc: Set<String> = compiled) =
        AccelerationPolicy.choose(r, mode, enc, c)
    expect(choose().backend == EncodeBackend.MEDIACODEC, "Auto should select compatible hardware")
    expect(choose().codecName == candidate.name, "Select the exact Android component")
    expect(!choose().deviceQualified, "An advertised codec is not a tested codec")
    expect(choose().decoder == ProcessingBackend.CPU, "Hardware encode is not hardware decode")
    expect(choose().filters == ProcessingBackend.CPU, "Hardware encode is not GPU filtering")
    expect(choose().npu == ProcessingBackend.NONE, "Do not invent an NPU encoder")
    expect(choose(AccelerationMode.SOFTWARE_ONLY).encoder == "libx264", "CPU mode is honored")
    expect(choose(c = emptyList()).backend == EncodeBackend.SOFTWARE, "Missing hardware falls back")
    expect(choose(enc = setOf("libx264")).backend == EncodeBackend.SOFTWARE, "Wrapper must be compiled")
    expect(choose(c = listOf(candidate.copy(encoder = false))).backend == EncodeBackend.SOFTWARE, "Decoder is not encoder")
    expect(choose(c = listOf(candidate.copy(hardware = Support.UNKNOWN))).backend == EncodeBackend.SOFTWARE, "Unknown is not hardware proof")
    expect(choose(c = listOf(candidate.copy(hardware = Support.NO))).backend == EncodeBackend.SOFTWARE, "Reject software MediaCodec")
    expect(choose(c = listOf(candidate.copy(configuration = Support.NO))).backend == EncodeBackend.SOFTWARE, "Reject unsupported geometry")
    expect(choose(c = listOf(candidate.copy(configuration = Support.UNKNOWN))).backend == EncodeBackend.SOFTWARE, "Do not guess configuration")
    expect(choose(c = listOf(candidate.copy(bufferFormat = null))).backend == EncodeBackend.SOFTWARE, "Surface-only is not buffer input")
    expect(choose(c = listOf(candidate.copy(format = VideoFormat.HEVC))).backend == EncodeBackend.SOFTWARE, "Do not change requested codec")
    expect(choose(r = request.copy(constantQuality = true)).backend == EncodeBackend.SOFTWARE, "CRF stays on software")
    expect(choose(r = request.copy(rotationDegrees = 90)).backend == EncodeBackend.SOFTWARE, "Do not guess autorotated geometry")
    expect(choose(r = request.copy(bitDepth = 10)).backend == EncodeBackend.UNAVAILABLE, "No unqualified 10-bit fallback")
    expect(choose(r = request.copy(hdr = true)).backend == EncodeBackend.UNAVAILABLE, "HDR requires explicit pipeline")
    expect(choose(AccelerationMode.HARDWARE_REQUIRED, c = emptyList()).backend == EncodeBackend.UNAVAILABLE, "Forced hardware must not silently fall back")
    expect(choose(AccelerationMode.HARDWARE_REQUIRED, r = request.copy(constantQuality = true)).backend == EncodeBackend.UNAVAILABLE, "No hardware CRF")
    expect(choose(enc = emptySet()).backend == EncodeBackend.UNAVAILABLE, "No pretend encoder")
    val reversed = listOf(candidate.copy(name = "z.encoder"), candidate.copy(name = "a.encoder"))
    expect(choose(c = reversed).codecName == "a.encoder", "Stable ordering")
    val semi = choose(c = listOf(candidate.copy(bufferFormat = BufferFormat.NV12)))
    expect(semi.bufferFormat == BufferFormat.NV12, "Keep selected buffer representation")
    val args = choose().videoOptions(request)
    expect(args.windowed(2).any { it == listOf("-codec_name:v", candidate.name) }, "Pin actual encoder component")
    expect(args.windowed(2).any { it == listOf("-bitrate_mode:v", "vbr") }, "Use supported bitrate mode")
    expect(args.windowed(2).any { it == listOf("-bf:v", "0") }, "No unqualified B-frame timing")
    expect("-crf" !in args && "-preset" !in args, "No software-only options in MediaCodec route")
    expect("-hwaccel" !in args, "Do not advertise hardware decode")
    expect(semi.videoOptions(request).windowed(2).any { it == listOf("-pix_fmt", "nv12") }, "Match pixel format")
    rejects { request.copy(width = 0) }
    rejects { request.copy(width = 1279) }
    rejects { request.copy(height = -2) }
    rejects { request.copy(fps = Double.NaN) }
    rejects { request.copy(fps = Double.POSITIVE_INFINITY) }
    rejects { request.copy(fps = 0.0) }
    rejects { request.copy(bitrate = 0) }
    rejects { candidate.copy(name = "bad\u0000name") }
    expect(AccelerationPolicy.mayRetryInSoftware(AccelerationMode.AUTO, FailureKind.CODEC_INITIALIZATION), "Auto initialization fallback")
    for (failure in listOf(FailureKind.CANCELLED, FailureKind.IO, FailureKind.INVALID_INPUT, FailureKind.UNKNOWN, FailureKind.OVERSIZE))
        expect(!AccelerationPolicy.mayRetryInSoftware(AccelerationMode.AUTO, failure), "Do not misclassify $failure")
    expect(!AccelerationPolicy.mayRetryInSoftware(AccelerationMode.HARDWARE_REQUIRED, FailureKind.CODEC_INITIALIZATION), "Forced hardware stays forced")
    println("$checks acceleration policy checks passed")
}

fun main() { accelerationChecks(); mediaCodecCommandChecks() }
