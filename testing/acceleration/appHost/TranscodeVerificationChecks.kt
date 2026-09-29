package dev.forma.app.data

import dev.forma.core.*
import dev.forma.ffmpeg.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*

fun main() = runBlocking {
    val root = Files.createTempDirectory("forma-verification-").toFile()
    try {
        val original = File(root, "original.mp4").apply { writeText("immutable source") }
        val files = MediaFiles(root, original)
        val source = Source("original", "original.mp4", 2000, 320, 240, 1, 1)
        val caps = Capabilities(true, "", setOf("libx264", "aac"), setOf("mp4"), setOf("scale"))
        fun spec(id: String) = JobSpec(id, source, Trim(), Settings())
        var decodeExit = 1
        var decodeCalls = 0
        var decodeArguments = emptyList<String>()
        val bridge = object : FfmpegBridge {
            override suspend fun capabilities() = caps
            override suspend fun probe(localPath: String) = source.copy(uri = localPath)
            override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult {
                if (arguments.last() == "-") {
                    decodeCalls++
                    decodeArguments = arguments
                    return NativeResult(decodeExit, "invalid encoded packets")
                }
                File(arguments.last()).writeText("readable headers, invalid encoded packets")
                return NativeResult(0, "")
            }
        }
        val states = mutableListOf<JobState>()
        val bad = spec("corrupt")
        val error = runCatching { FfmpegTranscoder(files, bridge).run(bad, states::add) {} }.exceptionOrNull()
        check(error != null) { "Successful encoding with readable headers must not publish output whose packets fail decoding." }
        check(JobState.COMPLETED !in states && !files.output(bad).exists()) { "Decode failure must never publish COMPLETED." }
        check(!File(root, "work/corrupt").exists()) { "Failed output and staging files must be removed." }
        check(original.readText() == "immutable source")
        decodeExit = 0
        states.clear()
        val good = spec("valid")
        FfmpegTranscoder(files, bridge).run(good, states::add) {}
        check(states.last() == JobState.COMPLETED && files.output(good).isFile)
        check(decodeCalls == 2 && "-xerror" in decodeArguments && "-map" in decodeArguments)
        check(decodeArguments[decodeArguments.indexOf("-i") + 1].endsWith("work/valid/encoded.mp4"))
        check(!File(root, "work/valid").exists() && original.readText() == "immutable source")
        withTimeout(5000) {
            val enteredDecode = CompletableDeferred<Unit>()
            val enteredCleanup = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val cancellingBridge = object : FfmpegBridge by bridge {
                override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult {
                    if (arguments.last() != "-") return bridge.execute(arguments, onProgress)
                    val encoded = File(arguments[arguments.indexOf("-i") + 1])
                    enteredDecode.complete(Unit)
                    try { awaitCancellation() }
                    finally {
                        withContext(NonCancellable) {
                            enteredCleanup.complete(Unit)
                            allowCleanup.await()
                            check(encoded.isFile) { "The transcoder must retain native-owned output until decode cancellation finishes." }
                        }
                    }
                }
            }
            states.clear()
            val cancelled = spec("cancelled")
            val worker = launch { FfmpegTranscoder(files, cancellingBridge).run(cancelled, states::add) {} }
            enteredDecode.await()
            worker.cancel()
            enteredCleanup.await()
            check(File(root, "work/cancelled/encoded.mp4").isFile && !files.output(cancelled).exists())
            allowCleanup.complete(Unit)
            worker.join()
            check(JobState.COMPLETED !in states && !files.output(cancelled).exists() && !File(root, "work/cancelled").exists())
        }
        println("Production decode-before-publication and cleanup checks passed")
    } finally { root.deleteRecursively() }
}
