package dev.forma.core

/** Dependency-free regression checks; shared with the separate Gradle test source set. */
fun hardwareAccelerationChecks() {
    var count = 0
    fun expect(value: Boolean, message: String) { check(value) { message }; count++ }
    val source = Source("fixture", "fixture.mp4", 3000, 1280, 720, 1, 1)
    for (name in listOf("VP8_HW", "VP9_HW", "AV1_HW")) {
        val encoder = VideoEncoder.values().firstOrNull { it.name == name }
        expect(encoder != null, "$name must be available as an explicit hardware selection")
        val settings = Settings(container = Container.WEBM, video = encoder!!,
            rateControl = RateControl.BITRATE, audio = AudioEncoder.OPUS, fps = 30)
        expect(Planner.validate(source, Trim(), settings).isEmpty(), "$name must be valid in WebM")
        val args = Planner.arguments(source, Trim(), settings, "input.mp4", "output.webm")
        expect(args.windowed(2).any { it == listOf("-c:v", encoder.ffmpeg) }, "Bind $name wrapper")
        expect("-crf" !in args && "-preset" !in args && "-cpu-used" !in args, "No software options for $name")
        expect(Planner.validate(source, Trim(), settings.copy(rateControl = RateControl.QUALITY)).isNotEmpty(), "No hardware CRF")
        expect(Planner.validate(source, Trim(), settings, Capabilities(true, "", emptySet(), setOf("webm"), setOf("scale"))).isNotEmpty(), "Missing compiled wrapper must fail")
    }
    expect(Planner.validate(source, Trim(), Settings(video = VideoEncoder.valueOf("VP8_HW"),
        rateControl = RateControl.BITRATE)).isNotEmpty(), "VP8 must not be sent to this MP4 profile")
    expect(VideoFormat.values().any { it.device == "vp8_mediacodec" }, "VP8 must have a matching MIME mapping")
    println("$count hardware encoder selection checks passed")
}

fun main() { hardwareAccelerationChecks(); encoderConfigurationChecks(); dev.forma.accelerationlab.accelerationLabChecks() }
