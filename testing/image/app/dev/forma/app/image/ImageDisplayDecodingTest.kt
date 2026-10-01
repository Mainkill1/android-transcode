package dev.forma.app.image

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ImageDisplayDecodingTest {
    @Test fun canceledQueuedDisplayDecodeNeverAllocates() = runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        val first=async { ImageDisplayDecoding.decode { entered.complete(Unit);check(release.await(5,TimeUnit.SECONDS));"first" } }
        withTimeout(5000){entered.await()}
        var decoded=false
        val stale=async(start=CoroutineStart.UNDISPATCHED){ImageDisplayDecoding.decode{decoded=true;"stale"}}
        try { withTimeout(5000){stale.cancelAndJoin()};assertFalse(decoded) }
        finally { release.countDown() }
        assertEquals("first",withTimeout(5000){first.await()})
        assertEquals("latest",ImageDisplayDecoding.decode{"latest"})
    }
}
