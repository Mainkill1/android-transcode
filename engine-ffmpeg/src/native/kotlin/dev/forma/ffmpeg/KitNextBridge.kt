package dev.forma.ffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.FFprobeSessionCompleteCallback
import com.arthenica.ffmpegkit.ReturnCode
import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.core.audio.SourceAudioFacts
import dev.forma.core.audio.AudioGraphPlanner
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
        // Compiled wrapper availability is separate from per-job Android configuration support.
        val encoders = FfmpegListing.encoders(listing("-encoders"))
        val muxers = FfmpegListing.muxers(listing("-muxers"))
        val filters = FfmpegListing.filters(listing("-filters"))
        Capabilities(true, "Device encoding requires a compatible Android component and checked bitrate plan.", encoders, muxers, filters,
            "FFmpegKitNext 9.0.0 · ${FFmpegKitConfig.getFFmpegVersion()}",
            decoders=FfmpegListing.decoders(listing("-decoders")),demuxers=FfmpegListing.demuxers(listing("-demuxers")),
            pixelFormats=FfmpegListing.pixelFormats(listing("-pix_fmts")),configuration=listing("-buildconf"))
    }

    private suspend fun probeJson(localPath: String, countFrames: Boolean = false): JSONObject {
        val arguments = mutableListOf("-v", "error", "-show_streams", "-show_format", "-of", "json")
        if (countFrames) arguments += "-count_frames"
        arguments += localPath
        val session = awaitNativeSession<FFprobeSession, FFprobeSession>(
            start = { complete -> FFprobeKit.executeWithArgumentsAsync(arguments.toTypedArray(),
                FFprobeSessionCompleteCallback { complete(it) }) },
            cancel = { FFmpegKit.cancel(it.getSessionId()) })
        check(ReturnCode.isSuccess(session.getReturnCode())) { "FFprobe could not inspect this media file." }
        return JSONObject(session.getOutput().orEmpty())
    }

    override suspend fun inspectStreams(localPath: String, countFrames: Boolean): OutputFacts = withContext(Dispatchers.IO) {
        val root=probeJson(localPath,countFrames)
        val array=root.getJSONArray("streams")
        val streams=(0 until array.length()).map { array.getJSONObject(it) }.filterNot {
            it.optString("codec_type")=="video" && it.optJSONObject("disposition")?.optInt("attached_pic")==1
        }
        val fields=streams.map { stream ->
            stream.keys().asSequence().associateWith { stream.optString(it) } +
                mapOf("tag:DURATION" to stream.optJSONObject("tags")?.optString("DURATION").orEmpty())
        }
        val origin=root.optJSONObject("format")?.optString("start_time")
        val first=OutputFactsReader.read(origin,fields)
        val observed=fields.mapIndexed { i,stream ->
            if(first.streams[i].kind!=StreamKind.OTHER && first.streams[i].startUs==null)
                stream+observeStart(localPath,streams[i].getInt("index"),first.streams[i].kind)
            else stream
        }
        OutputFactsReader.read(origin,observed)
    }

    private suspend fun observeStart(localPath: String,index: Int,kind: StreamKind): Map<String,String> {
        suspend fun inspect(frames: Boolean): JSONObject? {
            val arguments=listOf("-v","error","-select_streams",index.toString(),"-read_intervals",if(frames) "%+#32" else "%+#1",
                if(frames) "-show_frames" else "-show_packets","-show_entries",
                if(frames) "frame=best_effort_timestamp,best_effort_timestamp_time,pts,pts_time" else "packet=pts,pts_time",
                "-of","json",localPath)
            val session=awaitNativeSession<FFprobeSession, FFprobeSession>(
                start = { complete -> FFprobeKit.executeWithArgumentsAsync(arguments.toTypedArray(),
                    FFprobeSessionCompleteCallback { complete(it) }) },
                cancel = { FFmpegKit.cancel(it.getSessionId()) })
            check(ReturnCode.isSuccess(session.getReturnCode())) { "FFprobe could not observe the stream presentation clock." }
            return JSONObject(session.getOutput().orEmpty()).optJSONArray(if(frames) "frames" else "packets")?.optJSONObject(0)
        }
        fun clock(entry: JSONObject?,frames: Boolean): Map<String,String>? {
            if(entry==null) return null
            val key=if(frames && entry.has("best_effort_timestamp_time")) "best_effort_timestamp" else "pts"
            val time=entry.optString("${key}_time")
            val pts=entry.optString(key)
            if(time.toDoubleOrNull()?.isFinite()!=true && pts.toLongOrNull()==null) return null
            return mapOf("observed_start_time" to time,"observed_start_pts" to pts)
        }
        // Decoded video clocks avoid mistaking reordered packet DTS/PTS for first presentation.
        if(kind==StreamKind.VIDEO) clock(inspect(true),true)?.let { return it }
        return clock(inspect(false),false) ?: clock(inspect(true),true).orEmpty()
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
        val hdrSideData = v?.optJSONArray("side_data_list")?.let { entries ->
            (0 until entries.length()).any { index ->
                val type = entries.optJSONObject(index)?.optString("side_data_type").orEmpty()
                listOf("mastering display", "content light", "dovi", "hdr").any { type.contains(it, ignoreCase = true) }
            }
        } ?: false
        Source(localPath, File(localPath).name, (duration * 1000).toLong(), v?.optInt("width") ?: 0,
            v?.optInt("height") ?: 0, video.size, audio.size,
            v != null && ColorRules.needsQualifiedPipeline(pixelFormat, v.optString("color_transfer"), v.optInt("bits_per_raw_sample"),
                v.optString("color_primaries"), v.optString("color_space"), hdrSideData),
            File(localPath).takeIf { it.isFile }?.length() ?: -1L, audio.map { stream ->
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
                require(settings.container.audioOnly || resolved.video.ffmpeg in caps.encoders) {
                    "$reason The software encoder is not compiled."
                }
                return listOf(PreparedAttempt(Planner.arguments(source, trim, resolved, input, output),
                    EncodeDecision(EncodeBackend.SOFTWARE,
                        if (settings.container.audioOnly) null else resolved.video.ffmpeg, reason = reason)))
            }
            if (!settings.video.deviceRequested || settings.container.audioOnly)
                return@withContext software("Software explicitly selected or audio-only output.")
            if (settings.video.automatic && (settings.rateControl == RateControl.QUALITY || settings.fps == 0))
                return@withContext software("Preserving constant quality or source/VFR timing; this device path requires explicit bitrate and frame rate.")
            if(settings.video.automatic && !settings.effects.isNeutral)
                return@withContext software("Preserving clip edits through the software graph route.")
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

    override suspend fun prepare(spec: ImageJobSpec, actual: ImageInfo, attempt: ImageAttempt, input: String, output: String): List<String> =
        withContext(Dispatchers.IO) { ImagePlanner.plan(actual, spec, attempt, capabilities()).arguments(input, output) }

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
