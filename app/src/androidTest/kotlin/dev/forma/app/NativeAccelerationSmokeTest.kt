package dev.forma.app

import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.*
import dev.forma.ffmpeg.AndroidCodecCatalog
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit device qualification entry point, never implied by the no-native UI test job. */
@RunWith(AndroidJUnit4::class)
class NativeAccelerationSmokeTest {
    @Test fun h264DeviceEncodeAndDecode() {
        assumeTrue("Run explicitly with -e formaNative true on a physical device.",
            InstrumentationRegistry.getArguments().getString("formaNative") == "true")
        runBlocking(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(context.cacheDir, "native-smoke-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val report = JSONObject().put("schemaVersion", 1).put("sdk", Build.VERSION.SDK_INT)
                .put("fingerprint", Build.FINGERPRINT).put("model", Build.MODEL)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
                .put("fullDeviceQualification", false)
            try {
                val bridge = createFfmpegBridge()
                val caps = bridge.capabilities()
                check(caps.available) { "FFmpeg for Android is REQUIRED. An API-only/UI-only build does not pass." }
                report.put("nativeBuild", caps.build)
                report.put("inventory", JSONArray(AndroidCodecCatalog().inventory().map {
                    JSONObject().put("name", it.name).put("mime", it.mime)
                        .put("encoder", it.encoder).put("hardware", it.hardware.name)
                }))
                val input = File(dir, "source.mp4")
                val generated = bridge.execute(listOf("-hide_banner", "-nostdin", "-n",
                    "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                    "-t", "2", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", input.absolutePath)) {}
                check(generated.exitCode == 0) { generated.diagnostics }
                val source = bridge.probe(input.absolutePath)
                val settings = Settings(video = VideoEncoder.H264_HW, rateControl = RateControl.BITRATE,
                    videoKbps = 1000, maxHeight = 240, fps = 30)
                val output = File(dir, "device.mp4")
                val arguments = bridge.prepare(source, Trim(), settings, input.absolutePath, output.absolutePath)
                val nameIndex = arguments.indexOf("-codec_name:v")
                check(nameIndex >= 0) { "The job did not bind a checked MediaCodec component." }
                report.put("codecName", arguments[nameIndex + 1]).put("arguments", JSONArray(arguments))
                val start = SystemClock.elapsedRealtime()
                val result = bridge.execute(arguments) {}
                report.put("encodeMs", SystemClock.elapsedRealtime() - start)
                check(result.exitCode == 0) { result.diagnostics }
                val actual = bridge.probe(output.absolutePath)
                check(actual.videoTracks == 1 && actual.audioTracks == 1)
                check(actual.width == 320 && actual.height == 240)
                check(kotlin.math.abs(actual.durationMs - 2000) <= 100)
                val decoded = bridge.execute(listOf("-hide_banner", "-nostdin", "-v", "error", "-xerror",
                    "-i", output.absolutePath, "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-")) {}
                check(decoded.exitCode == 0) { decoded.diagnostics }
                report.put("bytes", output.length()).put("durationMs", actual.durationMs)
                    .put("smokePassed", true)
            } catch (error: Exception) {
                report.put("smokePassed", false).put("error", error.message ?: error.javaClass.name)
                throw error
            } finally {
                File(context.filesDir, "native-readiness").apply { mkdirs() }
                    .resolve("h264-smoke.json").writeText(report.toString(2))
                dir.deleteRecursively()
            }
        }
    }
}
