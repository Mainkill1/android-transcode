package dev.forma.app.data

import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The verified file and durable completion are one non-cancellable transaction. */
internal suspend fun publishVerified(temporary: File, published: File, onCompleted: suspend () -> Unit) =
    withContext(NonCancellable) {
        require(!published.exists()) { "Refusing to replace an existing output." }
        check(temporary.renameTo(published)) { "The verified output could not be published." }
        try { onCompleted() }
        catch (error: Throwable) {
            if (published.exists() && !published.delete())
                error.addSuppressed(java.io.IOException("Could not roll back this job's output."))
            throw error
        }
    }
