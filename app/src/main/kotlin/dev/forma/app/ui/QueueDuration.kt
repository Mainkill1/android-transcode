package dev.forma.app.ui

import dev.forma.core.JobPlans
import dev.forma.core.JobSpec

/** Preserved future graphs remain visible even when this build cannot plan their duration. */
internal fun queueDurationLabel(job: JobSpec): String =
    runCatching { JobPlans.duration(job) }.getOrNull()?.let(::mediaTime) ?: "Duration unavailable"
