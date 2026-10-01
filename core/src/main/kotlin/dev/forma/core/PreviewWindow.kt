package dev.forma.core

/** A source-time window whose edited playback lasts at most five seconds. */
object PreviewWindow {
    fun select(edit:SourceEdit,playheadMs:Long):Trim {
        val start=edit.trim.startMs
        val end=edit.trim.endMs ?: edit.source.durationMs
        require(start>=0 && end>start && end<=edit.source.durationMs) {"Choose valid trim times before previewing."}
        val sourceSpan=(5_000L*edit.effects.speedPercent/100).coerceAtLeast(1).coerceAtMost(end-start)
        val center=playheadMs.coerceIn(start,end)
        val first=(center-sourceSpan/2).coerceIn(start,end-sourceSpan)
        return Trim(first,first+sourceSpan)
    }
}
