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
        // Bound retained session history. The application does not install global callbacks.
        FFmpegKitConfig.setSessionHistorySize(8)
        fun listing(option: String): String {
            val session = FFmpegKit.executeWithArguments(arrayOf("-hide_banner", option))
            check(ReturnCode.isSuccess(session.getReturnCode())) { "Could not query FFmpeg $option." }
            return session.getOutput().orEmpty()
        }
        val encoders = Regex("(?m)^\\s*[VAS][A-Z.]{5}\\s+(\\S+)").findAll(listing("-encoders"))
            .map { it.groupValues[1] }.filterNot { it.endsWith("_mediacodec") }.toSet()
        val muxers = Regex("(?m)^\\s*E\\s+(\\S+)").findAll(listing("-muxers"))
            .flatMap { it.groupValues[1].split(',').asSequence() }.toSet()
        val filters = Regex("(?m)^\\s*[TSC.]{3}\\s+(\\S+)").findAll(listing("-filters"))
            .map { it.groupValues[1] }.toSet()
        Capabilities(true, "Device encoders are held until device-specific qualification.", encoders, muxers, filters,
            "FFmpegKitNext 9.0.0 · ${FFmpegKitConfig.getFFmpegVersion()}")
    }

    override suspend fun probe(localPath: String): Source = withContext(Dispatchers.IO) {
        // Probing uses a staged local file, never a shell-quoted document URI.
        val session = FFprobeKit.executeWithArguments(arrayOf("-v", "error", "-show_streams", "-show_format", "-of", "json", localPath))
        check(ReturnCode.isSuccess(session.getReturnCode())) { "FFprobe could not inspect this media file." }
        val root = JSONObject(session.getOutput().orEmpty())
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

    override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult = withContext(Dispatchers.IO) {
        val done = CompletableDeferred<FFmpegSession>()
        val session = FFmpegKit.executeWithArgumentsAsync(arguments.toTypedArray(),
            { done.complete(it) }, { /* Diagnostics remain scoped to this session. */ },
            { onProgress(Progress(it.time.toLong(), it.speed)) })
        val completed = try { done.await() } catch (cancel: CancellationException) {
            FFmpegKit.cancel(session.getSessionId())
            // Do not delete a staging file while native code still has it open.
            withContext(NonCancellable) { done.await() }
            throw cancel
        }
        NativeResult(completed.getReturnCode()?.value ?: -1,
            (completed.getFailStackTrace() ?: completed.getOutput().orEmpty()).takeLast(6000))
    }
}
