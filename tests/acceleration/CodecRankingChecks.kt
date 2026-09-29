package dev.forma.core

/** Standalone host regression checks; never included in the application. */
fun main() {
    var checks = 0
    fun test(name: String, body: () -> Unit) {
        try { body(); checks++; println("PASS $name") }
        catch (error: Throwable) { throw AssertionError("FAIL $name: ${error.message}", error) }
    }
    val request = EncodeRequest(VideoFormat.H264, 1280, 720, 30.0, 2_000_000)
    val compiled = setOf("libx264", "h264_mediacodec")
    fun candidate(name: String, lower: Double? = null, upper: Double? = lower) = CodecCandidate(
        name, request.format, true, Support.YES, Support.YES, BufferFormat.YUV420P,
        "test fixture", request, if (lower != null && upper != null) CodecPerformanceHint(lower, upper) else null)
    fun choose(vararg candidates: CodecCandidate, mode: AccelerationMode = AccelerationMode.AUTO,
               req: EncodeRequest = request, encoders: Set<String> = compiled) =
        AccelerationPolicy.choose(req, mode, encoders, candidates.toList())
    val slow = candidate("a.slow", 30.0, 60.0)
    val fast = candidate("z.fast", 90.0, 120.0)

    test("performance beats alphabetical ordering") { check(choose(slow, fast).codecName == "z.fast") }
    test("input order does not change the result") { check(choose(fast, slow).codecName == "z.fast") }
    test("conservative lower estimate outranks burst upper estimate") {
        check(choose(candidate("a.burst", 20.0, 1000.0), fast).codecName == "z.fast")
    }
    test("upper estimate breaks equal lower estimates") {
        check(choose(candidate("a", 90.0, 100.0), fast).codecName == "z.fast")
    }
    test("name is a deterministic final tiebreak") {
        check(choose(candidate("z", 50.0), candidate("a", 50.0)).codecName == "a")
    }
    test("missing hints retain deterministic fallback") {
        check(choose(candidate("z"), candidate("a")).codecName == "a")
    }
    test("missing estimates preserve OEM preference instead of alphabetical ordering") {
        check(choose(candidate("a").copy(platformRank = 1), candidate("z").copy(platformRank = 0)).codecName == "z")
    }
    test("equal estimates preserve OEM preference") {
        check(choose(candidate("a", 60.0).copy(platformRank = 1), candidate("z", 60.0).copy(platformRank = 0)).codecName == "z")
    }
    test("invalid platform rank is rejected") {
        check(runCatching { candidate("a").copy(platformRank = -1) }.isFailure)
    }
    test("usable hint is preferred provisionally over unknown speed") {
        check(choose(candidate("a.unknown"), fast).codecName == "z.fast")
    }
    test("performance does not imply device qualification or accelerated other stages") {
        val decision = choose(fast)
        check(!decision.deviceQualified && decision.decoder == ProcessingBackend.CPU &&
            decision.filters == ProcessingBackend.CPU && decision.npu == ProcessingBackend.NONE)
    }
    test("low advertised speed is not an availability veto") {
        check(choose(candidate("slow", 1.0)).backend == EncodeBackend.MEDIACODEC)
    }
    test("software only stays software") {
        check(choose(fast, mode = AccelerationMode.SOFTWARE_ONLY).backend == EncodeBackend.SOFTWARE)
    }
    test("decoder cannot win encoder selection") {
        check(choose(slow, fast.copy(encoder = false)).codecName == "a.slow")
    }
    test("software component cannot win hardware selection") {
        check(choose(slow, fast.copy(hardware = Support.NO)).codecName == "a.slow")
    }
    test("unknown hardware is not silently promoted") {
        check(choose(fast.copy(hardware = Support.UNKNOWN), mode = AccelerationMode.HARDWARE_REQUIRED)
            .backend == EncodeBackend.UNAVAILABLE)
    }
    test("failed configuration cannot win") {
        check(choose(slow, fast.copy(configuration = Support.NO)).codecName == "a.slow")
    }
    test("surface only is not accepted as byte buffer") {
        check(choose(slow, fast.copy(bufferFormat = null)).codecName == "a.slow")
    }
    test("stale bitrate request cannot reuse hint or eligibility") {
        check(choose(slow, fast.copy(request = request.copy(bitrate = 3_000_000))).codecName == "a.slow")
    }
    test("stale dimensions cannot reuse hint or eligibility") {
        check(choose(slow, fast.copy(request = request.copy(width = 1920, height = 1080))).codecName == "a.slow")
    }
    test("wrong codec family cannot win") {
        check(choose(slow, fast.copy(format = VideoFormat.AV1)).codecName == "a.slow")
    }
    test("missing native wrapper cannot be rescued by Android capability") {
        check(choose(fast, encoders = setOf("libx264")).backend == EncodeBackend.SOFTWARE)
    }
    test("required hardware fails when wrapper missing") {
        check(choose(fast, mode = AccelerationMode.HARDWARE_REQUIRED, encoders = setOf("libx264"))
            .backend == EncodeBackend.UNAVAILABLE)
    }
    test("CRF does not become hardware rate control") {
        check(choose(fast, req = request.copy(constantQuality = true)).backend == EncodeBackend.SOFTWARE)
    }
    test("HDR remains gated") {
        check(choose(fast, req = request.copy(hdr = true)).backend == EncodeBackend.UNAVAILABLE)
    }
    test("high bit depth remains gated") {
        check(choose(fast, req = request.copy(bitDepth = 10)).backend == EncodeBackend.UNAVAILABLE)
    }
    test("unqualified autorotation remains gated") {
        check(choose(fast, req = request.copy(rotationDegrees = 90)).backend == EncodeBackend.SOFTWARE)
    }
    test("valid hints preserve precision") {
        check(CodecPerformanceHint.from(29.97, 59.94) == CodecPerformanceHint(29.97, 59.94))
    }
    test("invalid OEM estimates are unknown") {
        for ((lower, upper) in listOf(0.0 to 1.0, -1.0 to 2.0, 50.0 to 20.0,
            Double.NaN to 1.0, 1.0 to Double.NaN, 1.0 to Double.POSITIVE_INFINITY)) {
            check(CodecPerformanceHint.from(lower, upper) == null)
        }
    }
    test("invalid hint constructor cannot bypass validation") {
        check(runCatching { CodecPerformanceHint(20.0, 1.0) }.isFailure)
    }
    test("selection preserves exact component and request in command") {
        val decision = choose(fast)
        val options = decision.videoOptions(request)
        check(options[options.indexOf("-codec_name:v") + 1] == "z.fast")
        check(options[options.indexOf("-b:v") + 1] == "2000000")
        check("-crf" !in options && "-preset" !in options && "cbr_fd" !in options)
        check(runCatching { decision.videoOptions(request.copy(bitrate = 1_000_000)) }.isFailure)
    }
    test("only codec initialization allows software retry") {
        for (failure in FailureKind.values()) {
            check(AccelerationPolicy.mayRetryInSoftware(AccelerationMode.AUTO, failure) ==
                (failure == FailureKind.CODEC_INITIALIZATION))
            check(!AccelerationPolicy.mayRetryInSoftware(AccelerationMode.HARDWARE_REQUIRED, failure))
        }
    }
    println("$checks acceleration ranking checks passed.")
}
