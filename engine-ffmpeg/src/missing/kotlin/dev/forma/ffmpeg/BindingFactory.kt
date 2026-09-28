package dev.forma.ffmpeg

import dev.forma.core.*

fun createFfmpegBridge(): FfmpegBridge = MissingFfmpegBridge()

private class MissingFfmpegBridge : FfmpegBridge {
    override suspend fun capabilities() = Capabilities(
        reason = "This build contains the UI and queue, but no native encoder. Build with ffmpegEnabled=true and a source-built FFmpegKitNext repository."
    )
    override suspend fun probe(localPath: String): Source = error("Native FFprobe is not installed in this build.")
    override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult =
        error("Native FFmpeg is not installed. No conversion was performed.")
}
