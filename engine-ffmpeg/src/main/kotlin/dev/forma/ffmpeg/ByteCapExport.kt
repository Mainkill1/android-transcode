package dev.forma.ffmpeg

import dev.forma.core.*
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One budget includes codec failures and verified size retries; every attempt rereads the original. */
object ByteCapExport {
    suspend fun run(
        bridge: FfmpegBridge, source: Source, trim: Trim, requested: Settings,
        input: File, output: File, targetBytes: Long?,
        onProgress: (Progress) -> Unit = {}, onAttempt: (AttemptEvent) -> Unit = {},
        durationMs: Long = Planner.outputDuration(source,trim,requested),
        transformAttempt: suspend (Settings,PreparedAttempt) -> PreparedAttempt = { _,attempt -> attempt },
        verifyAttempt: (suspend (Settings,PreparedAttempt) -> Unit)? = null,
        executeAttempt: (suspend (Settings,PreparedAttempt,(Progress)->Unit) -> NativeResult)? = null
    ): PreparedAttempt {
        require(input.isFile && input.canonicalFile != output.canonicalFile && !output.exists()) {
            "Separate original and non-overwriting output are required."
        }
        val audio = source.audioTracks > 0 && requested.audio != AudioEncoder.NONE
        var settings = targetBytes?.let { UploadFit.initial(requested, durationMs, audio, it) } ?: requested
        var used = 0
        var selected: EncodeDecision? = null
        var softwareOnly = false
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val all = bridge.prepareAttempts(source, trim, settings, input.absolutePath, output.absolutePath).map { transformAttempt(settings,it) }
                val ordered = selected?.let { prior -> all.sortedBy { attempt ->
                    val now = attempt.decision
                    if (now?.backend == prior.backend && now?.codecName == prior.codecName &&
                        now?.encoder == prior.encoder && now?.bufferFormat == prior.bufferFormat && now?.bitrateMode == prior.bitrateMode) 0 else 1
                } } ?: all
                val remaining = if (targetBytes == null) ordered.size else UploadFit.MAX_ATTEMPTS - used
                check(remaining > 0) { "Size/codec attempt budget exhausted. Nothing was published." }
                val retained = if (softwareOnly || selected?.backend == EncodeBackend.SOFTWARE) ordered.filter { it.decision?.backend == EncodeBackend.SOFTWARE } else ordered
                val fallback = retained.firstOrNull { it.decision?.backend == EncodeBackend.SOFTWARE }
                val routes = if (targetBytes != null && requested.video.automatic && fallback != null &&
                    selected?.backend == EncodeBackend.MEDIACODEC && remaining == 1) listOf(fallback)
                else if (targetBytes != null && requested.video.automatic && fallback != null && retained.size > remaining)
                    retained.filter { it !== fallback }.take(remaining - 1) + fallback
                else retained.take(remaining)
                var started = 0
                val accepted = ExportRetry.run(routes, output,
                    execute = { attempt,progress -> executeAttempt?.invoke(settings,attempt,progress) ?: bridge.execute(attempt.arguments,progress) },
                    verify = { attempt ->
                        if(verifyAttempt!=null) verifyAttempt(settings,attempt)
                        else verifyEncodedOutput(bridge,source,trim,settings,output,attempt)
                    },
                    onProgress = onProgress,
                    onAttempt = { event ->
                        if (event.status == AttemptStatus.STARTED) started++
                        onAttempt(event.copy(number = used + event.number,
                            total = if (targetBytes == null) routes.size else UploadFit.MAX_ATTEMPTS))
                    })
                used += started
                currentCoroutineContext().ensureActive()
                if (targetBytes == null || UploadFit.fits(output.length(), targetBytes)) return accepted
                val bytes = output.length()
                check(output.delete()) { "Cannot remove the verified oversized candidate." }
                check(used < UploadFit.MAX_ATTEMPTS) { "Size limit was not met after $used attempts. Nothing was published." }
                val next = UploadFit.retry(settings, audio, targetBytes, bytes)
                if (next != null) settings = next
                else if (requested.video.automatic && accepted.decision?.backend == EncodeBackend.MEDIACODEC &&
                    all.any { it.decision?.backend == EncodeBackend.SOFTWARE }) {
                    // Some devices enforce a bitrate floor. An Automatic request can retain
                    // its codec/geometry/trim/audio and try software at this same budget.
                    softwareOnly = true
                } else error(if(requested.container.audioOnly && !requested.audio.usesBitrate) "The verified ${requested.container.name} output exceeds the byte limit; its lossless/sample policy cannot be reduced by a bitrate retry. Increase the limit or shorten the selection; nothing was published." else "No lower supported bitrate remains. Increase the limit; nothing was published.")
                selected = accepted.decision
            }
        } catch (error: Throwable) {
            // Bridge teardown completes before returning here, including cancellation.
            if (output.exists() && !output.delete()) error.addSuppressed(java.io.IOException("Could not remove private output."))
            throw error
        }
    }
}
