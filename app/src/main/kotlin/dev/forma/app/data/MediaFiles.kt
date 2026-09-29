package dev.forma.app.data

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dev.forma.core.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*

class MediaFiles(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val workRoot get() = File(context.filesDir, "work").apply { mkdirs() }
    private val outputRoot get() = File(context.filesDir, "outputs").apply { mkdirs() }
    fun workDir(spec: JobSpec) = File(workRoot, spec.id).apply { mkdirs() }
    fun output(spec: JobSpec) = File(outputRoot, "${spec.id}.${spec.settings.container.extension}")
    fun outputUri(spec: JobSpec): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", output(spec))
    fun exportName(spec: JobSpec) = spec.source.name.substringBeforeLast('.').replace(Regex("[/\\\\\\x00]"), "_").take(100) + "_forma." + spec.settings.container.extension
    fun cleanupWork() { workRoot.listFiles()?.forEach { it.deleteRecursively() } }

    suspend fun inspect(uri: Uri): Source = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Choose a file through the system document picker." }
        currentCoroutineContext().ensureActive()
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        var name = "Media file"
        var bytes = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val b = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                if (b >= 0 && !c.isNull(b)) bytes = c.getLong(b)
            }
        }
        currentCoroutineContext().ensureActive()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var video = 0; var audio = 0; var width = 0; var height = 0; var duration = 0L; var hdr = false
            repeat(extractor.trackCount) { index ->
                currentCoroutineContext().ensureActive()
                val f = extractor.getTrackFormat(index)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (f.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration, f.getLong(MediaFormat.KEY_DURATION) / 1000)
                if (mime.startsWith("video/")) {
                    if (video == 0) {
                        width = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0
                        height = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                        val transfer = if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
                        hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
                    }
                    video++
                }
                if (mime.startsWith("audio/")) audio++
            }
            require(video + audio > 0 && duration > 0) { "Android could not inspect this source. Broader FFprobe import support is the next integration step." }
            Source(uri.toString(), name, duration, width, height, video, audio, hdr, bytes)
        } finally { extractor.release() }
    }

    suspend fun stage(spec: JobSpec): File = withContext(Dispatchers.IO) {
        val target = File(workDir(spec), "source.media")
        if (spec.source.bytes > 0) require(target.parentFile!!.usableSpace > spec.source.bytes + 64L * 1024 * 1024) {
            "Not enough private storage to stage the source plus working space."
        }
        resolver.openInputStream(Uri.parse(spec.source.uri))?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IOException("The source could not be opened. Reselect it to restore access.")
        if (spec.source.bytes >= 0) require(target.length() == spec.source.bytes) { "The source size changed. Reselect it before converting." }
        target
    }

    suspend fun export(spec: JobSpec, destination: Uri, onBytes: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        require(destination.toString() != spec.source.uri) { "The original cannot be the export destination." }
        val source = output(spec)
        require(source.isFile && source.length() > 0) { "The completed output is no longer available." }
        val total = source.length()
        try {
            resolver.openOutputStream(destination, "w")?.use { out -> source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    onBytes(copied, total)
                }
                out.flush()
                check(copied == total) { "The output changed during saving." }
            } } ?: throw IOException("The destination is not writable.")
        } catch (error: Exception) {
            // Only the new ACTION_CREATE_DOCUMENT result is eligible for cleanup.
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw error
        }
    }
}
