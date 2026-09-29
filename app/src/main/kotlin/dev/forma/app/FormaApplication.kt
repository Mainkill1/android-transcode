package dev.forma.app

import android.app.Application
import dev.forma.app.data.*
import dev.forma.app.settings.AndroidPowerMonitor
import dev.forma.app.settings.PowerRuntime
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
    val settings = dev.forma.app.settings.SettingsRepository(application, scope)
    val files = MediaFiles(application)
    val queue = QueueRepository(application)
    val runs = RunCoordinator(scope)
    val previews = dev.forma.app.audio.AudioPreviewController(scope)
    val powerMonitor = AndroidPowerMonitor(application)
    val power = PowerRuntime(settings.state, powerMonitor.samples, runs.state, scope)
    val bridge = ManagedFfmpegBridge(createFfmpegBridge())
    val transcoder = FfmpegTranscoder(files, bridge)
    private val initialization = Mutex()
    private var initialized = false
    suspend fun initialize() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (!initialized) {
                settings.load()
                queue.load()
                // Startup cleanup precedes all native/preview readers and never runs on a settings save.
                val values=settings.state.value.document?.values ?: dev.forma.core.settings.PreferenceValues.EMPTY
                val expired=if(settings.state.value.document==null) emptySet() else
                    queue.pruneCompleted(dev.forma.core.settings.ConsumerSettings.historyDays(values),System.currentTimeMillis())
                files.cleanupExpiredOutputs(expired)
                if(dev.forma.core.settings.ConsumerSettings.choice(values,"queue.interrupted_prompt")=="review" &&
                    queue.entries.value.any { it.state==dev.forma.core.JobState.INTERRUPTED })
                    queue.error.value="Interrupted jobs are waiting for review. Retry them explicitly; partial output is not resumed."
                files.cleanupWork()
                java.io.File(application.cacheDir,"audio-preview").deleteRecursively()
                files.cleanupImports(queue.entries.value.map { it.spec.source.uri }.toSet())
                initialized = true
            }
        }
    }
}
