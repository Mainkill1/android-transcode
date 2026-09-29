package dev.forma.app.service

import dev.forma.core.JobState
import org.junit.Test

class PowerServiceRulesTest {
    @Test fun powerCancellationMarksInterruptedBeforeReturningJobToQueue() {
        setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING).forEach { state ->
            check(PowerServiceRules.powerStopTransitions(state) == listOf(JobState.INTERRUPTED, JobState.QUEUED))
        }
    }

    @Test fun queuedTerminalAndAlreadyInterruptedJobsAreNotRewritten() {
        setOf(JobState.QUEUED, JobState.COMPLETED, JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED).forEach { state ->
            check(PowerServiceRules.powerStopTransitions(state).isEmpty())
        }
    }
}
