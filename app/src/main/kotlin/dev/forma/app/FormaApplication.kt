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
    val treeGrants = dev.forma.app.settings.TreeGrantStore(application)
    val bridge = ManagedFfmpegBridge(createFfmpegBridge())
    val files = MediaFiles(application, bridge)
    val queue = QueueRepository(application)
    val runs = RunCoordinator(scope)
    val previews = dev.forma.app.audio.AudioPreviewController(scope)
    val powerMonitor = AndroidPowerMonitor(application)
    val power = PowerRuntime(settings.state, powerMonitor.samples, runs.state, scope)
    val transcoder = FfmpegTranscoder(files, bridge)
    val imageMarkup = dev.forma.app.image.ImageMarkupRenderer()
    val imageTranscoder = ImageTranscoder(files, bridge, imageMarkup)
    val imageDrafts = dev.forma.app.image.ImageDraftRepository(application)
    val imagePreviews = dev.forma.app.image.ImagePreviewController(scope, runs, files, bridge, imageMarkup, application)
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
                java.io.File(application.cacheDir,"image-preview").deleteRecursively()
                val references=imageDrafts.references()
                val queuedSources=queue.entries.value.flatMap { entry ->
                    when(val spec=entry.spec) {
                        is dev.forma.core.image.QueueJobSpec.Av -> dev.forma.core.JobPlans.sourceUris(spec.job) + spec.source.uri
                        is dev.forma.core.image.QueueJobSpec.Image -> setOf(spec.source.uri)
                    }
                }.toSet()
                if(!references.preserveImports)files.cleanupImports(queuedSources + references.uris)
                else queue.error.value="Some image drafts cannot be read. Their originals are retained for recovery."
                initialized = true
            }
        }
    }
}
