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
import dev.forma.core.image.*
import dev.forma.app.image.ImageInputAdapter
import dev.forma.core.audio.SourceAudioFacts
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*

class MediaFiles(private val context: Context,private val imageBridge: dev.forma.ffmpeg.FfmpegBridge? = null) {
    private val resolver get() = context.contentResolver
    private val workRoot get() = File(context.filesDir, "work").apply { mkdirs() }
    private val outputRoot get() = File(context.filesDir, "outputs").apply { mkdirs() }
    private val importRoot get() = File(context.filesDir, "imports").apply { mkdirs() }
    private fun importedUri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    fun workDir(spec: QueueJobSpec) = File(workRoot, spec.id).apply { mkdirs() }
    fun workDir(spec: ImageJobSpec) = File(workRoot, spec.id).apply { mkdirs() }
    fun output(spec: QueueJobSpec) = File(outputRoot, "${spec.id}.${spec.extension}")
    fun outputUri(spec: QueueJobSpec): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", output(spec))
    fun exportName(spec: QueueJobSpec) = spec.source.name.substringBeforeLast('.').replace(Regex("[/\\\\\\x00]"), "_").take(100) + "_forma." + spec.extension
    fun workDir(spec: JobSpec) = File(workRoot, spec.id).apply { mkdirs() }
    fun output(spec: JobSpec) = File(outputRoot, "${spec.id}.${spec.settings.container.extension}")
    fun outputUri(spec: JobSpec): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", output(spec))
    fun exportName(spec: JobSpec) = spec.source.name.substringBeforeLast('.').replace(Regex("[/\\\\\\x00]"), "_").take(100) + "_forma." + spec.settings.container.extension
    fun cleanupWork() { workRoot.listFiles()?.forEach { it.deleteRecursively() } }
    // Editor-only copies expire on the next process start; every persisted job keeps its source.
    fun cleanupImports(referencedUris: Set<String>) {
        importRoot.listFiles()?.forEach { directory ->
            val referenced = directory.listFiles().orEmpty().any { importedUri(it).toString() in referencedUris }
            if (!referenced) directory.deleteRecursively()
        }
    }

    suspend fun importShared(uri: Uri): Source = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "The sender must share a readable media file." }
        if (isImage(uri)) return@withContext importImage(uri)
        val (name, bytes) = metadata(uri)
        val directory = File(importRoot, UUID.randomUUID().toString()).apply { check(mkdir()) { "Could not prepare private storage." } }
        val extension = name.substringAfterLast('.', "media").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "media"
        val target = File(directory, "source.$extension")
        val reserve = 64L * 1024 * 1024
        try {
            require(bytes < 0 || bytes < directory.usableSpace - reserve) { "Not enough private storage to keep the shared media." }
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        require(directory.usableSpace > reserve + read) { "Not enough private storage to keep the shared media." }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IOException("The sender's media could not be opened. Share it again from Gallery or Files.")
            require(bytes < 0 || target.length() == bytes) { "The shared media changed while copying. Share it again." }
            inspect(importedUri(target), persistPermission = false).copy(name = name)
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun metadata(uri: Uri): Pair<String, Long> {
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
        return name to bytes
    }

    suspend fun inspect(uri: Uri, persistPermission: Boolean = true): Source = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Choose a file through the system document picker." }
        currentCoroutineContext().ensureActive()
        if (isImage(uri)) return@withContext importImage(uri)
        if (persistPermission) resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val (name, bytes) = metadata(uri)
        currentCoroutineContext().ensureActive()
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val audioFacts=mutableListOf<SourceAudioFacts>()
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
                if (mime.startsWith("audio/")) {
                    audio++
                    audioFacts+=SourceAudioFacts(index,
                        if(f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) f.getInteger(MediaFormat.KEY_SAMPLE_RATE) else null,
                        if(f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else null,
                        durationUs=if(f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0)
                }
            }
            require(video + audio > 0 && duration > 0) { "Android could not inspect this source. Broader FFprobe import support is the next integration step." }
            Source(uri.toString(), name, duration, width, height, video, audio, hdr, bytes, audioFacts.toList())
        } finally { extractor.release() }
    }

    suspend fun stage(spec: QueueJobSpec): File = when(spec) { is QueueJobSpec.Av -> stage(spec.job); is QueueJobSpec.Image -> stage(spec.job) }
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

    private suspend fun isImage(uri:Uri):Boolean {
        val task=currentCoroutineContext()
        return resolver.openInputStream(uri)?.use{dev.forma.ffmpeg.image.ImageSniff.isImage(it){task.ensureActive()}}?:false
    }
    private suspend fun importImage(uri: Uri): Source {
        val name=metadata(uri).first
        val staged=ImageInputAdapter(context).stage(uri.toString(), UUID.randomUUID().toString())
        try {
        val caps=imageBridge?.capabilities()
        val info=if(imageBridge!=null && caps?.available==true && staged.info.format.decoder in caps.decoders){
            ImageValidation.requireMemory(staged.info,ImageSize(staged.info.width,staged.info.height),(Runtime.getRuntime().maxMemory()*.65).toLong(),false)
            imageBridge.inspectImage(staged.path)
        }else staged.info
        return Source(staged.source.uri,name,0,info.width,info.height,bytes=info.bytes,imageInfo=info,imageOriginalUri=uri.toString())
        }catch(error:Throwable){File(staged.path).parentFile?.deleteRecursively();throw error}
    }
    suspend fun stage(spec: ImageJobSpec): File = withContext(Dispatchers.IO) {
        val target=File(workDir(spec), "source.image")
        try {
            resolver.openInputStream(Uri.parse(spec.document.source.uri))?.use { input -> target.outputStream().use { out ->
                val b=ByteArray(65536);var total=0L;while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;total+=n
                    if(total>64L*1024*1024 || target.parentFile!!.usableSpace<64L*1024*1024+n)throw ImageFailure("RESOURCE_LIMIT","Not enough private storage to stage this image.");out.write(b,0,n)
                }
            }} ?: throw ImageFailure("SOURCE_ACCESS","Choose the image again to restore source access.")
        } catch(e:SecurityException){throw ImageFailure("SOURCE_ACCESS","Choose the image again to restore source access.")}
        if(target.length()!=spec.document.source.bytes || dev.forma.ffmpeg.image.ImageProbe.hash(target)!=spec.document.source.hash)throw ImageFailure("SOURCE_CHANGED","Source identity changed. Choose the image again.")
        target
    }
    suspend fun export(spec: JobSpec, destination: Uri, onBytes: (Long, Long) -> Unit = { _, _ -> }) = export(QueueJobSpec.Av(spec), destination, onBytes)
    suspend fun export(spec: QueueJobSpec, destination: Uri, onBytes: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        if(spec is QueueJobSpec.Image)require(!spec.job.document.source.isOriginalDestination(destination.toString())){"The original cannot be the export destination."}
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
