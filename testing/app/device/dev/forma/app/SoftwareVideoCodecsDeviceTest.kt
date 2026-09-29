package dev.forma.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.*
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in physical-device export qualification for the expanded software profile. */
@RunWith(AndroidJUnit4::class)
class SoftwareVideoCodecsDeviceTest {
    @Test fun softwareCodecsProduceMatchingPlayableStreams(): Unit = runBlocking {
        assumeTrue("Run explicitly with -e formaSoftwareCodecs true on the lab package.",
            InstrumentationRegistry.getArguments().getString("formaSoftwareCodecs") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode.lab") { "Use the isolated lab package." }
        val dir = File(context.cacheDir, "software-codecs-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val report = JSONObject().put("schema", 1).put("package", context.packageName)
        val cases = JSONArray()
        report.put("cases", cases)
        try {
            val bridge = createFfmpegBridge()
            val caps = bridge.capabilities()
            check(caps.available) { "A real native FFmpeg build is required." }
            val input = File(dir, "source.mp4")
            val generated = bridge.execute(listOf("-hide_banner", "-v", "error", "-nostdin", "-n",
                "-f", "lavfi", "-i", "testsrc2=size=128x96:rate=24:duration=1",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", input.absolutePath)) {}
            check(generated.exitCode == 0) { generated.diagnostics }
            val source = bridge.probe(input.absolutePath)
            val selections = listOf(
                Triple(VideoEncoder.X265, Container.MP4, "hevc"),
                Triple(VideoEncoder.VP9, Container.WEBM, "vp9"),
                Triple(VideoEncoder.AV1, Container.WEBM, "av1")
            )
            for ((encoder, container, expectedCodec) in selections) {
                check(encoder.ffmpeg in caps.encoders) { "${encoder.ffmpeg} is not in the packaged native encoder list." }
                val output = File(dir, "${encoder.name.lowercase()}.${container.extension}")
                val settings = Settings(container = container, video = encoder, audio = AudioEncoder.NONE,
                    rateControl = RateControl.BITRATE, videoKbps = 500, maxHeight = 96, fps = 24)
                val attempt = bridge.prepareAttempts(source, Trim(), settings, input.absolutePath, output.absolutePath).single()
                val decision = requireNotNull(attempt.decision)
                check(decision.backend == EncodeBackend.SOFTWARE)
                check(decision.encoder == encoder.ffmpeg)
                check(attempt.arguments[attempt.arguments.indexOf("-c:v") + 1] == encoder.ffmpeg)
                val result = bridge.execute(attempt.arguments) {}
                check(result.exitCode == 0) { "${encoder.ffmpeg}: ${result.diagnostics}" }
                check(output.length() > 0)
                val actualCodec = videoCodec(output)
                check(actualCodec == expectedCodec) { "Selected ${encoder.ffmpeg}, got $actualCodec." }
                val decoder = if (encoder == VideoEncoder.AV1) listOf("-c:v", "libdav1d") else emptyList()
                val decoded = bridge.execute(listOf("-v", "error", "-xerror") + decoder + listOf("-i", output.absolutePath,
                    "-map", "0:v:0", "-f", "null", "-")) {}
                check(decoded.exitCode == 0) { "Could not decode $actualCodec: ${decoded.diagnostics}" }
                val facts = bridge.probe(output.absolutePath)
                check(facts.videoTracks == 1 && facts.durationMs in 900..1100)
                withContext(Dispatchers.Main) {
                    val player = ExoPlayer.Builder(context).build()
                    try {
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(output)))
                        player.prepare()
                        player.play()
                        withTimeout(20_000) {
                            while (player.playbackState != Player.STATE_ENDED) {
                                check(player.playerError == null) { "Media3 could not play $actualCodec: ${player.playerError}" }
                                delay(50)
                            }
                        }
                    } finally {
                        player.release()
                    }
                }
                cases.put(JSONObject().put("requested", encoder.ffmpeg).put("actual", actualCodec)
                    .put("bytes", output.length()).put("durationMs", facts.durationMs))
            }
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report.put("status", "FAIL").put("error", failure.message ?: failure.javaClass.name)
            throw failure
        } finally {
            File(context.filesDir, "native-readiness").apply { mkdirs() }
                .resolve("software-codecs.json").writeText(report.toString(2))
            dir.deleteRecursively()
        }
    }

    private fun videoCodec(file: File): String {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                when (mime) {
                    "video/hevc" -> return "hevc"
                    "video/x-vnd.on2.vp9" -> return "vp9"
                    "video/av01" -> return "av1"
                }
            }
            error("No supported video track in ${file.name}.")
        } finally {
            extractor.release()
        }
    }
}
