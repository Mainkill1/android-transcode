package dev.forma.core

object EncoderChoiceChecks {
    fun run() {
        val source = Source("content://source", "source", 60_000, 1920, 1080, 1, 1)
        val auto = VideoEncoder.valueOf("H264_AUTO")
        val settings = Settings(video = auto, rateControl = RateControl.BITRATE, fps = 30)
        fun valid(s: Settings, enc: Set<String>) = Planner.validate(source, Trim(), s,
            Capabilities(true, "", enc + "aac", setOf("mp4", "webm"), setOf("scale"))).isEmpty()
        check(valid(settings, setOf("h264_mediacodec"))) { "Auto works even when only hardware is compiled" }
        check(valid(settings, setOf("libx264"))) { "Auto works when only CPU is compiled" }
        check(!valid(settings, emptySet()))
        check(Settings().video == VideoEncoder.X264) { "Do not reinterpret old default queues" }
        check(VideoEncoder.valueOf("H264_HW").hardware)
        for (name in listOf("VP9_HW", "AV1_HW")) {
            val video = VideoEncoder.valueOf(name)
            val s = settings.copy(container = Container.WEBM, video = video, audio = AudioEncoder.NONE)
            check(valid(s, setOf(video.ffmpeg)))
            check(Planner.validate(source, Trim(), s.copy(rateControl = RateControl.QUALITY)).isNotEmpty())
        }
        val fallback = settings.copy(video = VideoEncoder.X264)
        val args = Planner.arguments(source, Trim(1_000, 31_000), fallback, "/original", "/output")
        check(args[args.indexOf("-t") + 1] == "30.000" && "2000k" !in args)
        check("-preset" in args && "-crf" !in args && "-fs" !in args)
        println("Encoder choice, compiled backend and legacy-setting checks passed")
    }
}
fun main() = EncoderChoiceChecks.run()
