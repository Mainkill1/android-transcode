package dev.forma.app.work

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RunMode { IDLE, RUNNING, DRAINING, STOPPING }
enum class StopReason { USER, SERVICE_STOPPED, TIME_LIMIT, POWER_POLICY }
data class RunState(val mode: RunMode = RunMode.IDLE, val id: Long? = null, val error: String? = null)

/** Process-wide ownership outlives Activity/Service recreation and native cancellation cleanup. */
class RunCoordinator(private val scope: CoroutineScope) {
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

    @Synchronized fun start(work: suspend (Ticket) -> Unit): Ticket? {
        if (current != null || !scope.isActive) return null
        val ticket = Ticket(++nextId)
        ticket.job = scope.launch(start = CoroutineStart.LAZY) {
            try { work(ticket) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { ticket.failure = error.message ?: "Queue processing failed." }
        }
        current = ticket
        mutable.value = RunState(RunMode.RUNNING, ticket.id)
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
        if (id != null && ticket.id != id || ticket.stopReason != null) return
        ticket.stopReason = reason
        mutable.value = RunState(RunMode.STOPPING, ticket.id)
        ticket.job.cancel()
    }
}
