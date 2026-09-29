package dev.forma.core

/** The same checks run under Gradle/JUnit and the dependency-free host runner. */
object CoreChecks {
    private val source = Source("content://media/12", "A file's name.mp4", 60_000, 1920, 1080, 1, 2)
    private val defaults = Settings()
    private fun args(s: Settings = defaults, t: Trim = Trim()) =
        Planner.arguments(source, t, s, "/private/input ' one.mp4", "/private/output two.mp4")
    private fun rejects(s: Settings = defaults, t: Trim = Trim(), src: Source = source) =
        check(Planner.validate(src, t, s).isNotEmpty())
    private fun fails(block: () -> Unit) { check(runCatching(block).isFailure) }

    val cases: List<Pair<String, () -> Unit>> = listOf(
        "nv12 is eight bit, not twelve bit" to { check(!ColorRules.needsQualifiedPipeline("nv12", "bt709")) },
        "nv21 is eight bit" to { check(!ColorRules.needsQualifiedPipeline("nv21", "bt709")) },
        "planar ten bit is held" to { check(ColorRules.needsQualifiedPipeline("yuv420p10le", "bt709")) },
        "p010 is held" to { check(ColorRules.needsQualifiedPipeline("p010le", "bt709")) },
        "pq transfer is held" to { check(ColorRules.needsQualifiedPipeline("yuv420p", "smpte2084")) },
        "explicit bit depth is held" to { check(ColorRules.needsQualifiedPipeline("unknown", "bt709", 12)) },
        "reject inverted trim" to { rejects(t = Trim(20_000, 10_000)) },
        "accept default video plan" to { check(Planner.validate(source, Trim(), defaults).isEmpty()) },
        "reject negative trim" to { rejects(t = Trim(-1)) },
        "reject trim after EOF" to { rejects(t = Trim(0, 61_000)) },
        "reject zero duration" to { rejects(src = source.copy(durationMs = 0)) },
        "reject missing video" to { rejects(src = source.copy(videoTracks = 0)) },
        "reject audio only without audio" to { rejects(Planner.preset(Goal.AUDIO, Quality.BALANCED), src = source.copy(audioTracks = 0)) },
        "reject out of range audio track" to { rejects(defaults.copy(audioTrack = 2)) },
        "reject h264 in webm" to { rejects(defaults.copy(container = Container.WEBM)) },
        "accept vp9 opus in webm" to { check(Planner.validate(source, Trim(), defaults.copy(container = Container.WEBM, video = VideoEncoder.VP9, audio = AudioEncoder.OPUS)).isEmpty()) },
        "reject hardware CRF" to { rejects(defaults.copy(video = VideoEncoder.H264_HW)) },
        "reject odd height" to { rejects(defaults.copy(maxHeight = 721)) },
        "reject invalid bitrate" to { rejects(defaults.copy(videoKbps = 0)) },
        "reject HDR until real color pipeline exists" to { rejects(src = source.copy(hdr = true)) },
        "missing runtime blocks execution" to { check(Planner.validate(source, Trim(), defaults, Capabilities()).isNotEmpty()) },
        "missing encoder is not inferred from decoder" to { check(Planner.validate(source, Trim(), defaults, Capabilities(true, "", setOf("aac"), setOf("mp4"), setOf("scale"))).isNotEmpty()) },
        "explicit frame grid requires fps capability" to {
            val caps=Capabilities(true,"",setOf("libx264","aac"),setOf("mp4"),setOf("scale"))
            check(Planner.validate(source,Trim(),defaults.copy(fps=30),caps).any { "fps" in it })
        },
        "presets do not carry trim or source" to { val edit = SourceEdit(source, Trim(1000, 5000)); Planner.preset(Goal.SMALLER, Quality.SMALL); check(edit.trim.startMs == 1000L) },
        "advanced mode does not change settings" to { val e = Editor(defaults.copy(crf = 18), custom = true); check(e.copy(advanced = true).copy(advanced = false).settings == e.settings) },
        "queued configuration is a snapshot" to { var s = defaults; val j = JobSpec("1", source, Trim(), s); s = s.copy(crf = 12); check(j.settings.crf == 23 && s.crf == 12) },
        "arguments preserve spaces as one token" to { check(args().contains("/private/input ' one.mp4")) },
        "never overwrite with ffmpeg" to { check("-n" in args() && "-y" !in args()) },
        "reject identical input and output" to { fails { Planner.arguments(source, Trim(), defaults, "/same", "/same") } },
        "trim uses duration not absolute end" to { val a = args(t = Trim(10_000, 30_000)); check(a[a.indexOf("-t") + 1] == "20.000") },
        "no upscaling expression" to { check(args().any { "min(ih,1080)" in it }) },
        "map chosen audio track explicitly" to { check(args(defaults.copy(audioTrack = 1)).contains("0:a:1")) },
        "audio only disables video" to { check("-vn" in args(Planner.preset(Goal.AUDIO, Quality.CLEAR))) },
        "bitrate and CRF are mutually exclusive" to { val a = args(defaults.copy(rateControl = RateControl.BITRATE)); check("-crf" !in a && "-b:v" in a) },
        "progress is not completion" to { check(Progress(70_000).fraction(60_000) == 0.99f) },
        "unknown duration is indeterminate" to { check(Progress(1).fraction(0) == null) },
        "queued job cannot jump to complete" to { fails { QueueRules.transition(QueueEntry(JobSpec("1", source, Trim(), defaults)), JobState.COMPLETED) } },
        "only verifying can complete" to { val e = QueueEntry(JobSpec("1", source, Trim(), defaults), JobState.VERIFYING); check(QueueRules.transition(e, JobState.COMPLETED).state == JobState.COMPLETED) },
        "process death is interrupted not successful" to { val e = QueueEntry(JobSpec("1", source, Trim(), defaults), JobState.RUNNING); check(QueueRules.recover(e).state == JobState.INTERRUPTED) },
        "recovery leaves finished results alone" to { val e = QueueEntry(JobSpec("1", source, Trim(), defaults), JobState.COMPLETED); check(QueueRules.recover(e) == e) }
    )
    fun runAll() { cases.forEach { (name, test) -> try { test() } catch (e: Throwable) { throw AssertionError(name, e) } } }
}
fun main() {
    CoreChecks.cases.forEach { (name, test) -> test(); println("PASS: $name") }
    println("${CoreChecks.cases.size} core checks passed")
}
