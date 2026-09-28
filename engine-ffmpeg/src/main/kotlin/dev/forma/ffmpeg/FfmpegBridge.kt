package dev.forma.ffmpeg

import dev.forma.core.Capabilities
import dev.forma.core.Progress
import dev.forma.core.Source

data class NativeResult(val exitCode: Int, val diagnostics: String)

/** No activity, document-picker or queue ownership crosses this boundary. */
interface FfmpegBridge {
    suspend fun capabilities(): Capabilities
    suspend fun probe(localPath: String): Source
    suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult
}
