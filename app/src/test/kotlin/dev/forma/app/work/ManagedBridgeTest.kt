package dev.forma.app.work

import dev.forma.core.*
import dev.forma.ffmpeg.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ManagedBridgeTest {
    @Test fun cacheDoesNotWaitForEncodeAndProbeWaitsForNativeCleanup() = runBlocking {
        withTimeout(5000) {
            val capabilityCalls = AtomicInteger()
            val probeCalls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fake = object : FfmpegBridge {
                override suspend fun capabilities(): Capabilities { capabilityCalls.incrementAndGet(); return Capabilities(available = true) }
                override suspend fun probe(localPath: String): Source { probeCalls.incrementAndGet(); return Source(localPath, "test", 1000) }
                override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult {
                    entered.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                }
            }
            val bridge = ManagedFfmpegBridge(fake, 8)
            bridge.capabilities()
            val encode = launch { bridge.execute(emptyList()) {} }
            entered.await()
            withTimeout(1000) { bridge.capabilities() }
            assertEquals(1, capabilityCalls.get())
            val probe = async { bridge.probe("input") }
            encode.cancel()
            cleaning.await()
            assertEquals(0, probeCalls.get())
            release.complete(Unit)
            encode.join()
            probe.await()
            assertEquals(1, probeCalls.get())
        }
    }
}
