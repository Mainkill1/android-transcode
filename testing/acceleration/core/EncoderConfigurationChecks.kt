package dev.forma.core

fun encoderConfigurationChecks() {
    var count = 0
    fun expect(value: Boolean, message: String) { check(value) { message }; count++ }
    val bothFormats = BufferFormat.values().toSet()
    val bothModes = CodecBitrateMode.values().toSet()
    val calls = mutableListOf<BufferConfiguration>()
    val selected = EncoderConfigurations.firstSupported(bothFormats, bothModes) {
        calls += it
        it == BufferConfiguration(BufferFormat.NV12, CodecBitrateMode.VBR)
    }
    expect(selected == BufferConfiguration(BufferFormat.NV12, CodecBitrateMode.VBR), "Try a second exact layout instead of giving up")
    expect(calls == listOf(BufferConfiguration(BufferFormat.YUV420P, CodecBitrateMode.VBR), selected), "VBR/layout preference is deterministic")
    val cbr = EncoderConfigurations.firstSupported(bothFormats, setOf(CodecBitrateMode.CBR)) { true }
    expect(cbr == BufferConfiguration(BufferFormat.YUV420P, CodecBitrateMode.CBR), "CBR-only encoders are usable without frame dropping")
    expect(EncoderConfigurations.firstSupported(emptySet(), bothModes) { error("must not query") } == null, "Surface/flexible-only is not raw buffer support")
    expect(EncoderConfigurations.firstSupported(bothFormats, emptySet()) { error("must not query") } == null, "No invented rate control")
    expect(EncoderConfigurations.firstSupported(bothFormats, bothModes) { false } == null, "All rejected exact formats remain unavailable")
    expect(bothModes.map { it.ffmpeg }.toSet() == setOf("vbr", "cbr"), "Never select cq or cbr_fd")
    for (format in VideoFormat.values()) {
        val r = EncodeRequest(format, 1280, 720, 30.0, 2_000_000)
        val c = CodecCandidate("vendor.encoder", format, true, Support.YES, Support.YES,
            BufferFormat.NV12, "advertised", r, CodecBitrateMode.CBR)
        val d = AccelerationPolicy.choose(r, AccelerationMode.HARDWARE_REQUIRED, setOf(format.device), listOf(c))
        expect(d.backend == EncodeBackend.MEDIACODEC, "Each requested codec remains supported")
        expect(d.bitrateMode == CodecBitrateMode.CBR, "Carry the exact selected mode")
        expect(d.videoOptions(r).windowed(2).any { it == listOf("-bitrate_mode:v", "cbr") }, "Bind CBR, not a VBR hardcode")
        expect(!d.deviceQualified && d.decoder == ProcessingBackend.CPU && d.filters == ProcessingBackend.CPU, "No invented acceleration or qualification")
        for (changed in listOf(r.copy(bitrate = 1_000_000), r.copy(width = 640), r.copy(fps = 60.0))) {
            expect(runCatching { d.videoOptions(changed) }.isFailure, "Reject stale requests after retry")
        }
        for (bad in listOf(c.copy(hardware = Support.UNKNOWN), c.copy(encoder = false), c.copy(configuration = Support.NO))) {
            expect(AccelerationPolicy.choose(r, AccelerationMode.HARDWARE_REQUIRED, setOf(format.device), listOf(bad)).backend == EncodeBackend.UNAVAILABLE, "Do not confuse a decoder or advertised wrapper with a valid encoder")
        }
    }
    val request = EncodeRequest(VideoFormat.H264, 1280, 720, 30.0, 2_000_000)
    val preferred = CodecCandidate("z.preferred", request.format, true, Support.YES, Support.YES,
        BufferFormat.YUV420P, "advertised", request)
    val chosen = AccelerationPolicy.choose(request, AccelerationMode.HARDWARE_REQUIRED,
        setOf(request.format.device), listOf(preferred, preferred.copy(name = "a.secondary")))
    expect(chosen.codecName == preferred.name, "Preserve Android preferred order, not alphabetical component names")
    println("$count encoder configuration checks passed")
}
