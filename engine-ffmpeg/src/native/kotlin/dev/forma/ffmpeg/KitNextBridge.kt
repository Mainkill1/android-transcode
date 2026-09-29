package dev.forma.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.core.audio.SourceAudioFacts
import dev.forma.core.audio.AudioGraphPlanner
import java.io.File
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
        // Compiled wrapper availability is separate from per-job Android configuration support.
        val encoders = FfmpegListing.encoders(listing("-encoders"))
        val muxers = FfmpegListing.muxers(listing("-muxers"))
        val filters = FfmpegListing.filters(listing("-filters"))
        Capabilities(true, "Device encoding requires a compatible Android component and checked bitrate plan.", encoders, muxers, filters,
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
        val video = (0 until streams.length()).map { streams.getJSONObject(it) }.filter {
            it.optString("codec_type") == "video" && it.optJSONObject("disposition")?.optInt("attached_pic") != 1 }
        val audio = (0 until streams.length()).map { streams.getJSONObject(it) }.filter { it.optString("codec_type") == "audio" }
        val v = video.firstOrNull()
        val duration = root.optJSONObject("format")?.optString("duration")?.toDoubleOrNull()
            ?: (video + audio).mapNotNull { it.optString("duration").toDoubleOrNull() }.maxOrNull() ?: 0.0
        val pixelFormat = v?.optString("pix_fmt").orEmpty()
        Source(localPath, File(localPath).name, (duration * 1000).toLong(), v?.optInt("width") ?: 0,
            v?.optInt("height") ?: 0, video.size, audio.size,
            v != null && ColorRules.needsQualifiedPipeline(pixelFormat, v.optString("color_transfer"), v.optInt("bits_per_raw_sample")),
            File(localPath).length(), audio.map { stream ->
                val sampleRate = stream.optString("sample_rate").toIntOrNull()?.takeIf { it > 0 }
                val ticks = stream.optString("duration_ts").toLongOrNull()?.takeIf { it >= 0 }
                val base = stream.optString("time_base").split('/').map { it.toLongOrNull() }
                val us = if (ticks != null && base.size == 2 && base[0] != null && base[1]?.let { it > 0 } == true)
                    AudioGraphPlanner.roundRatio(ticks, base[0]!! * 1000000, base[1]!!)
                else ((stream.optString("duration").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: duration) * 1000000).toLong()
                val codec = stream.optString("codec_name")
                val total = if ((codec.startsWith("pcm_") || codec == "flac") && ticks != null && sampleRate != null &&
                    base.size == 2 && base[0] != null && base[1]?.let { it > 0 } == true)
                    AudioGraphPlanner.roundRatio(ticks, base[0]!! * sampleRate, base[1]!!) else null
                val tags = stream.optJSONObject("tags")
                SourceAudioFacts(stream.optInt("index"), sampleRate, stream.optInt("channels").takeIf { it > 0 },
                    stream.optString("channel_layout").takeIf { it.isNotEmpty() }, stream.optString("sample_fmt").takeIf { it.isNotEmpty() },
                    us, totalSamples = total, codec = codec, language = tags?.optString("language")?.takeIf { it.isNotEmpty() },
                    title = tags?.optString("title")?.takeIf { it.isNotEmpty() },
                    timelineOffsetUs = stream.optString("start_time").toDoubleOrNull()?.takeIf { it.isFinite() }?.let {
                        ((it - (v?.optString("start_time")?.toDoubleOrNull() ?: 0.0)) * 1000000).toLong() })
            })
    }

    override suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> =
        withContext(Dispatchers.IO) {
            val arguments = Planner.arguments(source, trim, settings, input, output)
            if (!settings.video.hardware || settings.container.audioOnly) return@withContext arguments
            require(settings.fps > 0) { "Choose an explicit output frame rate for device encoding; source-rate/VFR needs separate qualification." }
            val root = probeJson(input)
            val streams = root.getJSONArray("streams")
            val video = (0 until streams.length()).map { streams.getJSONObject(it) }.first { it.optString("codec_type") == "video" }
            val rotations = mutableListOf<Double>()
            video.optJSONObject("tags")?.optString("rotate")?.toDoubleOrNull()?.let { rotations.add(it) }
            video.optJSONArray("side_data_list")?.let { data ->
                for (i in 0 until data.length()) {
                    val entry = data.getJSONObject(i)
                    if (entry.has("rotation")) rotations.add(entry.optDouble("rotation", Double.NaN))
                    // Mirroring/skew can exist in a display matrix with zero rotation.
                    require(!entry.has("displaymatrix")) { "Display-matrix transforms need a separately qualified device path; use software encoding." }
                }
            }
            require(rotations.all { it.isFinite() && kotlin.math.abs(it % 360) < 0.001 }) {
                "Rotated source geometry is not yet qualified for device encoding; use software encoding."
            }
            val dimensions = MediaCodecCommand.dimensions(source.width, source.height, settings.maxHeight)
            val format = when (settings.video) {
                VideoEncoder.H264_HW -> VideoFormat.H264
                VideoEncoder.H265_HW -> VideoFormat.HEVC
                else -> error("Unsupported device encoder.")
            }
            val request = EncodeRequest(format, dimensions.first, dimensions.second, settings.fps.toDouble(),
                settings.videoKbps * 1000, constantQuality = settings.rateControl == RateControl.QUALITY, hdr = source.hdr)
            val decision = AccelerationPolicy.choose(request, AccelerationMode.HARDWARE_REQUIRED,
                capabilities().encoders, AndroidCodecCatalog().candidates(request))
            require(decision.backend == EncodeBackend.MEDIACODEC) { decision.reason }
            MediaCodecCommand.bind(arguments, request, decision)
        }

    override suspend fun prepare(spec: ImageJobSpec, actual: ImageInfo, attempt: ImageAttempt, input: String, output: String): List<String> =
        withContext(Dispatchers.IO) { ImagePlanner.plan(actual, spec, attempt, capabilities()).arguments(input, output) }

    override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult = withContext(Dispatchers.IO) {
        val done = CompletableDeferred<FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray(),
            { done.complete(it) }, { /* Diagnostics remain scoped to this session. */ },
            { onProgress(Progress(it.time.toLong(), it.speed)) })
        val completed = try { done.await() } catch (cancel: CancellationException) {
            FFmpegKit.cancel(session.getSessionId())
            withContext(NonCancellable) { done.await() }
            throw cancel
        }
        NativeResult(completed.getReturnCode()?.value ?: -1,
            (completed.getFailStackTrace() ?: completed.getOutput().orEmpty()).takeLast(6000))
    }
}
