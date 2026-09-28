package dev.forma.app

import android.app.Application
import dev.forma.app.data.*
import dev.forma.ffmpeg.createFfmpegBridge
import kotlinx.coroutines.*

class FormaApplication : Application() {
    val graph by lazy { AppGraph(this) }
}

/** Small manual composition root; no service locators inside the domain or native adapter. */
class AppGraph(application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val files = MediaFiles(application)
    val queue = QueueRepository(application)
    val bridge = createFfmpegBridge()
    val transcoder = FfmpegTranscoder(files, bridge)
    val ready = scope.async { queue.load(); files.cleanupWork() }
}
