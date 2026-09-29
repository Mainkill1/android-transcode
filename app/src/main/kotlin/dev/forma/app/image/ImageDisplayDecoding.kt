package dev.forma.app.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Blocking platform decoders share one slot, including superseded UI requests. */
internal object ImageDisplayDecoding {
    private val slot=Mutex()
    suspend fun <T> decode(block:()->T):T=withContext(Dispatchers.IO) {
        slot.withLock {
            ensureActive()
            val result=block()
            ensureActive()
            result
        }
    }
}
