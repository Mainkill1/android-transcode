package dev.forma.app.service

import dev.forma.core.JobState

/** Power cancellation is recoverable, but never resumes or appends to a partial output. */
object PowerServiceRules {
    private val active = setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING)

    fun powerStopTransitions(state: JobState): List<JobState> =
        if (state in active) listOf(JobState.INTERRUPTED, JobState.QUEUED) else emptyList()
}
