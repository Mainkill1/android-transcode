package dev.forma.core

/** Presentation grouping only: persistence and job state transitions stay unchanged. */
object QueueLists {
    fun inQueue(state: JobState): Boolean = when (state) {
        JobState.QUEUED, JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING -> true
        JobState.COMPLETED, JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED -> false
    }

    fun inFinished(state: JobState): Boolean = !inQueue(state)
}
