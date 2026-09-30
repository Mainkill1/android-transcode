package dev.forma.ffmpeg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Owns a native session until its completion callback, including after coroutine cancellation. */
internal suspend fun <Handle, Result> awaitNativeSession(
    start: (complete: (Result) -> Unit) -> Handle,
    cancel: (Handle) -> Unit
): Result {
    currentCoroutineContext().ensureActive()
    val done = CompletableDeferred<Result>()
    val handle = start { done.complete(it) }
    val result = try {
        done.await()
    } catch (stopped: CancellationException) {
        withContext(NonCancellable) {
            try {
                cancel(handle)
            } finally {
                done.await()
            }
        }
        throw stopped
    }
    currentCoroutineContext().ensureActive()
    return result
}
