package dev.forma.app.data

import dev.forma.core.JobSpec
import java.io.File

/** Host-only storage boundary: compile the real transcoder without Android's document resolver. */
class MediaFiles(private val root: File, val original: File) {
    fun workDir(spec: JobSpec) = File(root, "work/${spec.id}").apply { mkdirs() }
    fun output(spec: JobSpec) = File(root, "outputs/${spec.id}.${spec.settings.container.extension}").apply { parentFile!!.mkdirs() }
    suspend fun stage(spec: JobSpec): File = original.copyTo(File(workDir(spec), "source.media"))
}
