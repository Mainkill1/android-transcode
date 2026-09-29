package dev.forma.ffmpeg

import dev.forma.core.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.*

object ExportRetryChecks {
    fun run() = runBlocking {
        val dir = Files.createTempDirectory("forma-retries-").toFile()
        val output = File(dir, "result.mp4")
        val r = EncodeRequest(VideoFormat.H264, 320, 240, 30.0, 1_000_000)
        val c = CodecCandidate("vendor", r.format, true, Support.UNKNOWN, Support.NO, null, "trial", r)
        val decisions = CodecTrials.plan(r, AccelerationMode.AUTO, setOf(r.format.device, r.format.software), listOf(c))
        val attempts = decisions.map { PreparedAttempt(listOf("-n", "-i", File(dir, "original").absolutePath,
            "-c:v", it.encoder!!, output.absolutePath), it) }
        try {
            var calls = 0
            var verifies = 0
            val events = mutableListOf<AttemptEvent>()
            val result = ExportRetry.run(attempts, output,
                execute = { _, _ ->
                    check(!output.exists()) { "A failed partial must be removed before retry" }
                    calls++; output.writeText(if (calls == 1) "partial" else "valid")
                    if (calls == 1) NativeResult(1, "scoped codec failure", FailureKind.CODEC_INITIALIZATION)
                    else NativeResult(0, "")
                }, verify = { verifies++; check(output.readText() == "valid") }, onAttempt = events::add)
            check(result == attempts[1] && calls == 2 && verifies == 1)
            check(events.map { it.status } == listOf(AttemptStatus.STARTED, AttemptStatus.REJECTED, AttemptStatus.STARTED, AttemptStatus.VERIFIED))
            check(output.readText() == "valid"); output.delete()
            calls = 0
            ExportRetry.run(attempts, output, execute = { _, _ ->
                calls++; output.writeText("x"); NativeResult(0, "")
            }, verify = { if (calls < 3) throw EncodedOutputRejected("wrong dimensions") })
            check(calls == 3); output.delete()
            for (failure in listOf(FailureKind.IO, FailureKind.INVALID_INPUT, FailureKind.UNKNOWN, FailureKind.CANCELLED)) {
                calls = 0
                val error = runCatching { ExportRetry.run(attempts, output, execute = { _, _ ->
                    calls++; output.writeText("partial"); NativeResult(1, "fatal", failure)
                }, verify = {}) }.exceptionOrNull()
                check(error != null && calls == 1 && !output.exists()) { "Do not retry $failure" }
            }
            for (error in listOf(IOException("disk"), IllegalArgumentException("bug"), CancellationException("stop"))) {
                calls = 0
                val caught = runCatching { ExportRetry.run(attempts, output, execute = { _, _ ->
                    calls++; output.writeText("partial"); throw error
                }, verify = {}) }.exceptionOrNull()
                check(caught === error && calls == 1 && !output.exists())
            }
            calls = 0
            ExportRetry.run(attempts, output, execute = { attempt, _ ->
                calls++; output.writeText("x")
                if (attempt.decision?.backend == EncodeBackend.SOFTWARE) NativeResult(0, "")
                else NativeResult(1, "codec", FailureKind.CODEC_INITIALIZATION)
            }, verify = {})
            check(calls == 5); output.delete()
            calls = 0
            val exhausted = runCatching { ExportRetry.run(attempts.dropLast(1), output,
                execute = { _, _ -> calls++; NativeResult(1, "codec", FailureKind.CODEC_INITIALIZATION) }, verify = {}) }.exceptionOrNull()
            check(exhausted != null && calls == 4 && !output.exists())
            // Caller cancellation cannot turn into fallback or delete buffers before native completion.
            val started = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            calls = 0
            val worker = launch {
                ExportRetry.run(attempts, output, execute = { _, _ ->
                    calls++; output.writeText("native in use"); started.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { allowCleanup.await(); check(output.exists()) }
                    }
                }, verify = {})
            }
            started.await(); worker.cancel(); yield()
            check(output.exists() && calls == 1)
            allowCleanup.complete(Unit); worker.join(); check(!output.exists())
            output.writeText("preexisting")
            calls = 0
            check(runCatching { ExportRetry.run(attempts, output, execute = { _, _ -> calls++; NativeResult(0, "") }, verify = {}) }.isFailure)
            check(calls == 0 && output.readText() == "preexisting"); output.delete()
            check(runCatching { ExportRetry.run(emptyList(), output, execute = { _, _ -> NativeResult(0, "") }, verify = {}) }.isFailure)
            check(runCatching { ExportRetry.run(List(14) { attempts.first() }, output, execute = { _, _ -> NativeResult(0, "") }, verify = {}) }.isFailure)
            check(runCatching { ExportRetry.run(listOf(attempts.first().copy(arguments = listOf("/different"))), output,
                execute = { _, _ -> NativeResult(0, "") }, verify = {}) }.isFailure)
            // A successful native return is not sufficient without a nonempty artifact.
            check(runCatching { ExportRetry.run(listOf(attempts.last()), output, execute = { _, _ -> NativeResult(0, "") }, verify = {}) }.isFailure)
            println("Coroutine fallback, cancellation, verification and cleanup checks passed")
        } finally { dir.deleteRecursively() }
    }
}
fun main() = ExportRetryChecks.run()
