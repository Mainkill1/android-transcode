package dev.forma.app.data

import dev.forma.core.*
import dev.forma.ffmpeg.FfmpegBridge
import dev.forma.ffmpeg.FfmpegRenderSession
import dev.forma.ffmpeg.RenderAttempt
import java.io.File
import kotlinx.coroutines.*

/** Existing service owns one lease from staging through verification and durable publication. */
class FfmpegTranscoder(private val files: MediaFiles, private val bridge: FfmpegBridge) {
    suspend fun run(spec: JobSpec, onState: suspend (JobState) -> Unit, onProgress: (Progress) -> Unit,
        onAttempt: (RenderAttempt) -> Unit = {}) = withContext(Dispatchers.IO) {
        val directory = files.workDir(spec)
        val temporary = File(directory, "encoded.${spec.settings.container.extension}")
        val published = files.output(spec)
        require(!published.exists()) { "An output already exists for this job. Retry as a new job instead of overwriting it." }
        try {
            val inputs = files.stageInputs(spec)
            onState(JobState.RUNNING)
            FfmpegRenderSession(bridge).render(spec, inputs, temporary, onProgress, onAttempt)
            currentCoroutineContext().ensureActive()
            onState(JobState.VERIFYING)
            currentCoroutineContext().ensureActive()
            // Publication and its durable notification retain the existing cancellation transaction.
            withContext(NonCancellable) {
                check(temporary.renameTo(published)) { "The verified output could not be published." }
                onState(JobState.COMPLETED)
            }
        } finally { directory.deleteRecursively() }
    }
}
