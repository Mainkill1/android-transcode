package dev.forma.app.data

import dev.forma.core.*
import dev.forma.ffmpeg.FfmpegBridge
import java.io.File
import dev.forma.core.audio.AudioArtifactVerification
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
            val verification = AudioArtifactVerification.problems(actual, spec.trim, spec.settings, output, temporary.length())
            check(verification.isEmpty()) { verification.joinToString("\n") }
            val decoded = bridge.execute(listOf("-hide_banner", "-nostdin", "-v", "error", "-xerror", "-i", temporary.absolutePath,
                "-map", "0:v?", "-map", "0:a?", "-f", "null", "-")) {}
            check(decoded.exitCode == 0) { "The output could not be fully decoded. ${decoded.diagnostics}" }
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
