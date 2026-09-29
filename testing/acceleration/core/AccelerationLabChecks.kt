package dev.forma.accelerationlab

import dev.forma.core.*

fun accelerationLabChecks() {
    var count = 0
    fun expect(ok: Boolean, why: String) { check(ok) { why }; count++ }
    fun rejects(why: String, block: () -> Unit) = expect(runCatching(block).isFailure, why)
    val request = EncodeRequest(VideoFormat.H264, 1280, 720, 30.0, 2_000_000)
    val fixture = LabFixture(1280, 720, 30, 3)
    val c = CodecCandidate("vendor.encoder", request.format, true, Support.YES, Support.YES,
        BufferFormat.NV12, "test", request)
    val d = AccelerationPolicy.choose(request, AccelerationMode.HARDWARE_REQUIRED, setOf(request.format.device), listOf(c))
    val source = Source("fixture", "fixture.mp4", 3000, 1280, 720, 1, 1)
    val settings = Settings(video = VideoEncoder.H264_HW, rateControl = RateControl.BITRATE, maxHeight = 720, fps = 30)
    val prepared = MediaCodecCommand.bind(Planner.arguments(source, Trim(), settings, "input file.mp4", "output file.mp4"), request, d)
    val supported = setOf("ndk_codec", "ndk_async", "codec_name", "bitrate_mode", "operating_rate")
    fun variant(mode: LabMode, options: Set<String> = supported, rate: Int = 0) =
        LabCommands.variant(prepared, mode, request, fixture, options, rate)
    val java = variant(LabMode.JAVA)
    expect(java.windowed(2).any { it == listOf("-ndk_codec:v", "0") }, "Java selection explicit")
    val ndk = variant(LabMode.NDK)
    expect(ndk.windowed(2).any { it == listOf("-ndk_codec:v", "1") }, "NDK selection explicit")
    expect(ndk.contains("scale=-2:'trunc(min(ih,720)/2)*2'"), "Buffer route retains CPU filters")
    val async = variant(LabMode.NDK_ASYNC)
    expect(async.windowed(2).any { it == listOf("-ndk_async:v", "1") }, "Async option enabled")
    expect(async.windowed(2).any { it == listOf("-bsf:v", "extract_extradata") }, "Async mux extradata caveat handled")
    val decode = variant(LabMode.DECODE_BUFFER)
    expect(decode.take(decode.indexOf("-i")).windowed(2).any { it == listOf("-c:v", "h264_mediacodec") }, "Hardware decode is an input option")
    expect("-hwaccel_output_format" !in decode && "-vf" in decode, "Buffer decode is not an opaque surface graph")
    expect(decode.take(decode.indexOf("-i")).none { it.startsWith("-codec_name") }, "Do not invent a decoder codec_name option")
    val surface = variant(LabMode.SURFACE)
    expect("-vf" !in surface && "-r" !in surface, "Only the identity fixture can remove CPU transforms and CFR conversion")
    expect(surface.windowed(2).any { it == listOf("-hwaccel_output_format", "mediacodec") }, "Opaque frames explicitly requested")
    expect(surface.windowed(2).any { it == listOf("-pix_fmt", "mediacodec") }, "Surface pixels are not raw YUV")
    expect(surface.last() == "output file.mp4", "Preserve output token")
    expect(variant(LabMode.NDK, rate = 240).windowed(2).any { it == listOf("-operating_rate:v", "240") }, "Operating rate is separate from output fps")
    rejects("Missing async option is not silent synchronous success") { variant(LabMode.NDK_ASYNC, supported - "ndk_async") }
    rejects("Missing NDK option fails") { variant(LabMode.NDK, emptySet()) }
    rejects("Unsupported operating rate fails") { variant(LabMode.NDK, supported - "operating_rate", 240) }
    rejects("No arbitrary surface filter stripping") {
        LabCommands.variant(prepared.map { if (it.startsWith("scale=")) "hqdn3d,$it" else it }, LabMode.SURFACE, request, fixture, supported)
    }
    rejects("No resize erased to fit surface route") { LabCommands.variant(prepared, LabMode.SURFACE, request.copy(width = 640), fixture, supported) }
    rejects("No HDR fixture") { LabCommands.variant(prepared, LabMode.SURFACE, request.copy(hdr = true), fixture, supported) }
    rejects("No async mux workaround guessed for AV1") { LabCommands.variant(prepared, LabMode.NDK_ASYNC, request.copy(format = VideoFormat.AV1), fixture, supported) }
    val pts = (0 until 90).map { it / 30.0 }
    FrameSequence.verify(pts, fixture)
    count++
    rejects("Frame loss detected") { FrameSequence.verify(pts.dropLast(1), fixture) }
    rejects("Duplicate PTS detected") { FrameSequence.verify(pts.toMutableList().apply { this[3] = this[2] }, fixture) }
    rejects("Bad timestamps detected") { FrameSequence.verify(pts.toMutableList().apply { this[20] = Double.NaN }, fixture) }
    rejects("Playback rate change detected") { FrameSequence.verify(pts.map { it * 2 }, fixture) }
    expect(LabCommands.optionNames("  -ndk_codec <boolean> E..\n -ndk_async <boolean> E..").containsAll(setOf("ndk_codec", "ndk_async")), "Runtime help parsing")
    println("$count acceleration lab checks passed")
}
