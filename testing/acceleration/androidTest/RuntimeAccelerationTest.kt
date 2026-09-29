package dev.forma.app

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.*
import dev.forma.ffmpeg.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Separate test APK. Executes the same planner, retry loop and verifier as normal exports. */
@RunWith(AndroidJUnit4::class)
class RuntimeAccelerationTest {
    @Test fun encodeUsingRuntimeTrials() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit native device test only.", args.getString("formaNative") == "true")
        runBlocking(Dispatchers.IO) {
            val runId = requireNotNull(args.getString("formaRunId")) {
                "Direct ADB runs must supply a fresh formaRunId UUID."
            }
            require(UUID.fromString(runId).toString() == runId) { "Expected a canonical lowercase UUID." }
            val choice = requireNotNull(args.getString("formaEncoder")) {
                "Direct ADB runs must supply formaEncoder."
            }
            require(choice in setOf("H264_AUTO", "H265_AUTO", "H264_HW", "H265_HW", "VP9_HW", "AV1_HW"))
            val encoder = VideoEncoder.valueOf(choice)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.cacheDir, "codec-trial-$runId").apply { check(mkdirs()) }
            val events = JSONArray()
            val report = JSONObject().put("schemaVersion", 1).put("runId", runId)
                .put("requestedEncoder", choice).put("sdk", Build.VERSION.SDK_INT)
                .put("fingerprint", Build.FINGERPRINT).put("model", Build.MODEL)
                .put("events", events).put("success", false).put("deviceQualified", false)
                .put("nativeExecutionTested", false)
            try {
                val bridge = ManagedFfmpegBridge(createFfmpegBridge())
                val caps = bridge.capabilities()
                check(caps.available) { "A real native FFmpeg bundle is required; a UI-only APK must fail." }
                report.put("nativeBuild", caps.build)
                val input = File(directory, "source.mp4")
                val generated = bridge.execute(listOf("-hide_banner", "-nostdin", "-n", "-xerror",
                    "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                    "-t", "3", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", input.absolutePath)) {}
                check(generated.exitCode == 0) { generated.diagnostics }
                val source = bridge.probe(input.absolutePath)
                val settings = Settings(video = encoder, rateControl = RateControl.BITRATE, videoKbps = 1500,
                    maxHeight = 360, fps = 30, container = if (encoder.format in setOf(VideoFormat.VP9, VideoFormat.AV1)) Container.MKV else Container.MP4)
                val output = File(directory, "encoded.${settings.container.extension}")
                val attempts = bridge.prepareAttempts(source, Trim(), settings, input.absolutePath, output.absolutePath)
                report.put("nativeExecutionTested", true)
                val start = System.nanoTime()
                val selected = ExportRetry.run(attempts, output,
                    execute = { attempt, progress -> bridge.execute(attempt.arguments, progress) },
                    verify = { attempt -> verifyEncodedOutput(bridge, source, Trim(), settings, output, attempt) },
                    onAttempt = { event ->
                        events.put(JSONObject().put("attempt", event.number).put("total", event.total)
                            .put("status", event.status.name)
                            .put("component", event.attempt.decision?.codecName ?: JSONObject.NULL)
                            .put("reason", event.reason))
                    })
                val route = selected.decision
                report.put("exportAndVerificationMs", (System.nanoTime() - start) / 1_000_000)
                    .put("nativeExecutionTested", true).put("success", true)
                    .put("sourceSha256", sha256(input)).put("outputSha256", sha256(output)).put("outputBytes", output.length())
                    .put("selected", JSONObject().put("backend", route?.backend?.name)
                        .put("encoder", route?.encoder).put("component", route?.codecName ?: JSONObject.NULL)
                        .put("hardware", route?.hardwareSupport?.name)
                        .put("buffer", route?.bufferFormat?.name ?: JSONObject.NULL)
                        .put("bitrateMode", if (route?.backend == EncodeBackend.MEDIACODEC) route.bitrateMode.name else JSONObject.NULL))
            } catch (error: Exception) {
                report.put("success", false).put("errorType", error.javaClass.simpleName)
                throw error
            } finally {
                try {
                    writeFreshReport(File(context.filesDir, "acceleration"), "runtime-$runId.json", report)
                } finally {
                    directory.deleteRecursively()
                }
            }
        }
    }

    private fun writeFreshReport(directory: File, name: String, report: JSONObject) {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the test report directory." }
        val target = File(directory, name)
        check(target.createNewFile()) { "A report already exists for this run ID; use a new formaRunId." }
        target.writeText(report.toString(2))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
