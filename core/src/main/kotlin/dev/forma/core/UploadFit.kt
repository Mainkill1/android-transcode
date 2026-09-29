package dev.forma.core

/** Bitrate budgets are estimates. Only the measured, fully verified file can satisfy a byte cap. */
object UploadFit {
    const val MAX_ATTEMPTS = 4
    const val MAX_TARGET_BYTES = 50_000_000_000L
    fun fits(bytes: Long, target: Long): Boolean = bytes > 0 && bytes < target
    fun validateTarget(target: Long) { require(target in 65_536..MAX_TARGET_BYTES) { "Size limit must be 65536–50000000000 decimal bytes." } }
    fun initial(settings: Settings, durationMs: Long, hasAudio: Boolean, target: Long): Settings {
        validateTarget(target)
        require(durationMs > 0) { "Read the edited duration before budgeting." }
        require(!hasAudio || settings.audio != AudioEncoder.FLAC) { "A target size requires bitrate-controlled audio; choose AAC/Opus or remove the limit." }
        val reserved = maxOf(32_768L, target / 100)
        val kbps = ((target - reserved) * 8 / durationMs * 94 / 100).coerceAtMost(200_512).toInt()
        val video = settings.container != Container.M4A
        val audioKbps = if (!hasAudio) settings.audioKbps else if (video) minOf(settings.audioKbps, maxOf(32, kbps / 5)) else minOf(settings.audioKbps, kbps)
        val videoKbps = if (video) kbps - if (hasAudio) audioKbps else 0 else settings.videoKbps
        require((!hasAudio || audioKbps >= 32) && (!video || videoKbps >= 100)) {
            "This size limit cannot retain the selected duration at the minimum supported bitrates. Shorten it or choose a larger limit."
        }
        return settings.copy(rateControl = RateControl.BITRATE, videoKbps = videoKbps.coerceAtMost(200_000), audioKbps = audioKbps)
    }
    /** null means no lower supported bitrate remains; never mute, shorten, truncate, or re-encode a prior output. */
    fun retry(settings: Settings, hasAudio: Boolean, target: Long, actualBytes: Long): Settings? {
        validateTarget(target)
        require(actualBytes >= target) { "Only a valid oversized candidate can be retried." }
        val ratio = (target.toDouble() / actualBytes * 0.90).coerceAtMost(0.90)
        val video = settings.container != Container.M4A
        val next = settings.copy(
            videoKbps = if (video) maxOf(100, (settings.videoKbps * ratio).toInt()) else settings.videoKbps,
            audioKbps = if (hasAudio) maxOf(32, (settings.audioKbps * ratio).toInt()) else settings.audioKbps
        )
        return next.takeUnless { it == settings }
    }
}

/** All callers use the same duration, budget and validation for a single clip or a composed movie. */
object JobPlans {
    fun sourceUris(job: JobSpec): Set<String> = job.sequence?.timeline?.clips?.map { it.source.uri }?.toSet() ?: setOf(job.source.uri)
    fun duration(job: JobSpec): Long = job.sequence?.let(SequencePlanner::duration)
        ?: Planner.outputDuration(job.source, job.trim, job.settings)
    fun hasAudio(job: JobSpec): Boolean = job.sequence?.let { SequencePlanner.hasAudio(it, job.settings) }
        ?: (job.source.audioTracks > 0 && job.settings.audio != AudioEncoder.NONE)
    fun settings(job: JobSpec): Settings {
        val base = job.sequence?.let { job.settings.copy(maxHeight = it.canvas.height, fps = it.canvas.fps) } ?: job.settings
        return job.targetBytes?.let { UploadFit.initial(base, duration(job), hasAudio(job), it) } ?: base
    }
    fun validate(job: JobSpec, caps: Capabilities? = null): List<String> = try {
        val s = settings(job)
        job.sequence?.let { SequencePlanner.validate(it, s, caps) } ?: Planner.validate(job.source, job.trim, s, caps)
    } catch (error: IllegalArgumentException) { listOf(error.message ?: "Invalid export plan.") }
}
