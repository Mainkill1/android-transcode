package dev.forma.ffmpeg

import dev.forma.core.*

data class NativeResult(val exitCode: Int, val diagnostics: String)

/** No activity, document-picker or queue ownership crosses this boundary. */
interface FfmpegBridge {
    suspend fun capabilities(): Capabilities
    suspend fun probe(localPath: String): Source
    /** Every application export must prepare again after staging or changing its size budget. */
    suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> {
        require(!settings.video.hardware || settings.container == Container.M4A) {
            "This bridge does not implement a checked Android hardware route."
        }
        return Planner.arguments(source, trim, settings, input, output)
    }
    suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult
}
