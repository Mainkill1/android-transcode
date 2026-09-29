package dev.forma.app

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.forma.accelerationlab.*
import dev.forma.core.*
import dev.forma.ffmpeg.AndroidCodecCatalog
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native test APK only. No exported receiver/service or test API enters the application APK. */
@RunWith(AndroidJUnit4::class)
class HardwareAccelerationLabTest {
    @Test fun benchmark() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use the explicit ADB lab runner.", args.getString("formaAccelerationLab") == "true")
        val runId = args.getString("runId").orEmpty()
        require(runId.matches(Regex("[a-f0-9]{32}")))
        val appCommit = args.getString("appCommit").orEmpty()
        require(appCommit.matches(Regex("[a-f0-9]{40}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "acceleration-lab/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Refusing to reuse a previous run's directory." }
        val rows = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("runId", runId).put("appCommit", appCommit)
            .put("status", "running").put("deviceQualified", false).put("benchmarkSmokeOnly", true)
            .put("samples", rows).put("fingerprint", Build.FINGERPRINT).put("model", Build.MODEL)
            .put("sdk", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("soc", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown")
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(20 * 60 * 1000L) {
                    check(Build.VERSION.SDK_INT >= 29) { "API 26–28 hardware identity is UNKNOWN; this lab will not certify it by component name." }
                    val mode = LabMode.valueOf((args.getString("mode") ?: "NDK"))
                    val format = VideoFormat.valueOf((args.getString("format") ?: "H264"))
                    val baseline = (args.getString("baseline") ?: "JAVA")
                    require(baseline in setOf("JAVA", "SOFTWARE") && !(baseline == "JAVA" && mode == LabMode.JAVA))
                    val fixture = LabFixture((args.getString("width") ?: "1280").toInt(), (args.getString("height") ?: "720").toInt(),
                        (args.getString("fps") ?: "30").toInt(), (args.getString("seconds") ?: "3").toInt())
                    val bitrateKbps = (args.getString("videoKbps") ?: "4000").toInt()
                    require(bitrateKbps in 100..200_000)
                    val operatingRate = (args.getString("operatingRate") ?: "0").toInt()
                    require(operatingRate in 0..1000)
                    report.put("mode", mode.name).put("baseline", baseline).put("format", format.name)
                        .put("width", fixture.width).put("height", fixture.height).put("fps", fixture.fps)
                        .put("seconds", fixture.seconds).put("videoKbps", bitrateKbps).put("operatingRate", operatingRate)
                        .put("appApkSha256", sha256(File(context.applicationInfo.sourceDir)))
                        .put("timingIncludesDebugLogging", true)
                    check(directory.usableSpace > 256L * 1024 * 1024) { "The lab needs at least 256 MiB of free app storage." }
                    val bridge = createFfmpegBridge()
                    val caps = bridge.capabilities()
                    check(caps.available) { "A source-built native FFmpeg APK is required; UI-only is not a passing test." }
                    check(format.device in caps.encoders) { "Missing encoder wrapper ${format.device}." }
                    check("libx264" in caps.encoders && "aac" in caps.encoders) { "Fixture generation needs libx264 and AAC." }
                    report.put("nativeBuild", caps.build)
                    val inventory = AndroidCodecCatalog().inventory()
                    report.put("inventory", JSONArray(inventory.map {
                        JSONObject().put("name", it.name).put("mime", it.mime).put("encoder", it.encoder)
                            .put("hardware", it.hardware.name).put("softwareOnly", it.softwareOnly.name)
                            .put("priority", it.priority).put("colorFormats", JSONArray(it.colorFormats))
                            .put("bitrateModes", JSONArray(it.bitrateModes.map { value -> value.name }))
                            .put("encoderSurfaceInput", it.encoderSurfaceInput.name)
                            .put("maxInstances", it.maxInstances ?: JSONObject.NULL)
                            .put("queryError", it.queryError ?: JSONObject.NULL)
                    }))
                    val help = native(listOf("-hide_banner", "-h", "encoder=${format.device}"))
                    check("Encoder ${format.device}" in help) { "Cannot query the requested wrapper." }
                    val options = LabCommands.optionNames(help)
                    report.put("encoderOptions", JSONArray(options.toList()))
                    if (mode in setOf(LabMode.DECODE_BUFFER, LabMode.SURFACE)) {
                        val decoderHelp = native(listOf("-hide_banner", "-h", "decoder=h264_mediacodec"))
                        check("Decoder h264_mediacodec" in decoderHelp && "ndk_codec" in LabCommands.optionNames(decoderHelp)) {
                            "The loaded native build has no usable H.264 MediaCodec decoder."
                        }
                    }
                    val input = File(directory, "fixture.mp4")
                    native(listOf("-hide_banner", "-nostdin", "-n", "-f", "lavfi", "-i",
                        "testsrc2=size=${fixture.width}x${fixture.height}:rate=${fixture.fps}",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", fixture.seconds.toString(),
                        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", input.absolutePath))
                    report.put("fixtureSha256", sha256(input))
                    val source = bridge.probe(input.absolutePath)
                    check(!source.hdr && source.width == fixture.width && source.height == fixture.height)
                    val trim = Trim(0, fixture.seconds * 1000L)
                    val webm = format in setOf(VideoFormat.VP8, VideoFormat.VP9, VideoFormat.AV1)
                    val hardware = VideoEncoder.values().single { it.hardware && it.ffmpeg == format.device }
                    val settings = Settings(container = if (webm) Container.WEBM else Container.MP4,
                        video = hardware, rateControl = RateControl.BITRATE, videoKbps = bitrateKbps,
                        maxHeight = fixture.height, fps = fixture.fps, audio = if (webm) AudioEncoder.OPUS else AudioEncoder.AAC)
                    check(Planner.validate(source, trim, settings, caps).isEmpty()) { "The generated workload is not supported by this native build." }
                    val request = EncodeRequest(format, fixture.width, fixture.height, fixture.fps.toDouble(), bitrateKbps * 1000)
                    val labels = listOf(baseline, mode.name, mode.name, baseline) // ABBA, not parallel encoders.
                    for ((index, label) in labels.withIndex()) {
                        awaitCool(context)
                        val row = JSONObject().put("index", index).put("route", label).put("passed", false)
                        rows.put(row)
                        val output = File(directory, "$index-${label.lowercase()}.${settings.container.extension}")
                        val chosenSettings = if (label != "SOFTWARE") settings else settings.copy(video = when (format) {
                            VideoFormat.H264 -> VideoEncoder.X264
                            VideoFormat.HEVC -> VideoEncoder.X265
                            VideoFormat.VP9 -> VideoEncoder.VP9
                            VideoFormat.AV1 -> VideoEncoder.AV1
                            VideoFormat.VP8 -> error("There is no software VP8 app selection; use the JAVA baseline.")
                        })
                        check(chosenSettings.video.ffmpeg in caps.encoders) { "Requested baseline encoder is not compiled." }
                        val prepareStart = SystemClock.elapsedRealtimeNanos()
                        val prepared = bridge.prepare(source, trim, chosenSettings, input.absolutePath, output.absolutePath)
                        val isCandidate = index == 1 || index == 2
                        val rate = if (isCandidate) operatingRate else 0
                        val command = if (label == "SOFTWARE") prepared.toMutableList().apply {
                            this[indexOf("-loglevel") + 1] = "debug"
                        } else LabCommands.variant(prepared, LabMode.valueOf(label), request, fixture, options, rate)
                        row.put("prepareMs", (SystemClock.elapsedRealtimeNanos() - prepareStart) / 1_000_000.0)
                        row.put("arguments", JSONArray(command)).put("operatingRate", rate)
                        if (label == "SURFACE") checkSurface(prepared, request)
                        if (label != "SOFTWARE") row.put("encoderComponent", prepared[prepared.indexOf("-codec_name:v") + 1])
                        row.put("thermalBefore", thermal(context)).put("pssKiBBefore", Debug.getPss())
                        val start = SystemClock.elapsedRealtimeNanos()
                        val log = native(command)
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        row.put("encodeAndMuxMs", elapsed).put("thermalAfter", thermal(context)).put("pssKiBAfter", Debug.getPss())
                        LabDiagnostics.validate(if (label == "SOFTWARE") null else LabMode.valueOf(label), chosenSettings.video.ffmpeg, log)
                        if (label in setOf("DECODE_BUFFER", "SURFACE")) row.put("decoderComponent", observedDecoder(log))
                        if (label == "SURFACE") {
                            val surface = Regex("Using surface (0x[0-9a-fA-F]+)").find(log)?.groupValues?.get(1)
                            check(surface != null && surface.drop(2).any { it != '0' }) { "No usable decoder surface was observed." }
                            row.put("surfaceObserved", true).put("physicalZeroCopyVerified", false)
                        }
                        File(directory, "$index.log").writeText(log.take(16000) + "\n--- tail ---\n" + log.takeLast(16000))
                        val verificationStart = SystemClock.elapsedRealtimeNanos()
                        val actual = bridge.probe(output.absolutePath)
                        check(actual.videoTracks == 1 && actual.audioTracks == 1 && !actual.hdr)
                        check(actual.width == fixture.width && actual.height == fixture.height)
                        check(kotlin.math.abs(actual.durationMs - fixture.seconds * 1000L) <= 100)
                        val timestamps = frameTimestamps(output, fixture)
                        FrameSequence.verify(timestamps, fixture)
                        native(listOf("-hide_banner", "-nostdin", "-v", "error", "-xerror", "-err_detect", "explode",
                            "-i", output.absolutePath, "-map", "0:v:0", "-map", "0:a:0", "-f", "null", "-"))
                        row.put("verificationMs", (SystemClock.elapsedRealtimeNanos() - verificationStart) / 1_000_000.0)
                            .put("frames", timestamps.size).put("firstVideoPtsSeconds", timestamps.first())
                            .put("durationMs", actual.durationMs).put("bytes", output.length()).put("outputSha256", sha256(output))
                            .put("processedFps", fixture.frames * 1000.0 / elapsed).put("passed", true)
                    }
                    report.put("status", "passed")
                }
            }
        } catch (error: Throwable) {
            report.put("status", "failed").put("error", error.message ?: error.javaClass.name)
            throw error
        } finally {
            // A UUID-bound result prevents stale reports from becoming a false pass.
            val temporary = File(directory, "report.tmp")
            temporary.writeText(report.toString(2))
            check(temporary.renameTo(File(directory, "report.json"))) { "Could not publish the lab report." }
            if (args.getString("keepMedia") != "true") directory.listFiles()?.filter {
                it.extension in setOf("mp4", "webm")
            }?.forEach { it.delete() }
        }
    }

    private suspend fun native(arguments: List<String>): String {
        val done = CompletableDeferred<FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray(), { done.complete(it) }, {}, {})
        val completed = try { done.await() } catch (cancel: CancellationException) {
            FFmpegKit.cancel(session.getSessionId())
            withContext(NonCancellable) { done.await() }
            throw cancel
        }
        val output = completed.getOutput().orEmpty()
        check(ReturnCode.isSuccess(completed.getReturnCode())) { (completed.getFailStackTrace() ?: output).takeLast(6000) }
        return output
    }

    private fun frameTimestamps(file: File, fixture: LabFixture): List<Double> {
        val session = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-select_streams", "v:0",
            "-show_frames", "-show_entries", "frame=pts_time,width,height", "-of", "json", file.absolutePath))
        check(ReturnCode.isSuccess(session.getReturnCode())) { "Cannot decode/probe output frame timestamps." }
        val frames = JSONObject(session.getOutput().orEmpty()).getJSONArray("frames")
        return (0 until frames.length()).map { index ->
            val frame = frames.getJSONObject(index)
            check(frame.getInt("width") == fixture.width && frame.getInt("height") == fixture.height)
            frame.getString("pts_time").toDouble()
        }
    }

    private fun observedDecoder(log: String): String {
        val name = LabDiagnostics.decoderComponent(log)
        val info = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.singleOrNull { it.name == name }
        check(Build.VERSION.SDK_INT >= 29 && info != null && !info.isEncoder && info.isHardwareAccelerated && !info.isSoftwareOnly) {
            "The observed decoder is not identified as hardware by Android."
        }
        return name
    }

    private fun checkSurface(prepared: List<String>, request: EncodeRequest) {
        val name = prepared[prepared.indexOf("-codec_name:v") + 1]
        val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.single { it.name == name }
        val caps = info.getCapabilitiesForType(request.format.mime)
        check(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats)
        val mode = prepared[prepared.indexOf("-bitrate_mode:v") + 1]
        val format = MediaFormat.createVideoFormat(request.format.mime, request.width, request.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, request.bitrate)
            setFloat(MediaFormat.KEY_FRAME_RATE, request.fps.toFloat())
            setInteger(MediaFormat.KEY_BITRATE_MODE, if (mode == "vbr") MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        check(caps.isFormatSupported(format)) { "The bound encoder does not advertise this exact surface configuration." }
    }

    private fun thermal(context: Context): Int = if (Build.VERSION.SDK_INT >= 29)
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus else -1

    private suspend fun awaitCool(context: Context) {
        repeat(60) {
            if (thermal(context) < PowerManager.THERMAL_STATUS_MODERATE) return
            delay(1000)
        }
        error("Device stayed thermally throttled; cool it before comparing acceleration paths.")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
