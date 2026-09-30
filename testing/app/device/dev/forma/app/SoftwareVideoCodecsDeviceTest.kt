package dev.forma.app

import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.JobCodec
import dev.forma.app.service.TranscodeService
import dev.forma.app.work.RunMode
import dev.forma.core.*
import dev.forma.core.image.QueueJobSpec
import dev.forma.ffmpeg.StreamKind
import dev.forma.ffmpeg.createFfmpegBridge
import dev.forma.ffmpeg.verifyOutputStreams
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical-device qualification; reports distinguish bridge smoke from durable service export. */
@RunWith(AndroidJUnit4::class)
class SoftwareVideoCodecsDeviceTest {
    @Test fun bridgeSmokeRetainsDirectEncoderEvidence(): Unit = runBlocking {
        val context = labContext()
        val runId = UUID.randomUUID().toString()
        val dir = File(context.cacheDir, "software-codecs-smoke-$runId").apply { check(mkdirs()) }
        val report = report("bridgeSmoke", runId)
        val cases = JSONArray().also { report.put("cases", it) }
        try {
            val bridge = createFfmpegBridge()
            val caps = bridge.capabilities()
            check(caps.available) { "A real native FFmpeg build is required." }
            report.put("nativeBuild", caps.build)
            val input = File(dir, "source.mp4")
            val generated = bridge.execute(listOf("-hide_banner", "-v", "error", "-nostdin", "-n",
                "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=24:duration=1",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", input.absolutePath)) {}
            check(generated.exitCode == 0) { generated.diagnostics }
            val source = bridge.probe(input.absolutePath)
            val originalHash = sha256(input)
            report.put("originalSha256", originalHash)
            for ((encoder, container, codec) in selections) {
                val evidence = JSONObject().put("requestedEncoder", encoder.ffmpeg)
                cases.put(evidence)
                check(encoder.ffmpeg in caps.encoders) { "${encoder.ffmpeg} is not packaged." }
                val output = File(dir, "${encoder.name.lowercase()}.${container.extension}")
                val settings = Settings(container = container, video = encoder, audio = AudioEncoder.NONE,
                    rateControl = RateControl.BITRATE, videoKbps = 500, maxHeight = 96, fps = 24)
                val attempt = bridge.prepareAttempts(source, Trim(), settings, input.absolutePath, output.absolutePath).single()
                val decision = requireNotNull(attempt.decision)
                check(decision.backend == EncodeBackend.SOFTWARE && decision.encoder == encoder.ffmpeg)
                check(attempt.arguments[attempt.arguments.indexOf("-c:v") + 1] == encoder.ffmpeg)
                val result = bridge.execute(attempt.arguments) {}
                check(result.exitCode == 0) { "${encoder.ffmpeg}: ${result.diagnostics}" }
                check(output.length() > 0 && videoCodec(output) == codec)
                // Retain the original explicit-decoder smoke, then independently exercise production selection.
                val override = if (encoder == VideoEncoder.AV1) listOf("-c:v", "libdav1d") else emptyList()
                val forced = bridge.execute(listOf("-hide_banner", "-nostdin", "-v", "error", "-xerror") +
                    override + listOf("-i", output.absolutePath, "-map", "0:v:0", "-f", "null", "-")) {}
                check(forced.exitCode == 0) { forced.diagnostics }
                strictDecode(bridge, output)
                verifyOutputStreams(bridge, source, Trim(), settings, input.absolutePath, output.absolutePath)
                val counted = bridge.inspectStreams(output.absolutePath, countFrames = true)
                check(counted.streams.single { it.kind == StreamKind.VIDEO }.decodedFrames == 24L)
                val facts = bridge.probe(output.absolutePath)
                check(facts.videoTracks == 1 && facts.audioTracks == 0 && facts.durationMs in 900..1100)
                check(sha256(input) == originalHash) { "The smoke original changed." }
                val playback = renderedPlayback(context, output, expectAudio = false)
                evidence.put("actualCodec", codec)
                    .put("bytes", output.length()).put("durationMs", facts.durationMs)
                    .put("decodedFrames", 24).put("strictProductionDecode", true)
                    .put("renderedPlayback", playback).put("outputSha256", sha256(output))
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("error", failure.message ?: failure.javaClass.name)
            throw failure
        } finally {
            writeReport(context, "smoke", runId, report)
            dir.deleteRecursively()
        }
    }

    @Test fun queuedServiceExportsReachDurableCompleted(): Unit = runBlocking {
        val context = labContext()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = (context.applicationContext as FormaApplication).graph
        graph.initialize()
        check(graph.runs.state.value.mode == RunMode.IDLE && graph.queue.entries.value.isEmpty()) {
            "Use an idle, empty isolated lab queue."
        }
        val runId = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "forma-tests/software-codecs-$runId").apply { check(mkdirs()) }
        val report = report("durableService", runId)
        val cases = JSONArray().also { report.put("cases", it) }
        val queueFile = File(context.filesDir, "queue-v1.json")
        val savedQueue = queueFile.readBytes()
        val owned = mutableListOf<JobSpec>()
        var ownedRunId: Long? = null
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            val caps = graph.bridge.capabilities()
            check(caps.available) { "A real native FFmpeg build is required." }
            report.put("nativeBuild", caps.build)
            val opus = AudioEncoder.OPUS.ffmpeg in caps.encoders
            report.put("opusPackaged", opus)
            val input = File(dir, "source.mp4")
            val generated = graph.bridge.execute(listOf("-hide_banner", "-v", "error", "-nostdin", "-n",
                "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=30:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", input.absolutePath)) {}
            check(generated.exitCode == 0) { generated.diagnostics }
            val originalHash = sha256(input)
            report.put("originalSha256", originalHash)
            val source = graph.bridge.probe(input.absolutePath).copy(uri = Uri.fromFile(input).toString())
            check(source.videoTracks == 1 && source.audioTracks == 1 && source.durationMs >= 1900)
            val trim = Trim(250, 1750)
            for ((encoder, container, codec) in selections) {
                val evidence = JSONObject().put("requestedEncoder", encoder.ffmpeg)
                cases.put(evidence)
                check(encoder.ffmpeg in caps.encoders) { "${encoder.ffmpeg} is not packaged." }
                val audio = if (container == Container.MP4) AudioEncoder.AAC else if (opus) AudioEncoder.OPUS else AudioEncoder.NONE
                val settings = Settings(container = container, video = encoder, audio = audio,
                    rateControl = RateControl.BITRATE, videoKbps = 500, maxHeight = 96, fps = 24)
                if (container == Container.WEBM && !opus) {
                    val unsupported = JobSpec(UUID.randomUUID().toString(), source, trim, settings.copy(audio = AudioEncoder.OPUS))
                    check(JobPlans.validate(unsupported, caps).any { it.contains("libopus") || it.contains("Opus") }) {
                        "A missing Opus encoder was not rejected before export."
                    }
                    evidence.put("opusUnavailableValidated", true)
                }
                val job = JobSpec(UUID.randomUUID().toString(), source, trim, settings)
                owned += job
                graph.queue.add(listOf(job))
                instrumentation.runOnMainSync {
                    ContextCompat.startForegroundService(context,
                        Intent(context, TranscodeService::class.java).setAction(TranscodeService.START))
                }
                withTimeout(180_000) {
                    while (graph.queue.entries.value.first { it.spec.id == job.id }.state != JobState.COMPLETED) {
                        val entry = graph.queue.entries.value.first { it.spec.id == job.id }
                        val running = graph.runs.state.value
                        if (entry.state in setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING) && running.mode != RunMode.IDLE)
                            ownedRunId = running.id
                        check(entry.state !in setOf(JobState.FAILED, JobState.INTERRUPTED, JobState.CANCELLED)) { entry.message }
                        check(graph.queue.error.value == null) { graph.queue.error.value.orEmpty() }
                        delay(50)
                    }
                    while (graph.runs.state.value.mode != RunMode.IDLE) delay(50)
                }
                val durable = JobCodec.decode(queueFile.readText()).single { it.spec.id == job.id }
                check(durable.state == JobState.COMPLETED && durable.completedAtMs != null)
                val savedJob = (durable.spec as QueueJobSpec.Av).job
                check(savedJob.trim == trim && savedJob.settings == settings && savedJob.source.uri == source.uri) {
                    "Durable queue changed the frozen source, trim or codec settings."
                }
                val output = graph.files.output(job)
                check(output.isFile && output.length() > 0)
                val sidecar = JSONObject(File(output.parentFile, "${job.id}.acceleration.json").readText())
                val events = sidecar.getJSONArray("events")
                val verified = (0 until events.length()).map { events.getJSONObject(it) }.last { it.getString("status") == "VERIFIED" }
                check(verified.getString("backend") == "SOFTWARE" && verified.getString("encoder") == encoder.ffmpeg)
                check(videoCodec(output) == codec)
                strictDecode(graph.bridge, output)
                // Repeat the production verifier against the original fixture, and inspect decoded frames independently.
                verifyOutputStreams(graph.bridge, source, trim, settings, input.absolutePath, output.absolutePath)
                val counted = graph.bridge.inspectStreams(output.absolutePath, countFrames = true)
                val picture = counted.streams.single { it.kind == StreamKind.VIDEO }
                check(picture.width == 128 && picture.height == 96 && picture.decodedFrames == 36L) {
                    "Wrong output geometry or retained frame count: $picture"
                }
                check(counted.streams.count { it.kind == StreamKind.AUDIO } == if (audio == AudioEncoder.NONE) 0 else 1)
                val facts = graph.bridge.probe(output.absolutePath)
                check(facts.durationMs in 1350..1650) { "Wrong retained duration: ${facts.durationMs} ms." }
                check(facts.audioTracks == if (audio == AudioEncoder.NONE) 0 else 1)
                if (audio != AudioEncoder.NONE) {
                    check(facts.audioStreams.single().codec == if (audio == AudioEncoder.AAC) "aac" else "opus")
                }
                check(sha256(input) == originalHash) { "The queued original changed." }
                val playback = renderedPlayback(context, output, expectAudio = audio != AudioEncoder.NONE)
                evidence.put("actualCodec", codec)
                    .put("requestedAudio", audio.name).put("actualAudio", facts.audioStreams.firstOrNull()?.codec ?: JSONObject.NULL)
                    .put("jobId", job.id).put("durableState", durable.state.name)
                    .put("bytes", output.length()).put("durationMs", facts.durationMs)
                    .put("width", picture.width).put("height", picture.height).put("decodedFrames", picture.decodedFrames)
                    .put("strictProductionDecode", true).put("renderedPlayback", playback)
                    .put("outputSha256", sha256(output)).put("originalSha256", originalHash)
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("error", failure.message ?: failure.javaClass.name)
            throw failure
        } finally {
            try {
                withContext(NonCancellable) {
                    check(graph.queue.entries.value.all { entry -> owned.any { it.id == entry.spec.id } }) {
                        "Refusing to restore over foreign jobs."
                    }
                    ownedRunId?.let { owner ->
                        graph.runs.stop(owner)
                        withTimeout(60_000) {
                            while (graph.runs.state.value.id == owner && graph.runs.state.value.mode != RunMode.IDLE) delay(50)
                        }
                    }
                    check(graph.runs.state.value.mode == RunMode.IDLE) { "Native ownership must end before fixture cleanup." }
                    queueFile.writeBytes(savedQueue)
                    graph.queue.load()
                    owned.forEach { graph.files.output(it).delete() }
                    dir.deleteRecursively()
                }
            } finally {
                activity.close()
                writeReport(context, "service", runId, report)
            }
        }
    }

    private fun labContext(): android.content.Context {
        assumeTrue("Run explicitly with -e formaSoftwareCodecs true on the lab package.",
            InstrumentationRegistry.getArguments().getString("formaSoftwareCodecs") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode.lab") { "Use the isolated lab package." }
        return context
    }

    private fun report(layer: String, runId: String): JSONObject = JSONObject()
        .put("schema", 2).put("layer", layer).put("runId", runId)
        .put("package", "dev.forma.transcode.lab").put("sdk", Build.VERSION.SDK_INT)
        .put("model", Build.MODEL).put("fingerprint", Build.FINGERPRINT)

    private fun writeReport(context: android.content.Context, layer: String, runId: String, report: JSONObject) {
        val directory = File(context.filesDir, "native-readiness").apply { check(isDirectory || mkdirs()) }
        val file = File(directory, "software-codecs-$layer-$runId.json")
        check(file.createNewFile()) { "A report for this run already exists." }
        file.writeText(report.toString(2))
    }

    private suspend fun strictDecode(bridge: dev.forma.ffmpeg.FfmpegBridge, output: File) {
        val decoded = bridge.execute(listOf("-hide_banner", "-nostdin", "-v", "error", "-xerror",
            "-err_detect", "explode", "-i", output.absolutePath,
            "-map", "0:v:0?", "-map", "0:a:0?", "-f", "null", "-")) {}
        check(decoded.exitCode == 0) { "Production decoder could not fully decode ${output.name}: ${decoded.diagnostics}" }
    }

    private suspend fun renderedPlayback(context: android.content.Context, output: File, expectAudio: Boolean): JSONObject =
        withContext(Dispatchers.Main) {
            val texture = SurfaceTexture(0).apply { setDefaultBufferSize(128, 96) }
            val surface = Surface(texture)
            val firstFrame = CompletableDeferred<Unit>()
            val decoder = CompletableDeferred<String>()
            val player = ExoPlayer.Builder(context).build()
            try {
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() { firstFrame.complete(Unit) }
                })
                player.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                        decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        decoder.complete(decoderName)
                    }
                })
                player.setVideoSurface(surface)
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(output)))
                player.prepare()
                player.play()
                withTimeout(20_000) {
                    while (!firstFrame.isCompleted) {
                        check(player.playerError == null) { "Media3 failed before first frame: ${player.playerError}" }
                        delay(50)
                    }
                    firstFrame.await()
                    while (player.playbackState != Player.STATE_ENDED) {
                        check(player.playerError == null) { "Media3 failed before end: ${player.playerError}" }
                        delay(50)
                    }
                }
                check(player.playerError == null)
                check(player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }) {
                    "No selected video track was rendered."
                }
                if (expectAudio) check(player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }) {
                    "Expected audio track was not selected."
                }
                val decoderName = withTimeout(2_000) { decoder.await() }
                JSONObject().put("firstFrameRendered", true).put("videoTrackSelected", true)
                    .put("decoder", decoderName).put("endedWithoutError", true)
            } finally {
                player.release()
                surface.release()
                texture.release()
            }
        }

    private fun videoCodec(file: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                when (extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)) {
                    "video/hevc" -> return "hevc"
                    "video/x-vnd.on2.vp9" -> return "vp9"
                    "video/av01" -> return "av1"
                }
            }
            error("No expected video track in ${file.name}.")
        } finally { extractor.release() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private val selections = listOf(
        Triple(VideoEncoder.X265, Container.MP4, "hevc"),
        Triple(VideoEncoder.VP9, Container.WEBM, "vp9"),
        Triple(VideoEncoder.AV1, Container.WEBM, "av1")
    )
}
