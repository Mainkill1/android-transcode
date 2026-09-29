package dev.forma.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.forma.core.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONObject

/** Compiled only when a source-built local Maven artifact is explicitly selected. */
internal class KitNextBridge : FfmpegBridge {
    override suspend fun capabilities(): Capabilities = withContext(Dispatchers.IO) {
        FFmpegKitConfig.setSessionHistorySize(8)
        fun listing(option: String): String {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", option))
            check(ReturnCode.isSuccess(session.getReturnCode())) { "Could not query FFmpeg $option." }
            return session.getOutput().orEmpty()
        }
        val encoders = FfmpegListing.encoders(listing("-encoders"))
        val muxers = FfmpegListing.muxers(listing("-muxers"))
        val filters = FfmpegListing.filters(listing("-filters"))
        Capabilities(true, "Device support is established by bounded real export attempts, not capability reports alone.", encoders, muxers, filters,
            "FFmpegKitNext 9.0.0 · ${FFmpegKitConfig.getFFmpegVersion()}")
    }

    private fun probeJson(localPath: String): JSONObject {
        val session = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_streams", "-show_format", "-of", "json", localPath))
        check(ReturnCode.isSuccess(session.getReturnCode())) { "FFprobe could not inspect this media file." }
        return JSONObject(session.getOutput().orEmpty())
    }

    override suspend fun probe(localPath: String): Source = withContext(Dispatchers.IO) {
        val root = probeJson(localPath)
        val streams = root.getJSONArray("streams")
        val video = (0 until streams.length()).map { streams.getJSONObject(it) }.filter { it.optString("codec_type") == "video" }
        val audio = (0 until streams.length()).map { streams.getJSONObject(it) }.filter { it.optString("codec_type") == "audio" }
        val v = video.firstOrNull()
        val duration = root.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
            ?: (video + audio).mapNotNull { it.optString("duration").toDoubleOrNull() }.maxOrNull() ?: 0.0
        val pixelFormat = v?.optString("pix_fmt").orEmpty()
        Source(localPath, File(localPath).name, (duration * 1000).toLong(), v?.optInt("width") ?: 0,
            v?.optInt("height") ?: 0, video.size, audio.size,
            v != null && ColorRules.needsQualifiedPipeline(pixelFormat, v.optString("color_transfer"), v.optInt("bits_per_raw_sample")),
            File(localPath).length())
    }

    /** Kept for single-route callers; production exports use prepareAttempts and ExportRetry. */
    override suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> =
        prepareAttempts(source, trim, settings, input, output).first().arguments

    override suspend fun prepareAttempts(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<PreparedAttempt> =
        withContext(Dispatchers.IO) {
            val caps = capabilities()
            val problems = Planner.validate(source, trim, settings, caps)
            require(problems.isEmpty()) { problems.joinToString("\n") }
            fun software(reason: String): List<PreparedAttempt> {
                val resolved = settings.copy(video = settings.video.softwareVariant())
                require(settings.container == Container.M4A || resolved.video.ffmpeg in caps.encoders) {
                    "$reason The software encoder is not compiled."
                }
                return listOf(PreparedAttempt(Planner.arguments(source, trim, resolved, input, output),
                    EncodeDecision(EncodeBackend.SOFTWARE,
                        if (settings.container == Container.M4A) null else resolved.video.ffmpeg, reason = reason)))
            }
            if (!settings.video.deviceRequested || settings.container == Container.M4A)
                return@withContext software("Software explicitly selected or audio-only output.")
            if (settings.video.automatic && (settings.rateControl == RateControl.QUALITY || settings.fps == 0))
                return@withContext software("Preserving constant quality or source/VFR timing; this device path requires explicit bitrate and frame rate.")
            require(settings.fps > 0) { "Choose an explicit output frame rate for device encoding." }
            val root = probeJson(input)
            val streams = root.getJSONArray("streams")
            val video = (0 until streams.length()).map { streams.getJSONObject(it) }.first { it.optString("codec_type") == "video" }
            val rotations = mutableListOf<Double>()
            var matrix = false
            video.optJSONObject("tags")?.optString("rotate")?.toDoubleOrNull()?.let { rotations.add(it) }
            video.optJSONArray("side_data_list")?.let { data ->
                for (i in 0 until data.length()) {
                    val entry = data.getJSONObject(i)
                    if (entry.has("rotation")) rotations.add(entry.optDouble("rotation", Double.NaN))
                    if (entry.has("displaymatrix")) matrix = true
                }
            }
            if (matrix || rotations.any { !it.isFinite() || kotlin.math.abs(it % 360) >= 0.001 }) {
                if (settings.video.automatic) return@withContext software("Preserving the source display transform in software.")
                error("Display-matrix transforms need a separately implemented device path; select Automatic or software encoding.")
            }
            val dimensions = MediaCodecCommand.dimensions(source.width, source.height, settings.maxHeight)
            val request = EncodeRequest(settings.video.format, dimensions.first, dimensions.second, settings.fps.toDouble(),
                settings.videoKbps * 1000, constantQuality = settings.rateControl == RateControl.QUALITY, hdr = source.hdr)
            val decisions = CodecTrials.plan(request, settings.video.accelerationMode, caps.encoders, AndroidCodecCatalog().candidates(request))
            require(decisions.isNotEmpty()) { "No device encoder components or permitted software route were found. A decoder is not an encoder." }
            decisions.map { decision ->
                val resolved = settings.copy(video = if (decision.backend == EncodeBackend.MEDIACODEC)
                    settings.video.deviceVariant() else settings.video.softwareVariant())
                val args = Planner.arguments(source, trim, resolved, input, output)
                PreparedAttempt(if (decision.backend == EncodeBackend.MEDIACODEC)
                    MediaCodecCommand.bind(args, request, decision) else args, decision)
            }
        }

    override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult = withContext(Dispatchers.IO) {
        val done = CompletableDeferred<FFmpegSession>()
        val encoderIndex = arguments.indexOf("-c:v")
        val evidence = CodecFailureEvidence(if (encoderIndex >= 0) arguments.getOrNull(encoderIndex + 1).orEmpty() else "no_video_encoder")
        val encodedFrames = AtomicBoolean(false)
        val session = FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray(),
            { done.complete(it) }, { evidence.observe(it.message.orEmpty()) },
            {
                if (it.videoFrameNumber > 0) encodedFrames.set(true)
                onProgress(Progress(it.time.toLong(), it.speed))
            })
        val completed = try { done.await() } catch (cancel: CancellationException) {
            FFmpegKit.cancel(session.getSessionId())
            withContext(NonCancellable) { done.await() }
            throw cancel
        }
        val output = completed.getOutput().orEmpty()
        evidence.observe(output)
        val failure = if (ReturnCode.isCancel(completed.getReturnCode())) FailureKind.CANCELLED else evidence.failure(encodedFrames.get())
        NativeResult(completed.getReturnCode()?.value ?: -1,
            (completed.getFailStackTrace() ?: output).takeLast(6000), failure)
    }
}
