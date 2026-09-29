package dev.forma.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import dev.forma.core.*
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

    private fun probeJson(localPath: String, countFrames: Boolean = false): JSONObject {
        val session = FFprobeKit.executeWithArguments((listOf("-v", "error") + (if(countFrames) listOf("-count_frames") else emptyList()) + listOf("-show_streams", "-show_format", "-of", "json", localPath)).toTypedArray())
        check(ReturnCode.isSuccess(session.getReturnCode())) { "FFprobe could not inspect this media file." }
        return JSONObject(session.getOutput().orEmpty())
    }

    override suspend fun inspectStreams(localPath: String, countFrames: Boolean): OutputFacts = withContext(Dispatchers.IO) {
        // Synchronous FFprobe finishes its native worker before withContext can deliver cancellation.
        val root=probeJson(localPath,countFrames)
        val streams=root.getJSONArray("streams")
        val fields=(0 until streams.length()).map { i ->
            val stream=streams.getJSONObject(i)
            stream.keys().asSequence().associateWith { stream.optString(it) } +
                mapOf("tag:DURATION" to stream.optJSONObject("tags")?.optString("DURATION").orEmpty())
        }
        OutputFactsReader.read(root.optJSONObject("format")?.optString("start_time"),fields)
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

    override suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> =
        withContext(Dispatchers.IO) {
            val arguments = Planner.arguments(source, trim, settings, input, output)
            if (settings.effects.crop != null) {
                // Source dimensions are coded pixels; FFmpeg autorotates before user filters.
                // Reject unqualified metadata transforms rather than crop the wrong rectangle.
                val streams = probeJson(input).getJSONArray("streams")
                val video = (0 until streams.length()).map { streams.getJSONObject(it) }
                    .first { it.optString("codec_type") == "video" }
                val rotation = video.optJSONObject("tags")?.optString("rotate").orEmpty()
                require(rotation.isEmpty() || rotation.toDoubleOrNull()?.let { it.isFinite() && kotlin.math.abs(it % 360) < 0.001 } == true) {
                    "Cropping a rotation-tagged source needs display-coordinate mapping; reset crop for this source."
                }
                video.optJSONArray("side_data_list")?.let { data ->
                    for (i in 0 until data.length()) {
                        val entry = data.getJSONObject(i)
                        require(!entry.has("displaymatrix") && !entry.has("rotation")) {
                            "Cropping a display-matrix source is not qualified yet; reset crop for this source."
                        }
                    }
                }
            }
            if (!settings.video.hardware || settings.container == Container.M4A) return@withContext arguments
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
