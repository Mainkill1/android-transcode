package dev.forma.app.work

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger

private suspend fun RunCoordinator.awaitIdle(): RunState =
    withTimeout(1000) { state.first { it.mode == RunMode.IDLE } }

/** Force the worker-completed / coordinator-callback-pending ordering observed in CI. */
suspend fun completionPublicationChecks() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runs = RunCoordinator(scope)
    val release = CompletableDeferred<Unit>()
    try {
        val failed = runs.start { release.await(); error("worker failure") } ?: error("No slot")
        synchronized(runs) {
            release.complete(Unit)
            val deadline = System.nanoTime() + 1_000_000_000L
            while (!failed.job.isCompleted && System.nanoTime() < deadline) Thread.yield()
            check(failed.job.isCompleted) { "Worker did not complete while its publication callback was held" }
            runBlocking { withTimeout(1000) { failed.job.join() } }
            // Job.join's completed fast path does not wait for the synchronized callback.
            check(runs.state.value.mode == RunMode.RUNNING && runs.state.value.id == failed.id) {
                "The pending publication must retain this ticket's ownership"
            }
            check(runs.start {} == null) { "A completed ticket still owns the slot until publication" }
        }
        val released = runs.awaitIdle()
        check(released.error == "worker failure")
        val recovery = runs.start {} ?: error("Publication did not release the slot")
        recovery.job.join()
        runs.awaitIdle()
    } finally {
        scope.cancel()
    }
}

suspend fun workRuntimeChecks() = coroutineScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runs = RunCoordinator(scope)
    val entered = CompletableDeferred<Unit>()
    val cleanup = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val first = runs.start {
        entered.complete(Unit)
        try { awaitCancellation() }
        finally { withContext(NonCancellable) { cleanup.complete(Unit); release.await() } }
    } ?: error("First run rejected")
    entered.await()
    check(runs.start {} == null)
    runs.finishCurrent()
    check(runs.state.value.mode == RunMode.DRAINING)
    check(!first.canTakeNext())
    runs.stop()
    cleanup.await()
    check(runs.state.value.mode == RunMode.STOPPING)
    check(runs.start {} == null)
    release.complete(Unit)
    first.job.join()
    runs.awaitIdle()
    val second = runs.start { delay(50) } ?: error("Slot was not released")
    runs.stop(first.id)
    second.job.join()
    runs.awaitIdle()
    check(!second.job.isCancelled)
    val failed = runs.start { error("worker failure") } ?: error("No slot")
    failed.job.join()
    runs.awaitIdle()
    check(runs.state.value.mode == RunMode.IDLE)
    check(runs.state.value.error == "worker failure")
    val active = AtomicInteger()
    val maximum = AtomicInteger()
    val finishRace = CompletableDeferred<Unit>()
    val starts = (1..100).map { async(Dispatchers.Default) {
        runs.start {
            val n = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, n) }
            finishRace.await(); active.decrementAndGet()
        }
    } }.awaitAll().filterNotNull()
    finishRace.complete(Unit)
    starts.forEach { it.job.join() }
    check(maximum.get() == 1)
    check(starts.size == 1)
    scope.cancel()

    // A user pressing Stop during power-policy cleanup must prevent an automatic requeue.
    val precedenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val precedenceRuns = RunCoordinator(precedenceScope)
    val precedenceEntered = CompletableDeferred<Unit>()
    val precedenceCleanup = CompletableDeferred<Unit>()
    val precedenceRelease = CompletableDeferred<Unit>()
    val precedence = precedenceRuns.start {
        precedenceEntered.complete(Unit)
        try { awaitCancellation() }
        finally {
            withContext(NonCancellable) {
                precedenceCleanup.complete(Unit)
                precedenceRelease.await()
            }
        }
    } ?: error("Precedence run rejected")
    precedenceEntered.await()
    precedenceRuns.stop(precedence.id, StopReason.POWER_POLICY)
    precedenceCleanup.await()
    check(precedence.stopReason == StopReason.POWER_POLICY)
    precedenceRuns.stop(precedence.id, StopReason.USER)
    check(precedence.stopReason == StopReason.USER) {
        "Explicit user Stop must override a pending power-policy requeue"
    }
    precedenceRuns.stop(precedence.id, StopReason.TIME_LIMIT)
    check(precedence.stopReason == StopReason.USER) {
        "A later system signal must not replace explicit user intent"
    }
    precedenceRelease.complete(Unit)
    precedence.job.join()
    precedenceScope.cancel()

    var now = 0L
    var calls = 0
    val gate = ProgressGate(250) { now }
    repeat(1000) { if (gate.accept("a")) calls++ }
    check(calls == 1)
    now = 249; check(!gate.accept("a"))
    now = 250; check(gate.accept("a"))
    check(gate.accept("b"))
    now = 0; check(gate.accept("b"))
    println("Run exclusivity, cleanup race, stop precedence, drain, stale stop, error recovery, 100-way start race and progress flood checks passed")
}
fun main() = runBlocking { withTimeout(5000) { completionPublicationChecks(); workRuntimeChecks() } }
