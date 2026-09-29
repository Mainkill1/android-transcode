package dev.forma.app.audio

import dev.forma.ffmpeg.audio.AudioPreviewResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Graph-owned background work. Replacement/export waits for native cancellation cleanup. */
class AudioPreviewController(private val scope:CoroutineScope) {
    private val updates=Mutex()
    private var task:Job?=null
    @Volatile private var generation=0L
    fun request(render:suspend ()->AudioPreviewResult,onResult:(AudioPreviewResult)->Unit,onError:(String)->Unit) {
        val token=++generation
        scope.launch {
            updates.withLock {
                task?.cancelAndJoin()
                if(token!=generation)return@withLock
                task=scope.launch {
                    try { val result=render();ensureActive();if(token==generation)onResult(result) }
                    catch(e:CancellationException) { throw e }
                    catch(e:Exception) { if(token==generation)onError(e.message ?: "Preview failed.") }
                }
            }
        }
    }
    fun invalidate() { generation++;task?.cancel() }
    suspend fun cancelAndJoin() { generation++;updates.withLock { task?.cancelAndJoin();task=null } }
}
