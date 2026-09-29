package dev.forma.app.data

import dev.forma.core.*
import dev.forma.ffmpeg.FfmpegBridge
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.*

/** Staging -> native execution -> structural verification -> private publication. */
class FfmpegTranscoder(private val files: MediaFiles, private val bridge: FfmpegBridge) {
    suspend fun run(spec: JobSpec, onState: suspend (JobState) -> Unit, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val directory = files.workDir(spec)
        val temporary = File(directory, "encoded.${spec.settings.container.extension}")
        val published = files.output(spec)
        require(!published.exists()) { "An output already exists for this job. Retry as a new job instead of overwriting it." }
        try {
            val caps = bridge.capabilities()
            require(caps.available) { caps.reason }
            val input = files.stage(spec)
            val inspected = bridge.probe(input.absolutePath)
            val actual = inspected.copy(uri = spec.source.uri, name = spec.source.name)
            val problems = Planner.validate(actual, spec.trim, spec.settings, caps)
            require(problems.isEmpty()) { problems.joinToString("\n") }
            val arguments = bridge.prepare(actual, spec.trim, spec.settings, input.absolutePath, temporary.absolutePath)
            onState(JobState.RUNNING)
            val result = bridge.execute(arguments, onProgress)
            check(result.exitCode == 0) { "FFmpeg failed (${result.exitCode}). ${result.diagnostics}" }
            currentCoroutineContext().ensureActive()
            onState(JobState.VERIFYING)
            check(temporary.isFile && temporary.length() > 0) { "FFmpeg did not produce a non-empty output." }
            val output = bridge.probe(temporary.absolutePath)
            val expectedVideo = if (spec.settings.container == Container.M4A) 0 else 1
            val expectedAudio = if (actual.audioTracks > 0 && spec.settings.audio != AudioEncoder.NONE) 1 else 0
            check(output.videoTracks == expectedVideo && output.audioTracks == expectedAudio) { "The output track layout does not match the plan." }
            val duration = Planner.duration(actual, spec.trim)
            check(output.durationMs > 0 && abs(output.durationMs - duration) <= maxOf(1000L, duration / 20)) { "The output duration does not match the selected range." }
            if (expectedVideo > 0) {
                check(output.width > 0 && output.height > 0 && output.width % 2 == 0 && output.height % 2 == 0) { "The output dimensions are invalid." }
                check(spec.settings.maxHeight == 0 || output.height <= spec.settings.maxHeight) { "The output exceeded the requested height." }
            }
            currentCoroutineContext().ensureActive()
            // Keep publication and its durable state notification together across cancellation.
            // Process death between filesystem rename and queue fsync still needs startup recovery.
            withContext(NonCancellable) {
                check(temporary.renameTo(published)) { "The verified output could not be published." }
                onState(JobState.COMPLETED)
            }
        } finally { directory.deleteRecursively() }
    }
}
