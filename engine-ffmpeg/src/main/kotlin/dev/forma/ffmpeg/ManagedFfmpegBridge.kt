package dev.forma.ffmpeg

import dev.forma.core.*
import dev.forma.core.image.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Main-safe, process-shared native boundary. Do not launch competing probes during an encode. */
class ManagedFfmpegBridge(
    private val delegate: FfmpegBridge,
    private val processors: Int = Runtime.getRuntime().availableProcessors()
) : FfmpegBridge {
    private val native = Mutex()
    @Volatile private var cached: Capabilities? = null
    override suspend fun capabilities(): Capabilities = withContext(Dispatchers.IO) {
        cached ?: native.withLock { cached ?: delegate.capabilities().also { cached = it } }
    }
    override suspend fun probe(localPath: String): Source = withContext(Dispatchers.IO) {
        native.withLock { delegate.probe(localPath) }
    }
    override suspend fun inspectStreams(localPath: String, countFrames: Boolean): OutputFacts = withContext(Dispatchers.IO) {
        native.withLock { delegate.inspectStreams(localPath, countFrames) }
    }
    override suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> =
        withContext(Dispatchers.IO) {
            native.withLock { WorkPolicy.withThreadBudget(delegate.prepare(source, trim, settings, input, output), processors) }
        }
    override suspend fun prepareAudio(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> =
        withContext(Dispatchers.IO) { native.withLock { WorkPolicy.withThreadBudget(delegate.prepareAudio(source,trim,settings,input,output),processors) } }
    override suspend fun inspectImage(localPath: String): ImageInfo = withContext(Dispatchers.IO) {
        native.withLock { delegate.inspectImage(localPath) }
    }
    override suspend fun prepare(spec: ImageJobSpec, actual: ImageInfo, attempt: ImageAttempt, input: String, output: String): List<String> =
        withContext(Dispatchers.IO) { native.withLock { WorkPolicy.withImageThreadBudget(delegate.prepare(spec, actual, attempt, input, output), processors) } }
    override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult = withContext(Dispatchers.IO) {
        // The delegate awaits native cancellation completion before this lock can be released.
        native.withLock { delegate.execute(arguments, onProgress) }
    }
}
