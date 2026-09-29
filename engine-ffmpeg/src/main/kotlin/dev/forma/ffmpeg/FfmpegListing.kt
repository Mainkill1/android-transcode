package dev.forma.ffmpeg

/** Parses actual listing rows, independently of Android/native execution. */
internal object FfmpegListing {
    // Horizontal whitespace keeps a description-free row from consuming the next row.
    fun encoders(text: String) = Regex("(?m)^[ \\t]*[VAS][A-Z.]{5}[ \\t]+([a-zA-Z0-9_.-]+)(?=[ \\t]|$)").findAll(text).map { it.groupValues[1] }.toSet()
    fun muxers(text: String) = Regex("(?m)^[ \\t]*E[ \\t]+([a-zA-Z0-9_,.-]+)(?=[ \\t]|$)").findAll(text).flatMap { it.groupValues[1].split(',').asSequence() }.toSet()
    // FFmpeg 9 removed the command flag from rows but retains a three-flag legend.
    fun filters(text: String) = Regex("(?m)^[ \\t]*[TSC.]{2,3}[ \\t]+([a-zA-Z0-9_]+)[ \\t]+[AVN|]+->[AVN|]+(?=[ \\t]|$)")
        .findAll(text).map { it.groupValues[1] }.toSet()
}
