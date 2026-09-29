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
    val runtimeProblems: List<String> = emptyList()
) {
    val selected: SourceEdit? get() = sources.firstOrNull { it.source.uri == selectedUri } ?: sources.firstOrNull()
}

sealed interface UiAction {
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
    private var fileJob: Job? = null
    private var initialization: Job? = null
    private var defaultsSeeded = false
    private data class ValidationKey(val sources: List<SourceEdit>, val settings: Settings, val caps: Capabilities)
    private fun key(ui: TranscodeUiState) = ValidationKey(ui.sources, ui.editor.settings, ui.capabilities)

    init {
        initialize()
        viewModelScope.launch {
            mutable.map(::key).distinctUntilChanged().collectLatest { input ->
                mutable.update { it.copy(validating = true) }
                val results = withContext(Dispatchers.Default) {
                    val base = input.sources.flatMap { e -> ensureActive(); Planner.validate(e.source, e.trim, input.settings).map { "${e.source.name}: $it" } }.distinct()
                    val native = if (input.caps.available) input.sources.flatMap { e ->
                        ensureActive()
                        Planner.validate(e.source, e.trim, input.settings, input.caps).map { "${e.source.name}: $it" }
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
                // Seed once per new editor. Reloading capabilities or saving preferences cannot rebase an existing draft.
                if (!defaultsSeeded) {
                    defaultsSeeded = true
                    graph.settings.state.value.document?.let { saved ->
                        val defaults = dev.forma.core.settings.NativePreferences.apply(Settings(),
                            dev.forma.core.settings.SettingsResolver.resolve(saved.values))
                        edit { old -> if (old.sources.isEmpty() && !old.editor.custom && old.editor.settings == Settings())
                            old.copy(editor = old.editor.copy(settings = defaults,
                                custom = defaults != Planner.preset(old.editor.goal, old.editor.quality))) else old }
                    }
                    if (graph.settings.state.value.document == null) graph.settings.state.value.error?.let { problem ->
                        mutable.update { it.copy(message = problem) }
                    }
                }
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
            UiAction.ToggleAdvanced -> mutable.update { it.copy(editor = it.editor.copy(advanced = !it.editor.advanced)) }
            is UiAction.Preset -> edit { it.copy(validating = true, editor = it.editor.copy(goal = action.goal, quality = action.quality,
                settings = Planner.preset(action.goal, action.quality), custom = false)) }
            is UiAction.ChangeSettings -> edit { it.copy(validating = true, editor = it.editor.copy(settings = action.settings, custom = true)) }
            is UiAction.Select -> mutable.update { it.copy(selectedUri = action.uri) }
            is UiAction.RemoveSource -> edit { it.copy(validating = true, sources = it.sources.filterNot { e -> e.source.uri == action.uri }) }
            is UiAction.ChangeTrim -> edit { it.copy(validating = true, sources = it.sources.map { e -> if (e.source.uri == action.uri) e.copy(trim = action.trim) else e }) }
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
                Planner.validate(e.source, e.trim, draft.editor.settings, if (start) draft.capabilities else null).map { "${e.source.name}: $it" }
            } }
            require(problems.isEmpty()) { problems.joinToString("\n") }
            graph.queue.add(draft.sources.map { JobSpec(UUID.randomUUID().toString(), it.source, it.trim, draft.editor.settings) })
            if (start) startQueue()
            else mutable.update { it.copy(message = "${draft.sources.size} job(s) added. You can keep editing; queued settings are independent.") }
        }
    }
    private suspend fun startQueue() {
        if (runState.value.mode != dev.forma.app.work.RunMode.IDLE) return // adding during conversion is allowed
        val caps = mutable.value.capabilities
        require(caps.available) { caps.reason }
        val waiting = jobs.value.filter { it.state == JobState.QUEUED }
        require(waiting.isNotEmpty()) { "There are no waiting jobs." }
        val problems = withContext(Dispatchers.Default) { waiting.flatMap { e ->
            Planner.validate(e.spec.source, e.spec.trim, e.spec.settings, caps).map { "${e.spec.source.name}: $it" }
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
