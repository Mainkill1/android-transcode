package dev.forma.ffmpeg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionAwaitTest {
    @Test fun callbackBeforeAwaitReturnsItsResult() = runBlocking {
        val value = awaitNativeSession<String, String>(
            start = { complete -> complete("finished"); "handle" },
            cancel = { error("Completed sessions must not be cancelled") })
        assertEquals("finished", value)
    }

    @Test fun cancellationWaitsForNativeCompletion() = runBlocking {
        val started = CompletableDeferred<(String) -> Unit>()
        val cancelled = CompletableDeferred<String>()
        val job = async {
            awaitNativeSession<String, String>(
                start = { complete -> started.complete(complete); "handle" },
                cancel = { cancelled.complete(it) })
        }
        val complete = started.await()
        job.cancel()
        assertEquals("handle", cancelled.await())
        assertFalse(job.isCompleted)
        complete("native stopped")
        job.join()
        assertTrue(job.isCancelled)
    }

    @Test fun cancelledCallerNeverStartsNativeSession() = runBlocking {
        val job = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            awaitNativeSession<String, String>(
                start = { error("Native session started after cancellation") },
                cancel = { error("No native session exists") })
        }
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
    }
}
