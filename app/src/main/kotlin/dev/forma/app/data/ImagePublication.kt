package dev.forma.app.data
import dev.forma.core.image.ImageFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Filesystem publication succeeds only with its durable completion callback. */
suspend fun publishVerifiedImage(candidate:File,output:File,onCompleted:suspend()->Unit)=withContext(NonCancellable) {
    if(output.exists())throw ImageFailure("OUTPUT_EXISTS","The destination already exists.")
    if(!candidate.renameTo(output))throw ImageFailure("OUTPUT_INVALID","Could not publish the verified image.")
    try {onCompleted()}catch(error:Throwable){output.delete();throw error}
}
