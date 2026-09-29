package dev.forma.core

object RuntimeCodecChecks {
    fun run() {
        val request = EncodeRequest(VideoFormat.H264, 1280, 720, 30.0, 2_000_000)
        val encoders = setOf("libx264", "h264_mediacodec")
        val candidate = CodecCandidate("c2.vendor.encoder", request.format, true, Support.YES,
            Support.NO, null, "OEM says no", request)
        fun plan(mode: AccelerationMode = AccelerationMode.AUTO,
                 candidates: List<CodecCandidate> = listOf(candidate), r: EncodeRequest = request,
                 compiled: Set<String> = encoders) = CodecTrials.plan(r, mode, compiled, candidates)
        check(plan().first().backend == EncodeBackend.MEDIACODEC) { "An advertised NO must still be tried" }
        check(plan().last().backend == EncodeBackend.SOFTWARE)
        check(plan().size == 5) { "Two layouts x two frame-preserving rate modes, then CPU" }
        check(plan().take(4).map { it.bufferFormat to it.bitrateMode }.distinct().size == 4)
        check(plan().take(4).all { !it.deviceQualified && it.configuration == request })
        check(plan().first().videoOptions(request).contains("nv12"))
        check(plan().none { it.videoOptionsOrEmpty(request).contains("cbr_fd") })
        check(plan(AccelerationMode.HARDWARE_REQUIRED).all { it.backend == EncodeBackend.MEDIACODEC })
        check(plan(AccelerationMode.SOFTWARE_ONLY).single().backend == EncodeBackend.SOFTWARE)
        check(plan(candidates = listOf(candidate.copy(hardware = Support.UNKNOWN))).first().hardwareSupport == Support.UNKNOWN)
        val platformFirst = candidate.copy(name = "z.platform-first", hardware = Support.UNKNOWN,
            configuration = Support.UNKNOWN, platformRank = 0)
        val platformSecond = candidate.copy(name = "a.platform-second", hardware = Support.UNKNOWN,
            configuration = Support.UNKNOWN, platformRank = 1)
        check(plan(candidates = listOf(platformSecond, platformFirst)).first().codecName == platformFirst.name) {
            "Preserve Android's component order when capability queries do not provide stronger evidence"
        }
        check(plan(candidates = listOf(candidate.copy(hardware = Support.NO))).single().backend == EncodeBackend.SOFTWARE)
        check(plan(candidates = listOf(candidate.copy(encoder = false))).single().backend == EncodeBackend.SOFTWARE)
        check(plan(candidates = listOf(candidate.copy(request = request.copy(width = 640)))).single().backend == EncodeBackend.SOFTWARE)
        check(plan(candidates = listOf(candidate.copy(format = VideoFormat.HEVC))).single().backend == EncodeBackend.SOFTWARE)
        check(plan(compiled = setOf("libx264")).single().backend == EncodeBackend.SOFTWARE)
        check(plan(compiled = emptySet()).isEmpty())
        check(plan(AccelerationMode.HARDWARE_REQUIRED, candidates = emptyList()).isEmpty())
        check(plan(r = request.copy(hdr = true)).isEmpty())
        check(plan(r = request.copy(bitDepth = 10)).isEmpty())
        check(plan(r = request.copy(constantQuality = true)).single().backend == EncodeBackend.SOFTWARE)
        check(plan(r = request.copy(rotationDegrees = 90)).single().backend == EncodeBackend.SOFTWARE)
        check(plan(AccelerationMode.HARDWARE_REQUIRED, r = request.copy(constantQuality = true)).isEmpty())
        val many = (0..20).map { candidate.copy(name = "component-$it") }
        check(plan(candidates = many).size == 13)
        check(plan(candidates = many).take(3).map { it.codecName }.distinct().size == 3) { "Try components fairly before variants" }
        check(plan(candidates = listOf(candidate, candidate)).size == 5)
        check(plan(candidates = listOf(candidate.copy(bufferFormat = BufferFormat.YUV420P))).first().bufferFormat == BufferFormat.YUV420P)
        for (format in VideoFormat.values()) {
            val r = request.copy(format = format)
            val c = candidate.copy(format = format, request = r)
            check(CodecTrials.plan(r, AccelerationMode.HARDWARE_REQUIRED, setOf(format.device), listOf(c)).first().encoder == format.device)
        }
        fun classify(vararg lines: String, started: Boolean = false): FailureKind {
            val evidence = CodecFailureEvidence("h264_mediacodec")
            lines.forEach(evidence::observe)
            return evidence.failure(started)
        }
        val codecFailure = "[h264_mediacodec @ 0x123] MediaCodec configure failed, external error"
        check(classify(codecFailure) == FailureKind.CODEC_INITIALIZATION)
        check(classify("[vost#0:0/h264_mediacodec @ 0x123] MediaCodec configure failed, external error") == FailureKind.CODEC_INITIALIZATION)
        check(classify(codecFailure, started = true) == FailureKind.UNKNOWN)
        check(classify("configure failed") == FailureKind.UNKNOWN)
        check(classify("[hevc_mediacodec @ 123] MediaCodec configure failed") == FailureKind.UNKNOWN)
        check(classify("[h264_mediacodec @ 123] MediaCodec failed to start, external error") == FailureKind.CODEC_INITIALIZATION)
        check(classify("[h264_mediacodec @ 123] Failed to create encoder for type video/avc") == FailureKind.CODEC_INITIALIZATION)
        check(classify(codecFailure, "No space left on device") == FailureKind.IO)
        check(classify(codecFailure, "Permission denied") == FailureKind.IO)
        check(classify(codecFailure, "Invalid data found when processing input") == FailureKind.INVALID_INPUT)
        check(classify(codecFailure, "Cannot allocate memory") == FailureKind.UNKNOWN)
        check(classify(codecFailure, "[Parsed_scale_0 @ 123] Failed to configure output pad") == FailureKind.UNKNOWN)
        check(classify("/source/[h264_mediacodec @ fake] MediaCodec configure failed") == FailureKind.UNKNOWN)
        println("Runtime codec planner and failure evidence checks passed")
    }
    private fun EncodeDecision.videoOptionsOrEmpty(r: EncodeRequest) =
        if (backend == EncodeBackend.MEDIACODEC) videoOptions(r) else emptyList()
}
fun main() = RuntimeCodecChecks.run()
