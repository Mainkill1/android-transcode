package dev.forma.core

/** Bitrate budgets are estimates. Only the measured, fully verified file can satisfy a byte cap. */
object UploadFit {
    const val MAX_ATTEMPTS = 4
    const val MAX_TARGET_BYTES = 2_000_000_000L
    fun effective(source: Source, trim: Trim, settings: Settings, target: Long?): Settings =
        target?.let { initial(settings, Planner.outputDuration(source,trim,settings), source.audioTracks > 0 && settings.audio != AudioEncoder.NONE, it) } ?: settings
    fun fits(bytes: Long, target: Long): Boolean = bytes > 0 && bytes < target
    fun validateTarget(target: Long) { require(target in 32_000..MAX_TARGET_BYTES) { "Size limit must be 32000–2000000000 decimal bytes." } }
    fun initial(settings: Settings, durationMs: Long, hasAudio: Boolean, target: Long): Settings {
        validateTarget(target)
        require(durationMs > 0) { "Read the edited duration before budgeting." }
        // A fixed/lossless audio policy gets one measured candidate, never fictitious bitrate retries.
        if(settings.container.audioOnly && hasAudio && !settings.audio.usesBitrate) return settings
        val reserved = maxOf(32_768L, target / 100)
        val kbps = ((target - reserved) * 8 / durationMs * 94 / 100).coerceAtMost(200_512).toInt()
        val video = !settings.container.audioOnly
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
        if(settings.container.audioOnly && hasAudio && !settings.audio.usesBitrate) return null
        val ratio = (target.toDouble() / actualBytes * 0.90).coerceAtMost(0.90)
        val video = !settings.container.audioOnly
        val next = settings.copy(
            videoKbps = if (video) maxOf(100, (settings.videoKbps * ratio).toInt()) else settings.videoKbps,
            audioKbps = if (hasAudio) maxOf(32, (settings.audioKbps * ratio).toInt()) else settings.audioKbps
        )
        return next.takeUnless { it == settings }
    }
}

