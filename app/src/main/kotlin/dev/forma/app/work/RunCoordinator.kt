package dev.forma.app.work

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunMode { IDLE, RUNNING, DRAINING, STOPPING }
enum class StopReason { USER, SERVICE_STOPPED, TIME_LIMIT, POWER_POLICY }
data class RunState(val mode: RunMode = RunMode.IDLE, val id: Long? = null, val error: String? = null)

/** Process-wide ownership outlives Activity/Service recreation and native cancellation cleanup. */
class RunCoordinator(private val scope: CoroutineScope) {
    class PreviewLease internal constructor(private val owner:RunCoordinator,internal val preempt:()->Unit):AutoCloseable {
        internal val released=CompletableDeferred<Unit>()
        override fun close() {owner.releasePreview(this)}
    }
    class Ticket internal constructor(val id: Long) {
        lateinit var job: Job
            internal set
        @Volatile internal var draining = false
        @Volatile var stopReason: StopReason? = null
            internal set
        @Volatile internal var failure: String? = null
        fun canTakeNext(): Boolean = !draining && stopReason == null
    }
    private val mutable = MutableStateFlow(RunState())
    val state = mutable.asStateFlow()
    private var nextId = 0L
    private var current: Ticket? = null
    private var preview:PreviewLease?=null

    /** Atomically reserve idle native-preview time. Foreground work always preempts it. */
    @Synchronized fun tryAcquirePreview(onPreempt:()->Unit):PreviewLease? {
        if(current!=null || preview!=null)return null
        return PreviewLease(this,onPreempt).also {preview=it}
    }

    @Synchronized private fun releasePreview(lease:PreviewLease) {
        if(preview===lease)preview=null
        lease.released.complete(Unit)
    }

    @Synchronized fun start(work: suspend (Ticket) -> Unit): Ticket? {
        if (current != null || !scope.isActive) return null
        val interruptedPreview=preview
        val ticket = Ticket(++nextId)
        ticket.job = scope.launch(start = CoroutineStart.LAZY) {
            try { interruptedPreview?.released?.await();work(ticket) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { ticket.failure = error.message ?: "Queue processing failed." }
        }
        current = ticket
        mutable.value = RunState(RunMode.RUNNING, ticket.id)
        interruptedPreview?.let {lease ->runCatching {lease.preempt()}.onFailure {
            ticket.failure="Could not stop the preview before conversion: ${it.message}"
            lease.close()
        }}
        ticket.job.invokeOnCompletion {
            synchronized(this) {
                if (current === ticket) {
                    current = null
                    mutable.value = RunState(error = ticket.failure)
                }
            }
        }
        ticket.job.start()
        return ticket
    }

    @Synchronized fun finishCurrent() {
        val ticket = current ?: return
        if (ticket.stopReason != null) return
        ticket.draining = true
        mutable.value = RunState(RunMode.DRAINING, ticket.id)
    }

    /** A stale service may only stop its own ticket. Cancellation is not slot release. */
    @Synchronized fun stop(id: Long? = null, reason: StopReason = StopReason.USER) {
        val ticket = current ?: return
        if (id != null && ticket.id != id) return
        val existing = ticket.stopReason
        if (existing != null) {
            // Explicit user intent wins over a recoverable power wait while native cleanup
            // still owns the slot. System/service signals never replace a user cancellation.
            if (existing == StopReason.POWER_POLICY && reason == StopReason.USER) {
                ticket.stopReason = StopReason.USER
            }
            return
        }
        ticket.stopReason = reason
        mutable.value = RunState(RunMode.STOPPING, ticket.id)
        ticket.job.cancel()
    }
}
