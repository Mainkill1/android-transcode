package dev.forma.app

import android.app.Application
import dev.forma.app.data.*
import dev.forma.app.work.RunCoordinator
import dev.forma.ffmpeg.ManagedFfmpegBridge
import dev.forma.ffmpeg.createFfmpegBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FormaApplication : Application() {
    val graph by lazy { AppGraph(this) }
}

/** UI lifecycles never own native encoding. The foreground service owns its run ticket. */
class AppGraph(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val files = MediaFiles(application)
    val queue = QueueRepository(application)
    val runs = RunCoordinator(scope)
    val previews = dev.forma.app.audio.AudioPreviewController(scope)
    val bridge = ManagedFfmpegBridge(createFfmpegBridge())
    val transcoder = FfmpegTranscoder(files, bridge)
    private val initialization = Mutex()
    private var initialized = false
    suspend fun initialize() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (!initialized) {
                queue.load()
                files.cleanupWork()
                java.io.File(application.cacheDir,"audio-preview").deleteRecursively()
                files.cleanupImports(queue.entries.value.map { it.spec.source.uri }.toSet())
                initialized = true
            }
        }
    }
}
