package dev.forma.app.work

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test

class WorkRuntimeTest {
    @Test fun completedWorkerRetainsSlotUntilStatePublication() = runBlocking {
        withTimeout(5000) { completionPublicationChecks() }
    }
    @Test fun cancellationAndProgressRaces() = runBlocking { withTimeout(5000) { workRuntimeChecks() } }
    @Test fun foregroundRunPreemptsAndWaitsForPreviewCleanup() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val runs=RunCoordinator(scope)
        val preempted=CompletableDeferred<Unit>()
        val entered=CompletableDeferred<Unit>()
        try {
            val preview=runs.tryAcquirePreview {preempted.complete(Unit)}!!
            val conversion=runs.start {entered.complete(Unit)}!!
            withTimeout(1000) {preempted.await()}
            check(!entered.isCompleted) {"Conversion started before preview cleanup released its slot"}
            preview.close()
            withTimeout(1000) {entered.await();conversion.job.join()}
            withTimeout(1000) {runs.state.first {it.mode==RunMode.IDLE}}
            runs.tryAcquirePreview {}!!.close()
        } finally {scope.cancel()}
    }
}
