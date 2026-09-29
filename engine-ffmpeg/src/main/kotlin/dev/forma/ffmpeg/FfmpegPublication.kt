package dev.forma.ffmpeg

import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The service retains its lease until the verified output and durable Completed notification agree. */
object FfmpegPublication {
    suspend fun publish(verified: File, output: File, completed: suspend () -> Unit) = withContext(NonCancellable) {
        check(!output.exists()) { "Never overwrite an existing output." }
        check(verified.renameTo(output)) { "The verified output could not be published." }
        try {
            completed()
        } catch (error: Throwable) {
            // Only the file just published by this transaction is eligible for rollback.
            output.delete()
            throw error
        }
    }
}
