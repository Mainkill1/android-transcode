package dev.forma.app

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.forma.app.service.TranscodeService
import dev.forma.app.work.ProgressGate
import dev.forma.core.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class FileTask(val label: String, val fraction: Float? = null, val cancelling: Boolean = false)
data class TranscodeUiState(
    val editor: Editor = Editor(),
    val sources: List<SourceEdit> = emptyList(),
    val selectedUri: String? = null,
    val capabilities: Capabilities = Capabilities(reason = "Checking the encoder build…"),
    val ready: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val fileTask: FileTask? = null,
    val validating: Boolean = false,
    val problems: List<String> = emptyList(),
    val runtimeProblems: List<String> = emptyList(),
    val movie: MovieProject = MovieProject(),
    val movieCanUndo: Boolean = false,
    val movieCanRedo: Boolean = false,
    val movieRevision: Long = 0,
    val selectedMovieClipId: String? = null,
    val moviePreviewJobId: String? = null,
    val moviePreviewRevision: Long? = null
) {
    val selected: SourceEdit? get() = sources.firstOrNull { it.source.uri == selectedUri } ?: sources.firstOrNull()
}

sealed interface UiAction {
    data object MovieAppendSelected : UiAction
    data object MovieUndo : UiAction
    data object MovieRedo : UiAction
    data object MoviePreview : UiAction
    data object MovieExport : UiAction
    data object MovieQueue : UiAction
    data object OpenMoviePreview : UiAction
    data class MovieEdit(val command: TimelineCommand) : UiAction
    data class MovieChange(val project: MovieProject) : UiAction
    data class MovieSelect(val id: String) : UiAction
    data class ChangeTarget(val bytes: Long?) : UiAction
    data object Import : UiAction
    data object ToggleAdvanced : UiAction
    data object Queue : UiAction
    data object Convert : UiAction
    data object StartQueue : UiAction
    data object StopQueue : UiAction
    data object FinishCurrent : UiAction
    data object CancelFileTask : UiAction
    data object RetryInitialization : UiAction
    data object DismissMessage : UiAction
    data class DismissMessageIf(val message: String) : UiAction
    data class Preset(val goal: Goal, val quality: Quality) : UiAction
    data class ChangeSettings(val settings: Settings) : UiAction
    data class Select(val uri: String) : UiAction
    data class RemoveSource(val uri: String) : UiAction
    data class ChangeTrim(val uri: String, val trim: Trim) : UiAction
    data class ChangeEffects(val uri: String, val effects: ClipEffects) : UiAction
    data class RemoveJob(val id: String) : UiAction
    data class Retry(val id: String) : UiAction
    data class OpenSource(val uri: String) : UiAction
    data class OpenOutput(val id: String) : UiAction
    data class Share(val id: String) : UiAction
    data class Export(val id: String) : UiAction
}

class TranscodeViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as FormaApplication).graph
    private val mutable = MutableStateFlow(TranscodeUiState())
    val state = mutable.asStateFlow()
    val jobs = graph.queue.entries
    val progress = graph.queue.progress.asStateFlow()
    val runState = graph.runs.state
    private val movieHistory = ProjectHistory()
    private var fileJob: Job? = null
    private var initialization: Job? = null
    private data class ValidationKey(val sources: List<SourceEdit>, val settings: Settings, val caps: Capabilities, val targetBytes: Long?)
    private fun key(ui: TranscodeUiState) = ValidationKey(ui.sources, ui.editor.settings, ui.capabilities, ui.editor.targetBytes)

    init {
        initialize()
        viewModelScope.launch {
            mutable.map(::key).distinctUntilChanged().collectLatest { input ->
                mutable.update { it.copy(validating = true) }
                val results = withContext(Dispatchers.Default) {
                    val base = input.sources.flatMap { e -> ensureActive(); JobPlans.validate(JobSpec("draft", e.source, e.trim, e.snapshot(input.settings), targetBytes = input.targetBytes)).map { "${e.source.name}: $it" } }.distinct()
                    val native = if (input.caps.available) input.sources.flatMap { e ->
                        ensureActive()
                        JobPlans.validate(JobSpec("draft", e.source, e.trim, e.snapshot(input.settings), targetBytes = input.targetBytes), input.caps).map { "${e.source.name}: $it" }
                    }.distinct() else emptyList()
                    base to native
                }
                mutable.update { if (key(it) == input) it.copy(validating = false, problems = results.first, runtimeProblems = results.second) else it }
            }
        }
        viewModelScope.launch { graph.queue.error.collect { message -> if (message != null) mutable.update { it.copy(message = message) } } }
    }

    private fun initialize() {
        if (initialization?.isCompleted == false) return
        initialization = viewModelScope.launch {
            try {
                graph.initialize()
                val caps = graph.bridge.capabilities()
                mutable.update { it.copy(ready = true, capabilities = caps) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: LinkageError) { mutable.update { it.copy(ready = true, capabilities = Capabilities(reason = "Native library could not load: ${error.message}")) } }
            catch (error: Exception) { mutable.update { it.copy(ready = false, message = "Could not open the app's queue: ${error.message}") } }
        }
    }
    private fun edit(change: (TranscodeUiState) -> TranscodeUiState) = mutable.update { old ->
        val next = change(old)
        next.copy(validating = if (key(next) != key(old)) true else old.validating)
    }
    fun act(action: UiAction) {
        when (action) {
            UiAction.MovieAppendSelected -> changeMovie {
                val draft = mutable.value
                val selected = draft.selected ?: error("Select a source first.")
                require(it.sequence.timeline.clips.size < SequencePlanner.MAX_RENDER_CLIPS) { "A movie supports at most ${SequencePlanner.MAX_RENDER_CLIPS} clips." }
                val settings = selected.snapshot(draft.editor.settings)
                val next = it.edit(TimelineCommand.Append(TimelineClip(UUID.randomUUID().toString(), selected.source, selected.trim, settings)))
                mutable.update { ui -> ui.copy(selectedMovieClipId = next.sequence.timeline.clips.last().id) }
                next
            }
            is UiAction.MovieEdit -> changeMovie { it.edit(action.command) }
            is UiAction.MovieChange -> changeMovie { action.project }
            is UiAction.MovieSelect -> mutable.update { it.copy(selectedMovieClipId = action.id) }
            UiAction.MovieUndo -> { movieHistory.undo(); publishMovie() }
            UiAction.MovieRedo -> { movieHistory.redo(); publishMovie() }
            UiAction.MoviePreview -> enqueueMovie(preview = true, start = true)
            UiAction.MovieExport -> enqueueMovie(preview = false, start = true)
            UiAction.MovieQueue -> enqueueMovie(preview = false, start = false)
            UiAction.OpenMoviePreview -> mutable.value.moviePreviewJobId?.let { id -> launchRead { outputIntent(id, false) } }
            is UiAction.ChangeTarget -> {
                action.bytes?.let(UploadFit::validateTarget)
                mutable.update { it.copy(editor = it.editor.copy(targetBytes = action.bytes)) }
            }
            UiAction.ToggleAdvanced -> mutable.update { it.copy(editor = it.editor.copy(advanced = !it.editor.advanced)) }
            is UiAction.Preset -> edit { it.copy(validating = true, editor = it.editor.copy(goal = action.goal, quality = action.quality,
                settings = Planner.preset(action.goal, action.quality), custom = false)) }
            is UiAction.ChangeSettings -> edit { it.copy(validating = true, editor = it.editor.copy(settings = action.settings, custom = true)) }
            is UiAction.Select -> mutable.update { it.copy(selectedUri = action.uri) }
            is UiAction.RemoveSource -> edit { it.copy(validating = true, sources = it.sources.filterNot { e -> e.source.uri == action.uri }) }
            is UiAction.ChangeTrim -> edit { it.copy(validating = true, sources = it.sources.map { e -> if (e.source.uri == action.uri) e.copy(trim = action.trim) else e }) }
            is UiAction.ChangeEffects -> edit { it.copy(validating = true, sources = it.sources.map { e -> if (e.source.uri == action.uri) e.copy(effects = action.effects) else e }) }
            UiAction.Queue -> enqueue(false)
            UiAction.Convert -> enqueue(true)
            UiAction.StartQueue -> runOperation { startQueue() }
            // Never gate cancellation/draining on initialization, import, validation or UI busy state.
            UiAction.StopQueue -> graph.runs.stop()
            UiAction.FinishCurrent -> graph.runs.finishCurrent()
            UiAction.CancelFileTask -> {
                mutable.update { it.copy(fileTask = it.fileTask?.copy(label = "Stopping file operation…", cancelling = true)) }
                fileJob?.cancel()
            }
            UiAction.RetryInitialization -> initialize()
            is UiAction.RemoveJob -> runOperation { graph.queue.removeQueued(action.id) }
            is UiAction.Retry -> runOperation {
                val old = jobs.value.first { it.spec.id == action.id }
                require(old.state in setOf(JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED))
                graph.queue.add(listOf(old.spec.copy(id = UUID.randomUUID().toString())))
                mutable.update { it.copy(message = "A new copy of this job is waiting in the queue.") }
            }
            is UiAction.OpenSource -> launchRead {
                val uri = Uri.parse(action.uri)
                val mime = withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.getType(uri) } ?: "video/*"
                open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime))
            }
            is UiAction.OpenOutput -> launchRead { outputIntent(action.id, false) }
            is UiAction.Share -> launchRead { outputIntent(action.id, true) }
            UiAction.DismissMessage -> { mutable.update { it.copy(message = null) }; graph.queue.error.value = null }
            is UiAction.DismissMessageIf -> {
                mutable.update { if (it.message == action.message) it.copy(message = null) else it }
                graph.queue.error.compareAndSet(action.message, null)
            }
            UiAction.Import, is UiAction.Export -> Unit
        }
    }

    fun importSources(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runFileTask("Reading selected media…") {
            require(uris.size + mutable.value.sources.size <= 200) { "Choose at most 200 files at a time." }
            val errors = mutableListOf<String>()
            for ((index, uri) in uris.distinct().withIndex()) {
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(fileTask = FileTask("Reading file ${index + 1} of ${uris.size}")) }
                try {
                    val imported = SourceEdit(graph.files.inspect(uri))
                    // Publish completed files incrementally. Cancelling preserves already imported sources.
                    edit { old -> old.copy(validating = true,
                        sources = (old.sources + imported).distinctBy { it.source.uri },
                        selectedUri = old.selectedUri ?: imported.source.uri) }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { errors += error.message ?: "A selected file could not be inspected." }
            }
            if (errors.isNotEmpty()) mutable.update { it.copy(message = errors.take(5).joinToString("\n")) }
        }
    }
    fun export(id: String, uri: Uri) = runFileTask("Saving a copy…") {
        val entry = jobs.value.first { it.spec.id == id }
        require(entry.state == JobState.COMPLETED)
        val gate = ProgressGate(WorkPolicy.UI_PROGRESS_MS, SystemClock::elapsedRealtime)
        graph.files.export(entry.spec, uri) { bytes, total ->
            if (gate.accept(id)) mutable.update { old -> if (old.fileTask?.cancelling == true) old else
                old.copy(fileTask = FileTask("Saving a copy…", WorkPolicy.fraction(bytes, total))) }
        }
        mutable.update { it.copy(message = "Copy saved. Your verified original output is still available in the queue.") }
    }
    private fun enqueue(start: Boolean) {
        val draft = mutable.value // immutable request snapshot; later edits cannot rewrite it
        runOperation {
            require(draft.sources.isNotEmpty()) { "Choose media first." }
            val problems = withContext(Dispatchers.Default) { draft.sources.flatMap { e ->
                JobPlans.validate(JobSpec(UUID.randomUUID().toString(), e.source, e.trim, e.snapshot(draft.editor.settings), targetBytes = draft.editor.targetBytes), if (start) draft.capabilities else null).map { "${e.source.name}: $it" }
            } }
            require(problems.isEmpty()) { problems.joinToString("\n") }
            graph.queue.add(draft.sources.map { JobSpec(UUID.randomUUID().toString(), it.source, it.trim, it.snapshot(draft.editor.settings), targetBytes = draft.editor.targetBytes) })
            if (start) startQueue()
            else mutable.update { it.copy(message = "${draft.sources.size} job(s) added. You can keep editing; queued settings are independent.") }
        }
    }
    private fun changeMovie(change: (MovieProject) -> MovieProject) {
        try { movieHistory.replace(change(movieHistory.current)); publishMovie() }
        catch (error: IllegalArgumentException) { mutable.update { it.copy(message = error.message ?: "This movie edit is invalid.") } }
        catch (error: IllegalStateException) { mutable.update { it.copy(message = error.message ?: "This movie edit is unavailable.") } }
    }
    private fun publishMovie() {
        mutable.update { ui ->
            val current = movieHistory.current
            ui.copy(movie = current, movieCanUndo = movieHistory.canUndo, movieCanRedo = movieHistory.canRedo,
                movieRevision = if (current == ui.movie) ui.movieRevision else ui.movieRevision + 1,
                selectedMovieClipId = ui.selectedMovieClipId?.takeIf { id -> current.sequence.timeline.clips.any { it.id == id } }
                    ?: current.sequence.timeline.clips.firstOrNull()?.id)
        }
    }
    private fun enqueueMovie(preview: Boolean, start: Boolean) {
        val draft = mutable.value
        runOperation {
            val id = UUID.randomUUID().toString()
            val job = if (preview) draft.movie.previewJob(id) else draft.movie.toJob(id)
            val problems = withContext(Dispatchers.Default) { JobPlans.validate(job, if (start) draft.capabilities else null) }
            require(problems.isEmpty()) { problems.joinToString("\n") }
            graph.queue.add(listOf(job))
            if (preview) mutable.update { it.copy(moviePreviewJobId = id, moviePreviewRevision = draft.movieRevision,
                message = "Rendered preview is queued. Open it after verification in the movie controls or queue.") }
            else mutable.update { it.copy(message = "Movie queued with an independent snapshot and verified size limit.") }
            if (start) startQueue()
        }
    }
    private suspend fun startQueue() {
        if (runState.value.mode != dev.forma.app.work.RunMode.IDLE) return // adding during conversion is allowed
        val caps = mutable.value.capabilities
        require(caps.available) { caps.reason }
        val waiting = jobs.value.filter { it.state == JobState.QUEUED }
        require(waiting.isNotEmpty()) { "There are no waiting jobs." }
        val problems = withContext(Dispatchers.Default) { waiting.flatMap { e ->
            JobPlans.validate(e.spec, caps).map { "${e.spec.source.name}: $it" }
        } }
        require(problems.isEmpty()) { problems.joinToString("\n") }
        ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), TranscodeService::class.java).setAction(TranscodeService.START))
    }
    private suspend fun outputIntent(id: String, share: Boolean) {
        val entry = jobs.value.firstOrNull { it.spec.id == id && it.state == JobState.COMPLETED } ?: return
        val uri = withContext(Dispatchers.IO) {
            require(graph.files.output(entry.spec).isFile) { "The output is no longer available. Retry the job to recreate it." }
            graph.files.outputUri(entry.spec)
        }
        if (share) {
            val send = Intent(Intent.ACTION_SEND).setType(entry.spec.settings.container.mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Converted media", uri) }
            open(Intent.createChooser(send, "Share converted media"))
        } else open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.spec.settings.container.mime))
    }
    private fun open(intent: Intent) {
        getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private fun launchRead(block: suspend () -> Unit) {
        viewModelScope.launch { try { block() } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "Could not open this media.") } } }
    }
    private fun runOperation(block: suspend () -> Unit) {
        if (mutable.value.busy || !mutable.value.ready) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "The operation failed.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun runFileTask(label: String, block: suspend () -> Unit) {
        if (fileJob?.isCompleted == false || !mutable.value.ready) return
        mutable.update { it.copy(fileTask = FileTask(label)) }
        fileJob = viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) {
                mutable.update { it.copy(message = "File operation cancelled. Conversion jobs were not stopped.") }
                throw cancel
            }
            catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "The file operation failed.") } }
            finally { mutable.update { it.copy(fileTask = null) } }
        }
    }
}
