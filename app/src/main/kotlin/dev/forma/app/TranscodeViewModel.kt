package dev.forma.app

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.forma.app.service.TranscodeService
import dev.forma.core.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class TranscodeUiState(
    val editor: Editor = Editor(),
    val sources: List<SourceEdit> = emptyList(),
    val selectedUri: String? = null,
    val capabilities: Capabilities = Capabilities(reason = "Checking the encoder build…"),
    val ready: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null
) {
    val selected: SourceEdit? get() = sources.firstOrNull { it.source.uri == selectedUri } ?: sources.firstOrNull()
    val problems: List<String> get() = sources.flatMap { edit ->
        Planner.validate(edit.source, edit.trim, editor.settings).map { "${edit.source.name}: $it" }
    }
}

sealed interface UiAction {
    data object Import : UiAction
    data object ToggleAdvanced : UiAction
    data object Queue : UiAction
    data object Convert : UiAction
    data object StartQueue : UiAction
    data object StopQueue : UiAction
    data object DismissMessage : UiAction
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
    init {
        viewModelScope.launch {
            try {
                graph.ready.await()
                val caps = withContext(Dispatchers.IO) { graph.bridge.capabilities() }
                mutable.update { it.copy(ready = true, capabilities = caps) }
            } catch (e: CancellationException) { throw e }
            catch (e: LinkageError) { mutable.update { it.copy(ready = true, capabilities = Capabilities(reason = "The native library could not load for this device: ${e.message}")) } }
            catch (e: Exception) { mutable.update { it.copy(message = "Initialization failed: ${e.message}") } }
        }
        viewModelScope.launch { graph.queue.error.collect { message -> if (message != null) mutable.update { it.copy(message = message) } } }
    }

    fun act(action: UiAction) {
        when (action) {
            UiAction.ToggleAdvanced -> mutable.update { it.copy(editor = it.editor.copy(advanced = !it.editor.advanced)) }
            is UiAction.Preset -> mutable.update { it.copy(editor = it.editor.copy(goal = action.goal, quality = action.quality,
                settings = Planner.preset(action.goal, action.quality), custom = false)) }
            is UiAction.ChangeSettings -> mutable.update { it.copy(editor = it.editor.copy(settings = action.settings, custom = true)) }
            is UiAction.Select -> mutable.update { it.copy(selectedUri = action.uri) }
            is UiAction.RemoveSource -> mutable.update { it.copy(sources = it.sources.filterNot { e -> e.source.uri == action.uri }) }
            is UiAction.ChangeTrim -> mutable.update { it.copy(sources = it.sources.map { e -> if (e.source.uri == action.uri) e.copy(trim = action.trim) else e }) }
            UiAction.Queue -> enqueue(false)
            UiAction.Convert -> enqueue(true)
            UiAction.StartQueue -> runOperation { startQueue() }
            UiAction.StopQueue -> runOperation { getApplication<Application>().startService(Intent(getApplication(), TranscodeService::class.java).setAction(TranscodeService.STOP)) }
            is UiAction.RemoveJob -> runOperation { graph.queue.removeQueued(action.id) }
            is UiAction.Retry -> runOperation {
                val old = jobs.value.first { it.spec.id == action.id }
                require(old.state in setOf(JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED))
                graph.queue.add(listOf(old.spec.copy(id = UUID.randomUUID().toString())))
            }
            is UiAction.OpenSource -> runOperation { open(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(action.uri),
                getApplication<Application>().contentResolver.getType(Uri.parse(action.uri)) ?: "video/*")) }
            is UiAction.OpenOutput -> runOperation { outputIntent(action.id, false) }
            is UiAction.Share -> runOperation { outputIntent(action.id, true) }
            UiAction.DismissMessage -> { mutable.update { it.copy(message = null) }; graph.queue.error.value = null }
            UiAction.Import, is UiAction.Export -> Unit // Activity-result contracts own these two actions.
        }
    }

    fun importSources(uris: List<Uri>) = runOperation {
        val imported = mutableListOf<SourceEdit>()
        val errors = mutableListOf<String>()
        for (uri in uris) {
            try { imported += SourceEdit(graph.files.inspect(uri)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors += e.message ?: "A selected file could not be inspected." }
        }
        mutable.update { old -> old.copy(sources = (old.sources + imported).distinctBy { it.source.uri },
            selectedUri = old.selectedUri ?: imported.firstOrNull()?.source?.uri,
            message = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")) }
    }
    fun export(id: String, uri: Uri) = runOperation {
        val entry = jobs.value.first { it.spec.id == id }
        require(entry.state == JobState.COMPLETED)
        graph.files.export(entry.spec, uri)
        mutable.update { it.copy(message = "Saved to the selected location.") }
    }
    private fun enqueue(start: Boolean) {
        val draft = mutable.value
        runOperation {
            require(draft.sources.isNotEmpty()) { "Choose media first." }
            val problems = draft.sources.flatMap { e -> Planner.validate(e.source, e.trim, draft.editor.settings,
                if (start) draft.capabilities else null).map { "${e.source.name}: $it" } }
            require(problems.isEmpty()) { problems.joinToString("\n") }
            graph.queue.add(draft.sources.map { JobSpec(UUID.randomUUID().toString(), it.source, it.trim, draft.editor.settings) })
            if (start) startQueue() else mutable.update { it.copy(message = "Added ${draft.sources.size} job(s). Their settings are now independent of this editor.") }
        }
    }
    private fun startQueue() {
        val caps = mutable.value.capabilities
        require(caps.available) { caps.reason }
        val waiting = jobs.value.filter { it.state == JobState.QUEUED }
        require(waiting.isNotEmpty()) { "There are no waiting jobs." }
        val problems = waiting.flatMap { e -> Planner.validate(e.spec.source, e.spec.trim, e.spec.settings, caps).map { "${e.spec.source.name}: $it" } }
        require(problems.isEmpty()) { problems.joinToString("\n") }
        ContextCompat.startForegroundService(getApplication(), Intent(getApplication(), TranscodeService::class.java).setAction(TranscodeService.START))
    }
    private fun outputIntent(id: String, share: Boolean) {
        val entry = jobs.value.firstOrNull { it.spec.id == id && it.state == JobState.COMPLETED } ?: return
        val uri = graph.files.outputUri(entry.spec)
        if (share) {
            val send = Intent(Intent.ACTION_SEND).setType(entry.spec.settings.container.mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Converted media", uri) }
            open(Intent.createChooser(send, "Share converted media"))
        } else open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.spec.settings.container.mime))
    }
    private fun open(intent: Intent) {
        try { getApplication<Application>().startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (e: Exception) { mutable.update { it.copy(message = "No app could open this media: ${e.message}") } }
    }
    private fun runOperation(block: suspend () -> Unit) {
        if (mutable.value.busy || !mutable.value.ready) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "The operation failed.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
