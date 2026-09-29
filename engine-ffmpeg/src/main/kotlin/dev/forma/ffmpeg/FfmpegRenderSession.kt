package dev.forma.ffmpeg

import dev.forma.core.*
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.*

/** Production attempt evidence. Device tests observe it; they do not implement their own retry loop. */
data class RenderAttempt(
    val index: Int, val settings: Settings, val arguments: List<String>, val bytes: Long,
    val elapsedMs: Long, val verified: Boolean, val accepted: Boolean, val error: String? = null
)

/** Staged-file execution shared by the Android exporter and independent host/device qualification. */
class FfmpegRenderSession(private val bridge: FfmpegBridge) {
    suspend fun render(
        requested: JobSpec, inputs: List<File>, output: File, onProgress: (Progress) -> Unit,
        onAttempt: (RenderAttempt) -> Unit = {}
    ): Source = withContext(Dispatchers.IO) {
        val paths = inputs.map { it.canonicalFile }
        val destination = output.canonicalFile
        require(paths.isNotEmpty() && paths.all { it.isFile && it != destination }) { "Readable, separate staged sources are required." }
        require(!destination.exists()) { "Never overwrite an existing render or source." }
        require(destination.parentFile?.isDirectory == true) { "Create a private working directory before rendering." }
        val caps = bridge.capabilities()
        check(caps.available) { caps.reason }
        val sequence = requested.sequence
        val clips = sequence?.timeline?.clips
        require(inputs.size == (clips?.size ?: 1)) { "The staged sources do not match the requested clips." }
        val probes = mutableMapOf<String, Source>()
        suspend fun inspect(source: Source, index: Int): Source {
            val path = paths[index].absolutePath
            val found = probes[path] ?: bridge.probe(path).also { probes[path] = it }
            return found.copy(uri = source.uri, name = source.name)
        }
        val actual = if (clips == null) requested.copy(source = inspect(requested.source, 0)) else {
            val inspected = clips.mapIndexed { index, clip -> clip.copy(source = inspect(clip.source, index)) }
            requested.copy(sequence = requireNotNull(sequence).copy(timeline = EditTimeline(inspected)))
        }
        val problems = JobPlans.validate(actual, caps)
        require(problems.isEmpty()) { problems.joinToString("\n") }
        var settings = JobPlans.settings(actual)
        val expectedDuration = JobPlans.duration(actual)
        val expectedVideo = if (settings.container == Container.M4A) 0 else 1
        val expectedAudio = if (JobPlans.hasAudio(actual)) 1 else 0
        val limit = actual.targetBytes
        val count = if (limit == null) 1 else UploadFit.MAX_ATTEMPTS
        for (index in 1..count) {
            currentCoroutineContext().ensureActive()
            val candidate = File(destination.parentFile, "attempt-${UUID.randomUUID()}.${settings.container.extension}")
            val start = System.nanoTime()
            var arguments = emptyList<String>()
            var reported = false
            try {
                // Every retry recompiles/prepares against the original staged inputs, never a lossy candidate.
                arguments = actual.sequence?.let {
                    bridge.prepareSequence(it, settings, paths.map(File::getAbsolutePath), candidate.absolutePath)
                } ?: bridge.prepare(actual.source, actual.trim, settings, paths.single().absolutePath, candidate.absolutePath)
                onProgress(Progress(0, attempt = index, attempts = count))
                val result = bridge.execute(arguments) { onProgress(it.copy(attempt = index, attempts = count)) }
                check(result.exitCode == 0) { "FFmpeg failed (${result.exitCode}). ${result.diagnostics}" }
                currentCoroutineContext().ensureActive()
                check(candidate.isFile && candidate.length() > 0) { "The encoder did not produce a nonempty output." }
                val facts = bridge.probe(candidate.absolutePath)
                check(facts.videoTracks == expectedVideo && facts.audioTracks == expectedAudio) { "The output track layout differs from the plan." }
                val tolerance = maxOf(200L, 2000L / (settings.fps.takeIf { it > 0 } ?: 24))
                check(facts.durationMs > 0 && abs(facts.durationMs - expectedDuration) <= tolerance) { "Output duration ${facts.durationMs} ms differs from the planned $expectedDuration ms." }
                if (expectedVideo > 0) {
                    check(facts.width > 0 && facts.height > 0 && facts.width % 2 == 0 && facts.height % 2 == 0) { "Output dimensions are invalid." }
                    actual.sequence?.canvas?.let { check(facts.width == it.width && facts.height == it.height) { "Output does not match the movie canvas." } }
                        ?: check(settings.maxHeight == 0 || facts.height <= settings.maxHeight) { "Output exceeded the requested height." }
                }
                val decoded = bridge.execute(listOf("-hide_banner", "-loglevel", "error", "-nostdin", "-xerror", "-i", candidate.absolutePath,
                    "-map", "0:v:0?", "-map", "0:a:0?", "-f", "null", "-")) {}
                check(decoded.exitCode == 0) { "Output failed full decode verification. ${decoded.diagnostics}" }
                currentCoroutineContext().ensureActive()
                val bytes = candidate.length()
                val accepted = limit == null || UploadFit.fits(bytes, limit)
                reported = true
                onAttempt(RenderAttempt(index, settings, arguments, bytes, (System.nanoTime() - start) / 1_000_000, true, accepted))
                if (accepted) {
                    check(candidate.renameTo(destination)) { "The verified render could not be finalized." }
                    return@withContext facts.copy(uri = destination.absolutePath, name = destination.name, bytes = bytes)
                }
                check(index < count) { "Size target was not met after $count verified attempts: $bytes bytes, limit $limit. Nothing was published." }
                settings = UploadFit.retry(settings, expectedAudio > 0, limit!!, bytes)
                    ?: error("No lower supported bitrate remains. Increase the limit or shorten the selection; nothing was published.")
            } catch (error: Exception) {
                if (!reported) onAttempt(RenderAttempt(index, settings, arguments, candidate.length(), (System.nanoTime() - start) / 1_000_000,
                    false, false, error.message ?: error.javaClass.simpleName))
                throw error
            } finally {
                // Native bridge cancellation awaits worker completion before returning here.
                candidate.delete()
            }
        }
        error("No verified candidate was produced.")
    }
}
