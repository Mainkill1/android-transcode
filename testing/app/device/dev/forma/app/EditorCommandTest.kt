package dev.forma.app

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.ClipEffectsCodec
import dev.forma.core.*
import dev.forma.testing.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Compiled into the separate instrumentation APK, never the product APK. */
@RunWith(AndroidJUnit4::class)
class EditorCommandTest {
    @Test fun executeCommand(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val command = args.getString("formaCommand")
        // Ordinary Compose suites do not implicitly request expensive native qualification.
        assumeTrue("ADB editor command not requested", command != null)
        require(command in setOf("capabilities", "smoke", "export", "movie-smoke")) { "Unknown formaCommand." }
        val runId = args.getString("formaRunId").orEmpty()
        require(Regex("[a-f0-9]{32}").matches(runId)) { "formaRunId must contain 32 lowercase hex characters." }
        val timeout = args.getString("formaTimeoutMs")?.toLongOrNull() ?: 290_000L
        require(timeout in 20_000L..3_590_000L) { "Invalid test timeout." }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        require(context.packageName == "dev.forma.transcode.lab") { "Use the isolated lab build; never instrument the normal installation for CLI tests." }
        val graph = (context.applicationContext as FormaApplication).graph
        val directory = File(context.filesDir, "forma-tests/$runId").apply { check(isDirectory || mkdirs()) }
        val reportFile = File(directory, "result.json")
        check(!reportFile.exists()) { "Use a fresh run ID; an earlier report already exists." }
        val results = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", runId).put("command", command)
            .put("status", "FAIL").put("results", results).put("package", context.packageName)
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList())).put("pageBytes", Os.sysconf(OsConstants._SC_PAGESIZE)))
        val started = SystemClock.elapsedRealtime()
        try {
            report.put("apkSha256", sha256(File(context.applicationInfo.sourceDir)))
            val done = CompletableDeferred<Unit>()
            val ticket = graph.runs.start {
                try {
                    graph.initialize()
                    val caps = graph.bridge.capabilities()
                    report.put("capabilities", JSONObject().put("available", caps.available).put("reason", caps.reason)
                        .put("build", caps.build).put("encoders", JSONArray(caps.encoders.sorted()))
                        .put("filters", JSONArray(caps.filters.sorted())).put("muxers", JSONArray(caps.muxers.sorted())))
                    if (command == "movie-smoke") {
                        check(caps.available) { "Native movie test requested without FFmpeg: ${caps.reason}" }
                        runMovieCases(graph, directory, results)
                    } else if (command != "capabilities") {
                        check(caps.available) { "Native test explicitly requested, but FFmpeg is unavailable: ${caps.reason}" }
                        val input = File(directory, "input.media")
                        val cases = if (command == "smoke") {
                            check(!input.exists()) { "Smoke tests generate their own source; use a fresh run ID." }
                            val generated = graph.bridge.execute(listOf("-hide_banner", "-loglevel", "error", "-nostdin", "-n",
                                "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30:duration=6",
                                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=6",
                                "-t", "6", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-f", "mp4", input.absolutePath)) {}
                            check(generated.exitCode == 0) { "Fixture generation failed: ${generated.diagnostics}" }
                            editorSmokeCases()
                        } else listOf(readRecipe(File(directory, "recipe.json")))
                        check(input.isFile && input.length() > 0) { "Upload a nonempty input.media for this run." }
                        val inputHash = sha256(input)
                        val source = graph.bridge.probe(input.absolutePath).copy(uri = Uri.fromFile(input).toString(), name = "input.media", bytes = input.length())
                        for (case in cases) {
                            currentCoroutineContext().ensureActive()
                            runCase(graph, source, input, inputHash, directory, case, results)
                        }
                    }
                    done.complete(Unit)
                } catch (error: Throwable) {
                    done.completeExceptionally(error)
                    if (error is CancellationException) throw error
                }
            } ?: error("The lab app is busy. Finish or cancel its current operation before testing.")
            var completed = false
            try { withTimeout(timeout) { done.await() }; completed = true }
            finally {
                // Native cancellation retains ownership until FFmpeg's callback returns.
                if (!completed && !ticket.job.isCompleted) graph.runs.stop(ticket.id)
                withContext(NonCancellable) { ticket.job.join() }
            }
            report.put("status", "PASS")
        } catch (error: Throwable) {
            report.put("error", "${error.javaClass.simpleName}: ${error.message.orEmpty().take(6000)}")
            throw error
        } finally {
            report.put("elapsedMs", SystemClock.elapsedRealtime() - started)
            val temporary = File(directory, "result.tmp")
            temporary.writeText(report.toString(2))
            check(temporary.renameTo(reportFile)) { "Could not publish the test report." }
        }
    }

    private suspend fun runCase(graph: AppGraph, source: Source, input: File, inputHash: String, directory: File, case: EditorCase, results: JSONArray) {
        val spec = JobSpec(UUID.randomUUID().toString(), source, case.trim, case.settings)
        var entry = QueueEntry(spec, JobState.PREPARING)
        val states = JSONArray()
        val begin = SystemClock.elapsedRealtime()
        var processedMs = 0L
        val result = JSONObject().put("name", case.name).put("sourceSha256", inputHash)
            .put("effects", ClipEffectsCodec.encode(case.settings.effects)).put("decoded", false)
            .put("job", JSONObject(dev.forma.app.data.JobCodec.encode(listOf(entry))))
        results.put(result)
        try {
            val tracing = object : dev.forma.ffmpeg.FfmpegBridge by graph.bridge {
                override suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> {
                    val tokens = graph.bridge.prepare(source, trim, settings, input, output)
                    result.put("preparedArgv", JSONArray(tokens))
                    return tokens
                }
            }
            // The production class, with a forwarding recorder rather than a second encoder path.
            dev.forma.app.data.FfmpegTranscoder(graph.files, tracing).run(spec, { state ->
                entry = QueueRules.transition(entry, state)
                states.put(JSONObject().put("state", state.name).put("elapsedMs", SystemClock.elapsedRealtime() - begin))
            }, { progress -> processedMs = maxOf(processedMs, progress.processedMs) })
            check(entry.state == JobState.COMPLETED) { "Production exporter did not complete." }
            val output = graph.files.output(spec)
            val probe = graph.bridge.probe(output.absolutePath)
            val duration = Planner.outputDuration(source, case.trim, case.settings)
            // Tight fixture checks supplement the production exporter's general media tolerance.
            check(abs(probe.durationMs - duration) <= 300) { "Edited duration differs by more than 300 ms." }
            case.width?.let { check(probe.width == it) { "Unexpected width ${probe.width}; expected $it." } }
            case.height?.let { check(probe.height == it) { "Unexpected height ${probe.height}; expected $it." } }
            val decoded = graph.bridge.execute(listOf("-hide_banner", "-loglevel", "error", "-nostdin", "-xerror", "-i", output.absolutePath,
                "-map", "0:v?", "-map", "0:a?", "-f", "null", "-")) {}
            check(decoded.exitCode == 0) { "Output decode failed: ${decoded.diagnostics}" }
            check(sha256(input) == inputHash) { "The source was modified." }
            val name = "${case.name}.${case.settings.container.extension}"
            output.copyTo(File(directory, name), overwrite = false)
            result.put("decoded", true).put("bytes", output.length()).put("outputFile", name).put("outputSha256", sha256(output))
                .put("expectedDurationMs", duration).put("durationMs", probe.durationMs).put("width", probe.width).put("height", probe.height)
                .put("videoTracks", probe.videoTracks).put("audioTracks", probe.audioTracks)
        } finally {
            result.put("elapsedMs", SystemClock.elapsedRealtime() - begin).put("states", states).put("maxProcessedMs", processedMs)
            // Only this test's UUID output. Never clear the app queue or an unrelated output.
            graph.files.output(spec).delete()
        }
    }

    private suspend fun runMovieCases(graph: AppGraph, directory: File, results: JSONArray) {
        val a = File(directory,"movie-a.mp4")
        val b = File(directory,"movie-b.mp4")
        val generatedA = graph.bridge.execute(listOf("-hide_banner","-loglevel","error","-nostdin","-n",
            "-f","lavfi","-i","color=red:size=640x360:rate=30:duration=3",
            "-itsoffset","0.300","-f","lavfi","-i","sine=frequency=440:sample_rate=48000:duration=3",
            "-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac","-f","mp4",a.absolutePath)) {}
        check(generatedA.exitCode == 0) { generatedA.diagnostics }
        val generatedB = graph.bridge.execute(listOf("-hide_banner","-loglevel","error","-nostdin","-n",
            "-f","lavfi","-i","color=blue:size=360x640:rate=25:duration=3",
            "-c:v","libx264","-pix_fmt","yuv420p","-an","-f","mp4",b.absolutePath)) {}
        check(generatedB.exitCode == 0) { generatedB.diagnostics }
        val c=File(directory,"movie-video-delay.mp4")
        val generatedC=graph.bridge.execute(listOf("-hide_banner","-loglevel","error","-nostdin","-n",
            "-itsoffset","0.300","-f","lavfi","-i","color=red:size=640x360:rate=30:duration=3",
            "-f","lavfi","-i","sine=frequency=440:sample_rate=48000:duration=3",
            "-c:v","libx264","-pix_fmt","yuv420p","-fps_mode","passthrough","-c:a","aac","-f","mp4",c.absolutePath)) {}
        check(generatedC.exitCode==0) { generatedC.diagnostics }
        val ah=sha256(a); val bh=sha256(b); val ch=sha256(c)
        val sa=graph.bridge.probe(a.absolutePath).copy(uri=Uri.fromFile(a).toString(),name=a.name,bytes=a.length())
        val sb=graph.bridge.probe(b.absolutePath).copy(uri=Uri.fromFile(b).toString(),name=b.name,bytes=b.length())
        val sc=graph.bridge.probe(c.absolutePath).copy(uri=Uri.fromFile(c).toString(),name=c.name,bytes=c.length())
        fun project(speed: Int = 100, transition: Long = 0, cap: Long? = null, audio: Boolean = false): MovieProject {
            val clips=listOf(TimelineClip("a",sa,Trim(0,2000),Settings(effects=ClipEffects(speedPercent=speed))),
                TimelineClip("b",sb,Trim(0,2000),Settings()))
            return MovieProject(sequence=SequenceSpec(EditTimeline(clips),CanvasSpec(320,180,30),transition),
                settings=Settings(container=if(audio) Container.M4A else Container.MP4),targetBytes=cap)
        }
        val cases=listOf("movie-cut" to project(),"movie-crossfade" to project(transition=500),
            "movie-speed" to project(speed=200),"movie-audio" to project(audio=true),
            "movie-budget" to project(cap=150000),"movie-preview" to project(),
            "movie-video-delay" to project().copy(sequence=project().sequence.copy(timeline=EditTimeline(listOf(TimelineClip("a",sc,Trim(0,2000)),TimelineClip("b",sb,Trim(0,2000)))))))
        for((name,doc) in cases) {
            val id=UUID.randomUUID().toString()
            val requested=if(name=="movie-preview") doc.previewJob(id) else doc.toJob(id)
            val saved=dev.forma.app.data.JobCodec.decode(dev.forma.app.data.JobCodec.encode(listOf(QueueEntry(requested)))).single().spec
            check(saved == requested) { "Movie changed across queue persistence." }
            val result=JSONObject().put("name",name).put("sourceSha256",if(name=="movie-video-delay") ch else ah).put("otherSourceSha256",bh)
                .put("job",JSONObject(dev.forma.app.data.JobCodec.encode(listOf(QueueEntry(saved)))))
            val attempts=JSONArray();result.put("attempts",attempts);results.put(result)
            val tracing=object : dev.forma.ffmpeg.FfmpegBridge by graph.bridge {
                override suspend fun prepareSequence(sequence: SequenceSpec,settings: Settings,inputs: List<String>,output: String): List<String> {
                    return graph.bridge.prepareSequence(sequence,settings,inputs,output).also { result.put("preparedArgv",JSONArray(it)) }
                }
            }
            var entry=QueueEntry(saved,JobState.PREPARING)
            try {
                dev.forma.app.data.FfmpegTranscoder(graph.files,tracing).run(saved,{entry=QueueRules.transition(entry,it)},{}, { attempt ->
                    attempts.put(JSONObject().put("index",attempt.index).put("bytes",attempt.bytes).put("verified",attempt.verified)
                        .put("accepted",attempt.accepted).put("arguments",JSONArray(attempt.arguments)))
                })
                check(entry.state==JobState.COMPLETED)
                val output=graph.files.output(saved)
                val facts=graph.bridge.probe(output.absolutePath)
                check(abs(facts.durationMs-JobPlans.duration(saved))<=100)
                saved.targetBytes?.let { check(UploadFit.fits(output.length(),it)) }
                if(saved.settings.container!=Container.M4A) {
                    val frames=graph.bridge.inspectStreams(output.absolutePath,countFrames=true).streams.single { it.kind==dev.forma.ffmpeg.StreamKind.VIDEO }.decodedFrames
                    val sequence=requireNotNull(saved.sequence)
                    val expected=SequencePlanner.frames(sequence).sum()-SequencePlanner.overlapFrames(sequence)*(sequence.timeline.clips.size-1)
                    check(frames==expected) { "Decoded frame count $frames != $expected." }
                    result.put("decodedFrames",frames)
                }
                check(sha256(a)==ah && sha256(b)==bh && sha256(c)==ch) { "An original changed." }
                if(name=="movie-video-delay") {
                    val colors=JSONArray()
                    for(time in listOf("0.100","0.500")) {
                        val raw=File(directory,"gap-${time.replace('.','-')}.rgb")
                        val decoded=graph.bridge.execute(listOf("-hide_banner","-nostdin","-v","error","-n","-ss",time,"-i",output.absolutePath,
                            "-frames:v","1","-vf","scale=1:1","-pix_fmt","rgb24","-f","rawvideo",raw.absolutePath)) {}
                        check(decoded.exitCode==0) { decoded.diagnostics }
                        val pixel=raw.readBytes().map { it.toInt() and 255 };check(pixel.size==3)
                        if(time=="0.100") check(pixel.max()<=3) { "Video was advanced into its leading gap: $pixel" }
                        else check(pixel[0]>=150)
                        colors.put(JSONArray(pixel));raw.delete()
                    }
                    result.put("leadingGapRgb",colors)
                }
                val artifact="$name.${saved.settings.container.extension}"
                output.copyTo(File(directory,artifact),overwrite=false)
                result.put("decoded",true).put("bytes",output.length()).put("durationMs",facts.durationMs)
                    .put("expectedDurationMs",JobPlans.duration(saved)).put("outputFile",artifact).put("outputSha256",sha256(output))
            } finally { graph.files.output(saved).delete() }
        }
        // Deterministic cancellation after verification still must not produce a shareable output.
        val cancelled=project().toJob(UUID.randomUUID().toString())
        coroutineScope {
            val work=launch(start=CoroutineStart.LAZY) {
                dev.forma.app.data.FfmpegTranscoder(graph.files,graph.bridge).run(cancelled,{}, {},
                    { if(it.accepted) cancel("Movie lab cancellation at accepted attempt") })
            }
            work.start();work.join();check(work.isCancelled)
        }
        check(!graph.files.output(cancelled).exists()) { "Cancelled movie was published." }
        check(!graph.files.workDir(cancelled).listFiles().orEmpty().isNotEmpty())
        graph.files.workDir(cancelled).delete()
        val commitFailed=project().toJob(UUID.randomUUID().toString())
        check(runCatching {
            dev.forma.app.data.FfmpegTranscoder(graph.files,graph.bridge).run(commitFailed,
                { if(it==JobState.COMPLETED) error("Lab simulates a failed durable completion") },{})
        }.isFailure)
        check(!graph.files.output(commitFailed).exists()) { "Failed queue completion left a shareable output." }
        // An impossible minimum bitrate budget fails without publishing or modifying originals.
        val impossible=project().copy(targetBytes=65536)
        val first=impossible.sequence.timeline.clips.first().source
        val invalid=JobSpec(UUID.randomUUID().toString(),first,Trim(),impossible.settings,impossible.sequence,impossible.targetBytes)
        check(runCatching { dev.forma.app.data.FfmpegTranscoder(graph.files,graph.bridge).run(invalid,{}, {}) }.isFailure)
        check(!graph.files.output(invalid).exists())
        check(sha256(a)==ah && sha256(b)==bh && sha256(c)==ch)
    }

    private fun readRecipe(file: File): EditorCase {
        require(file.isFile && file.length() in 1..65_536L) { "recipe.json must be 1–65536 bytes." }
        val j = JSONObject(file.readText())
        require(j.keys().asSequence().all { it in setOf("schema", "startMs", "endMs", "container", "video", "audio", "maxHeight", "fps", "rateControl", "videoKbps", "audioKbps", "effects") }) { "Unknown recipe field." }
        fun number(name: String, default: Long): Long {
            if (!j.has(name)) return default
            val v = j.get(name)
            require(v is Int || v is Long) { "$name must be an integer." }
            return (v as Number).toLong()
        }
        fun int(name: String, default: Int): Int = number(name, default.toLong()).let {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()); it.toInt()
        }
        fun text(name: String, default: String): String {
            if (!j.has(name)) return default
            val value = j.get(name); require(value is String) { "$name must be a string." }; return value
        }
        require(number("schema", -1) == 1L) { "Unsupported recipe schema." }
        val settings = Settings(container = Container.valueOf(text("container", "MP4")),
            video = VideoEncoder.valueOf(text("video", "X264")), audio = AudioEncoder.valueOf(text("audio", "AAC")),
            maxHeight = int("maxHeight", 720), fps = int("fps", 30),
            rateControl = RateControl.valueOf(text("rateControl", "QUALITY")),
            videoKbps = int("videoKbps", 4000), audioKbps = int("audioKbps", 160),
            effects = if (j.has("effects")) ClipEffectsCodec.decode(j.getJSONObject("effects")) else ClipEffects())
        return EditorCase("custom", Trim(number("startMs", 0), if (!j.has("endMs") || j.isNull("endMs")) null else number("endMs", 0)), settings)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
