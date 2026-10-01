package dev.forma.ffmpeg

import dev.forma.core.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class NativeResult(val exitCode: Int, val diagnostics: String, val failure: FailureKind = FailureKind.UNKNOWN)
data class PreparedAttempt(val arguments: List<String>, val decision: EncodeDecision? = null)
enum class AttemptStatus { STARTED, REJECTED, VERIFIED, FAILED }
data class AttemptEvent(val number: Int, val total: Int, val attempt: PreparedAttempt,
                        val status: AttemptStatus, val reason: String = "")

/** Only explicit media-verification failures get this type; never wrap I/O or cancellation in it. */
class EncodedOutputRejected(message: String) : Exception(message)
private class CodecInitializationRejected : Exception("The selected codec configuration failed to initialize.")

/** Runs complete attempts from the original; never resumes or feeds back a failed lossy output. */
object ExportRetry {
    suspend fun run(
        attempts: List<PreparedAttempt>, output: File,
        execute: suspend (PreparedAttempt, (Progress) -> Unit) -> NativeResult,
        verify: suspend (PreparedAttempt) -> Unit,
        onProgress: (Progress) -> Unit = {},
        onAttempt: (AttemptEvent) -> Unit = {}
    ): PreparedAttempt {
        require(attempts.isNotEmpty()) { "No executable encoder route is available for this job." }
        require(attempts.size <= CodecTrials.MAX_DEVICE_ATTEMPTS + 1) { "Codec attempt budget exceeded." }
        require(attempts.all { it.arguments.lastOrNull() == output.absolutePath && "-y" !in it.arguments }) {
            "Every attempt must target the same private, non-overwriting output."
        }
        require(!output.exists()) { "Refusing to replace a preexisting output." }
        for ((index, attempt) in attempts.withIndex()) {
            currentCoroutineContext().ensureActive()
            onAttempt(AttemptEvent(index + 1, attempts.size, attempt, AttemptStatus.STARTED))
            onProgress(Progress(0))
            try {
                val result = execute(attempt, onProgress)
                currentCoroutineContext().ensureActive()
                if (result.exitCode != 0) {
                    if (result.failure == FailureKind.CANCELLED) throw CancellationException("Native export cancelled.")
                    if (attempt.decision?.backend == EncodeBackend.MEDIACODEC && result.failure == FailureKind.CODEC_INITIALIZATION)
                        throw CodecInitializationRejected()
                    error("FFmpeg failed (${result.exitCode}, ${result.failure}). ${result.diagnostics}")
                }
                if (!output.isFile || output.length() == 0L) throw EncodedOutputRejected("No nonempty encoded output was produced.")
                verify(attempt)
                currentCoroutineContext().ensureActive()
                onAttempt(AttemptEvent(index + 1, attempts.size, attempt, AttemptStatus.VERIFIED))
                return attempt
            } catch (error: Exception) {
                // execute MUST await native teardown before returning/throwing, including cancellation.
                // Do not race another encoder or delete a file while FFmpeg still owns it.
                if (output.exists() && !output.delete()) {
                    error.addSuppressed(IOException("Could not remove the failed private output."))
                    throw error
                }
                if (error is CancellationException) throw error
                val retryable = attempt.decision?.backend == EncodeBackend.MEDIACODEC &&
                    (error is CodecInitializationRejected || error is EncodedOutputRejected)
                val canRetry = retryable && index < attempts.lastIndex
                onAttempt(AttemptEvent(index + 1, attempts.size, attempt,
                    if (canRetry) AttemptStatus.REJECTED else AttemptStatus.FAILED,
                    when {
                        canRetry -> error.message.orEmpty()
                        retryable -> "Codec routes exhausted: ${error.message.orEmpty()}"
                        else -> "Non-codec failure: ${error.javaClass.simpleName}"
                    }))
                if (!canRetry) throw error
            }
        }
        error("No verified export was produced.")
    }
}
