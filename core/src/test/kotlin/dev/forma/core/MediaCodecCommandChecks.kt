package dev.forma.core

fun mediaCodecCommandChecks() {
    val r = EncodeRequest(VideoFormat.H264, 1280, 720, 30.0, 2_000_000)
    val c = CodecCandidate("c2.vendor.avc.encoder", r.format, true, Support.YES, Support.YES, BufferFormat.YUV420P, "advertised", r)
    val d = AccelerationPolicy.choose(r, AccelerationMode.HARDWARE_REQUIRED, setOf(r.format.device), listOf(c))
    val old = listOf("-i", "/tmp/source with spaces.mp4", "-c:v", "h264_mediacodec", "-b:v", "2000k", "-vf", "scale=-2:720", "-pix_fmt", "yuv420p", "-f", "mp4", "/tmp/result.mp4")
    val result = MediaCodecCommand.bind(old, r, d)
    check(old.last() == result.last())
    check(result.count { it == "-c:v" } == 1)
    check(result.count { it == "-b:v" } == 1)
    check(result.count { it == "-pix_fmt" } == 1)
    check(result.windowed(2).any { it == listOf("-i", "/tmp/source with spaces.mp4") })
    check(result.windowed(2).any { it == listOf("-vf", "scale=-2:720") })
    check(result.windowed(2).any { it == listOf("-codec_name:v", c.name) })
    for (bad in listOf("-crf", "-preset", "-hwaccel", "-codec_name:v")) {
        var rejected = false
        try { MediaCodecCommand.bind(old.dropLast(1) + listOf(bad, "x", old.last()), r, d) }
        catch (_: IllegalArgumentException) { rejected = true }
        check(rejected) { "Conflicting option $bad must be rejected" }
    }
    check(MediaCodecCommand.dimensions(1920, 1080, 720) == (1280 to 720))
    check(MediaCodecCommand.dimensions(1080, 1920, 720) == (406 to 720))
    check(MediaCodecCommand.dimensions(640, 360, 1080) == (640 to 360))
    check(MediaCodecCommand.dimensions(641, 361, 0) == (640 to 360))
    var staleRejected = false
    try { d.videoOptions(r.copy(width = 640)) } catch (_: IllegalArgumentException) { staleRejected = true }
    check(staleRejected) { "A decision for a different request must not be reused" }
    println("16 MediaCodec command checks passed")
}
