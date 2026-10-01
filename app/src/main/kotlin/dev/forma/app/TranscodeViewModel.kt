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
import dev.forma.core.image.*
import dev.forma.app.image.*
import dev.forma.core.settings.*
import dev.forma.core.audio.*
import dev.forma.app.audio.*
import dev.forma.ffmpeg.audio.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FileTask(val label: String, val fraction: Float? = null, val cancelling: Boolean = false)
data class WorkspaceRequest(val id: Int, val showQueue: Boolean)
data class TranscodeUiState(
    val editor: Editor = Editor(),
    val audioEditor: AudioEditorState = AudioEditorState(),
    val imageEditor: ImageEditorState = ImageEditorState(),
    val imageDocuments: Map<String, ImageEditDocument> = emptyMap(),
    val imagePreview: ImagePreviewState = ImagePreviewState(),
    val targetBytes: Long? = 10_000_000,
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
    val moviePreviewRevision: Long? = null,
    val destinationMode: String = "forma",
    val chosenFolderLabel: String? = null
) {
    val imageDocument get() = selected?.source?.uri?.let(imageDocuments::get)
    val selected: SourceEdit? get() = sources.firstOrNull { it.source.uri == selectedUri } ?: sources.firstOrNull()
    val saveLocationLabel: String get() = if(destinationMode=="custom") chosenFolderLabel ?: "chosen folder" else when {
        sources.map { if(it.source.imageInfo!=null) MediaCategory.IMAGE else if(editor.settings.container.audioOnly || it.source.videoTracks==0) MediaCategory.AUDIO else MediaCategory.VIDEO }.distinct().size>1 -> "Forma media folders"
        selected?.source?.imageInfo!=null -> "Pictures/Forma"
        editor.settings.container.audioOnly || selected?.source?.videoTracks==0 -> "Music/Forma"
        else -> "Movies/Forma"
    }
}

sealed interface UiAction {
    data object ToggleImageEditor : UiAction
    data object UndoImage : UiAction
    data object RedoImage : UiAction
    data object SaveImageDraft : UiAction
    data object DiscardImageDraft : UiAction
    data class ChangeImage(val document: ImageEditDocument, val commit: Boolean = true) : UiAction
    data class ImageTool(val tool: String) : UiAction
    data class RenderImage(val actualPixels: Boolean = false, val centerX: Int? = null, val centerY: Int? = null) : UiAction
    data object ToggleAudioEditor : UiAction
    data object RenderAudioPreview : UiAction
    data object CancelAudioPreview : UiAction
    data object UndoAudio : UiAction
    data object RedoAudio : UiAction
    data class ChangeAudioEdit(val edit: AudioEdit, val commit: Boolean = true) : UiAction
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
    data class SetTargetBytes(val targetBytes: Long?) : UiAction
    data class Preset(val goal: Goal, val quality: Quality, val keepOverrides: Boolean = false) : UiAction
    data class ChangeSettings(val settings: Settings, val preferences: MediaPreferences? = null, val explicitIds: Set<String> = emptySet()) : UiAction
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
    private var defaultsSeeded = false
    private val sharedImports = Mutex()
    var receivedInitialIntent = false
    private var settingsChosen = false
    private val audioHistory = AudioEditHistory().apply {
        resetBaseline(mutable.value.editor.settings.audioEdit,mutable.value.editor.preferences.overrides["audio.channels"])
    }
    private val imageHistory = mutableMapOf<String, ImageHistory>()
    private val imageGestureBase = mutableMapOf<String, ImageEditDocument>()
    private var imageSave: Job? = null
    private data class ValidationKey(val sources: List<SourceEdit>, val settings: Settings, val caps: Capabilities, val documents: Map<String, ImageEditDocument>, val targetBytes: Long?)
    private fun key(ui: TranscodeUiState) = ValidationKey(ui.sources, ui.editor.settings, ui.capabilities, ui.imageDocuments, ui.targetBytes)

    init {
        initialize()
        viewModelScope.launch { graph.settings.state.collect { saved ->
            val mode=(saved.document?.values?.get("export.destination") as? SettingValue.Choice)?.value ?: "forma"
            val label=withContext(Dispatchers.IO) { runCatching { graph.treeGrants.selected()?.label }.getOrNull() }
            mutable.update { it.copy(destinationMode=mode,chosenFolderLabel=label) }
        } }
        viewModelScope.launch { graph.imagePreviews.state.collect { preview -> mutable.update { it.copy(imagePreview=preview) } } }
        viewModelScope.launch {
            mutable.map(::key).distinctUntilChanged().collectLatest { input ->
                mutable.update { it.copy(validating = true) }
                val results = withContext(Dispatchers.Default) {
                    val base = input.sources.flatMap { e ->
                        ensureActive()
                        if (e.source.imageInfo != null) input.documents[e.source.uri]?.let { ImageValidation.validate(it).map { p->p.message } } ?: listOf("Image draft is missing.")
                        else runCatching { JobPlans.validate(JobSpec("draft",e.source,e.trim,e.snapshot(input.settings),targetBytes=input.targetBytes)) }
                            .getOrElse { listOf(it.message ?: "Invalid size limit") }.map { "${e.source.name}: $it" }
                    }.distinct()
                    val native = if (input.caps.available) input.sources.flatMap { e ->
                        ensureActive()
                        if (e.source.imageInfo != null) {
                            val info=e.source.imageInfo!!;val d=input.documents[e.source.uri]
                            if(d==null)listOf("Image draft is missing.") else try {
                                val spec=ImageJobSpec("validation",d,info);val format=ImagePlanner.resolveFormat(info,spec,input.caps)
                                val attempt=ImageFitPolicy.candidates(spec,info,format).first().copy(markupPath=if(d.annotations.isNotEmpty())"pending-private-markup" else null)
                                ImagePlanner.plan(info,spec,attempt,input.caps);emptyList()
                            }catch(error:Exception){listOf(error.message ?: "Image route unavailable.")}
                        } else runCatching { JobPlans.validate(JobSpec("draft",e.source,e.trim,e.snapshot(input.settings),targetBytes=input.targetBytes),input.caps) }
                            .getOrElse { listOf(it.message ?: "Invalid size limit") }.map { "${e.source.name}: $it" }
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
                        edit { old -> if (old.sources.isEmpty() && !settingsChosen && !old.editor.custom && old.editor.settings == Settings()) {
                            audioHistory.resetBaseline(defaults.audioEdit)
                            old.copy(editor = old.editor.copy(settings = defaults, preferences = MediaPreferences.fromDefaults(saved),
                                custom = defaults != Planner.preset(old.editor.goal, old.editor.quality)))
                        } else old }
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
        val previewChanged = AudioEditorSettings.previewKey(old.selected,old.editor.settings) != AudioEditorSettings.previewKey(next.selected,next.editor.settings)
        if (previewChanged) graph.previews.invalidate()
        val audio = if (previewChanged) next.audioEditor.copy(revision=old.audioEditor.revision+1,
            preview=next.audioEditor.preview.copy(identity="",status=if(next.audioEditor.preview.result!=null) "Stale" else "Not rendered",error=null)) else next.audioEditor
        next.copy(audioEditor=audio, validating = if (key(next) != key(old)) true else old.validating)
    }
    fun act(action: UiAction) {
        when (action) {
            UiAction.ToggleImageEditor -> {
                mutable.update { it.copy(imageEditor=it.imageEditor.copy(open=!it.imageEditor.open)) }
                if(mutable.value.imageEditor.open)renderImage(false)
            }
            UiAction.UndoImage -> restoreImageHistory(false)
            UiAction.RedoImage -> restoreImageHistory(true)
            UiAction.SaveImageDraft -> saveImageAndClose(false)
            UiAction.DiscardImageDraft -> saveImageAndClose(true)
            is UiAction.ChangeImage -> changeImage(action.document,action.commit)
            is UiAction.ImageTool -> mutable.update { it.copy(imageEditor=it.imageEditor.copy(tool=action.tool)) }
            is UiAction.RenderImage -> renderImage(action.actualPixels,action.centerX,action.centerY)
            UiAction.ToggleAudioEditor -> mutable.update { it.copy(audioEditor=it.audioEditor.copy(open=!it.audioEditor.open)) }
            UiAction.RenderAudioPreview -> renderAudioPreview()
            UiAction.CancelAudioPreview -> { graph.previews.invalidate();mutable.update { it.copy(audioEditor=it.audioEditor.copy(preview=it.audioEditor.preview.copy(identity="",status="Not rendered"))) } }
            UiAction.UndoAudio -> changeAudio(audioHistory.undo(),record=false)
            UiAction.RedoAudio -> changeAudio(audioHistory.redo(),record=false)
            is UiAction.ChangeAudioEdit -> changeAudio(action.edit,action.commit)
            UiAction.MovieAppendSelected -> changeMovie {
                val draft = mutable.value
                val selected = draft.selected ?: error("Select a source first.")
                require(selected.source.imageInfo == null) { "Still-image movie clips are not supported yet." }
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
            is UiAction.ChangeTarget -> act(UiAction.SetTargetBytes(action.bytes))
            UiAction.ToggleAdvanced -> mutable.update { it.copy(editor = it.editor.copy(advanced = !it.editor.advanced)) }
            is UiAction.Preset -> {
                settingsChosen = true
                val current=mutable.value.editor
                val preset=AudioEditorSettings.preset(current.settings,action.goal,action.quality)
                current.preferences.applyPreset(preset,"${action.goal.label} · ${action.quality.label}",action.keepOverrides).fold(
                    onSuccess={ applied ->
                        val settings=applied.settings;val preferences=applied.preferences
                        if(settings.audioEdit!=current.settings.audioEdit || preferences.overrides["audio.channels"]!=audioHistory.currentChannelOverride)
                            audioHistory.update(settings.audioEdit,true,preferences.overrides["audio.channels"])
                        edit { it.copy(validating=true,editor=it.editor.copy(goal=action.goal,quality=action.quality,
                            settings=settings,preferences=preferences,custom=preferences.overrideCount>0),
                            audioEditor=it.audioEditor.copy(canUndo=audioHistory.canUndo,canRedo=audioHistory.canRedo)) }
                    },onFailure={ error -> mutable.update { it.copy(message="Preset was not applied: ${error.message}") } })
            }
            is UiAction.ChangeSettings -> {
                settingsChosen = true
                val current=mutable.value.editor
                val preferences=action.preferences ?: current.preferences.changed(current.settings,action.settings,action.explicitIds)
                val audioChanged=current.settings.audioEdit != action.settings.audioEdit ||
                    preferences.overrides["audio.channels"]!=audioHistory.currentChannelOverride
                if(audioChanged)audioHistory.update(action.settings.audioEdit,true,preferences.overrides["audio.channels"])
                edit { it.copy(validating = true, editor = it.editor.copy(settings = action.settings, custom = true,
                    preferences=preferences),
                    audioEditor=if(audioChanged)it.audioEditor.copy(canUndo=audioHistory.canUndo,canRedo=audioHistory.canRedo) else it.audioEditor) }
            }
            is UiAction.Select -> { edit { it.copy(selectedUri = action.uri) }; if(mutable.value.imageEditor.open)renderImage(false) }
            is UiAction.RemoveSource -> edit { it.copy(validating = true, sources = it.sources.filterNot { e -> e.source.uri == action.uri }) }
            is UiAction.ChangeTrim -> edit { it.copy(validating = true, sources = it.sources.map { e -> if (e.source.uri == action.uri) e.copy(trim = action.trim) else e }) }
            is UiAction.SetTargetBytes -> { action.targetBytes?.let(UploadFit::validateTarget); edit { it.copy(targetBytes=action.targetBytes,editor=it.editor.copy(targetBytes=action.targetBytes)) } }
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
                val retry=old.spec.copy(id = UUID.randomUUID().toString())
                val values=graph.settings.state.value.document?.values ?: PreferenceValues.EMPTY
                val delivery=if(old.delivery.destination==null) newDelivery(retry,values) else
                    SaveDestinationPolicy.retry(old.delivery,retry,values,null)
                val tree=delivery.destination as? SaveDestination.DocumentTree
                if(tree!=null) require(withContext(Dispatchers.IO) { graph.treeGrants.validate(tree.uri) }) {
                    "Forma cannot write to this job's chosen folder. Choose it again before retrying."
                }
                graph.queue.addTagged(listOf(retry),mapOf(retry.id to delivery))
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

    private fun changeImage(value: ImageEditDocument, commit: Boolean = true) {
        val current=mutable.value.imageDocument ?: return
        if(value.source.hash!=current.source.hash)return
        val uri=current.source.uri
        val next=value.copy(revision=current.revision+1).frozen()
        val history=imageHistory[uri] ?: ImageHistory(current)
        val updated=if(commit) {
            val base=imageGestureBase.remove(uri) ?: history.current
            ImageHistory(base,history.past,history.future).apply(next)
        } else {
            imageGestureBase.putIfAbsent(uri,history.current)
            ImageHistory(next,history.past,history.future)
        }
        imageHistory[uri]=updated
        mutable.update { it.copy(imageDocuments=it.imageDocuments+(uri to next),imageEditor=it.imageEditor.copy(canUndo=updated.past.isNotEmpty(),canRedo=updated.future.isNotEmpty(),dirty=true)) }
        if(commit)autosaveImage(next)
        if(commit || value.crop==current.crop)renderImage(false)
    }
    private fun restoreImageHistory(redo: Boolean) {
        val d=mutable.value.imageDocument ?: return;val h=imageHistory[d.source.uri] ?: return
        val restored=if(redo)h.redo() else h.undo();if(restored===h)return
        val next=restored.current.copy(revision=d.revision+1)
        imageHistory[d.source.uri]=ImageHistory(next,restored.past,restored.future)
        mutable.update { it.copy(imageDocuments=it.imageDocuments+(d.source.uri to next),imageEditor=it.imageEditor.copy(canUndo=restored.past.isNotEmpty(),canRedo=restored.future.isNotEmpty(),dirty=true)) }
        autosaveImage(next);renderImage(false)
    }
    private fun autosaveImage(d: ImageEditDocument) {
        imageSave?.cancel();imageSave=viewModelScope.launch {
            delay(250)
            try { when(graph.imageDrafts.save(d,d.revision)) {
                ImageDraftSaveResult.Preserved -> mutable.update { it.copy(message="Existing corrupt or unsupported image draft is preserved. Export remains separate.") }
                else -> Unit
            } } catch(c:CancellationException){throw c}catch(e:Exception){mutable.update{it.copy(message="Draft could not be saved: ${e.message}")}}
        }
    }
    private fun saveImageAndClose(discard: Boolean) {
        val d=mutable.value.imageDocument ?: return
        viewModelScope.launch {
            imageSave?.cancelAndJoin()
            if(discard){graph.imageDrafts.discard(d.source.hash);val clean=ImageEditDocument(source=d.source,revision=d.revision+1)
                imageHistory[d.source.uri]=ImageHistory(clean);mutable.update {it.copy(imageDocuments=it.imageDocuments+(d.source.uri to clean))}}
            else when(graph.imageDrafts.save(d,d.revision)) {
                ImageDraftSaveResult.Preserved -> {mutable.update{it.copy(message="DRAFT_CORRUPT: Existing saved data is preserved; this draft could not replace it.")};return@launch}
                ImageDraftSaveResult.Conflict -> {mutable.update{it.copy(message="A newer draft was saved; this edit remains open.")};return@launch}
                is ImageDraftSaveResult.Saved -> Unit
            }
            mutable.update{it.copy(imageEditor=it.imageEditor.copy(open=false,dirty=false))}
        }
    }
    private fun renderImage(actual: Boolean,centerX:Int?=null,centerY:Int?=null) {
        val ui=mutable.value;val d=ui.imageDocument ?: return;val info=ui.selected?.source?.imageInfo ?: return
        if(!ui.capabilities.available)return
        graph.imagePreviews.request(d,info,actual,centerX,centerY)
    }
    private suspend fun initializeImage(source: Source) {
        val info=source.imageInfo ?: return
        val identity=ImageSource(source.uri,source.name,info.hash,info.bytes,source.imageOriginalUri)
        val restored=graph.imageDrafts.load(info.hash)
        val d=when(restored) {
            is ImageDraftLoadResult.Valid -> restored.document.copy(source=identity)
            is ImageDraftLoadResult.Corrupt -> {mutable.update{it.copy(message="DRAFT_CORRUPT: Saved image draft is preserved: ${restored.reason}")};ImageEditDocument(source=identity)}
            is ImageDraftLoadResult.Unsupported -> {mutable.update{it.copy(message="Saved image draft schema is unsupported and preserved.")};ImageEditDocument(source=identity)}
            ImageDraftLoadResult.Missing -> ImageEditDocument(source=identity)
        }
        imageHistory[source.uri]=ImageHistory(d)
        mutable.update{it.copy(imageDocuments=it.imageDocuments+(source.uri to d),imageEditor=ImageEditorState())}
    }
    private fun changeAudio(value: AudioEdit, commit: Boolean = true, record: Boolean = true) {
        val current=mutable.value.editor
        val preferences=if(record) current.preferences.changed(current.settings,current.settings.copy(audioEdit=value))
            else current.preferences.copy(overrides=audioHistory.currentChannelOverride?.let {
                current.preferences.overrides.with("audio.channels",it)
            } ?: current.preferences.overrides.without("audio.channels"))
        if(record)audioHistory.update(value,commit,preferences.overrides["audio.channels"])
        settingsChosen=true
        edit { it.copy(editor=it.editor.copy(settings=it.editor.settings.copy(audioEdit=value),custom=true,
            preferences=preferences),
            audioEditor=it.audioEditor.copy(canUndo=audioHistory.canUndo,canRedo=audioHistory.canRedo)) }
    }
    private fun renderAudioPreview() {
        val draft=mutable.value;val selected=draft.selected ?: return
        if(!draft.capabilities.available) { mutable.update { it.copy(message=draft.capabilities.reason) };return }
        if(graph.runs.state.value.mode!=dev.forma.app.work.RunMode.IDLE) { mutable.update { it.copy(message="Preview is available after conversion finishes.") };return }
        val token="${selected.source.uri}:${draft.audioEditor.revision}:${draft.editor.settings.audioTrack}"
        mutable.update { it.copy(audioEditor=it.audioEditor.copy(preview=AudioPreviewState(identity=token,status="Updating"))) }
        graph.previews.request(render={
            val root=File(getApplication<Application>().cacheDir,"audio-preview").apply { mkdirs() }
            root.listFiles()?.forEach { it.deleteRecursively() }
            val settings=selected.snapshot(draft.editor.settings)
            val spec=JobSpec(UUID.randomUUID().toString(),selected.source,selected.trim,settings,preferences=draft.editor.preferences,targetBytes=null)
            val directory=File(root,spec.id).apply { mkdirs() }
            try {
                val input=graph.files.stage(spec)
                val source=graph.bridge.probe(input.path)
                val identity=AudioAnalysisIdentity.create(AudioAnalysisIdentity.fingerprint(input),draft.capabilities.build,source,selected.trim,settings)
                val result=AudioPreviewRenderer(graph.bridge).render(AudioPreviewRequest(identity,input,source,selected.trim,settings,directory))
                currentCoroutineContext().ensureActive()
                result.copy(identity=token)
            } catch(e:Throwable) { directory.deleteRecursively();throw e }
            finally { graph.files.workDir(spec).deleteRecursively() }
        },onResult={ result ->
            mutable.update { old -> if(old.audioEditor.preview.identity==result.identity) old.copy(audioEditor=old.audioEditor.copy(
                preview=old.audioEditor.preview.accept(result.identity,result.rendered.path).copy(result=result))) else old }
        },onError={ message -> mutable.update { old -> if(old.audioEditor.preview.identity==token) old.copy(audioEditor=old.audioEditor.copy(preview=old.audioEditor.preview.copy(status="Unavailable",error=message))) else old } })
    }
    fun showImportError(message: String) { mutable.update { it.copy(message = message) } }
    fun importSharedSources(uris: List<Uri>) {
        val request = uris.toList()
        viewModelScope.launch {
            sharedImports.withLock {
                // A cold share can arrive before queue/capability initialization completes.
                initialization?.join()
                if (!mutable.value.ready) return@withLock
                fileJob?.join()
                importSources(request, shared = true)
                fileJob?.join()
            }
        }
    }
    fun importSources(uris: List<Uri>) = importSources(uris, shared = false)
    private fun importSources(uris: List<Uri>, shared: Boolean) {
        if (uris.isEmpty()) return
        runFileTask("Reading selected media…") {
            require(uris.size + mutable.value.sources.size <= 200) { "Choose at most 200 files at a time." }
            val errors = mutableListOf<String>()
            for ((index, uri) in uris.distinct().withIndex()) {
                currentCoroutineContext().ensureActive()
                mutable.update { it.copy(fileTask = FileTask("Reading file ${index + 1} of ${uris.size}")) }
                try {
                    val imported = SourceEdit(if (shared) graph.files.importShared(uri) else graph.files.inspect(uri))
                    initializeImage(imported.source)
                    // Publish completed files incrementally. Cancelling preserves already imported sources.
                    edit { old -> old.copy(validating = true,
                        message = if (old.sources.isEmpty() && !settingsChosen && imported.source.videoTracks == 0 && imported.source.imageInfo == null)
                            AudioEditorSettings.audioImportProblem(old.editor.settings) ?: old.message else old.message,
                        editor = if (old.sources.isEmpty() && !settingsChosen && imported.source.videoTracks == 0 && imported.source.imageInfo == null)
                            old.editor.copy(goal = Goal.AUDIO, settings = AudioEditorSettings.audioImport(old.editor.settings),
                                preferences=old.editor.preferences.changed(old.editor.settings,AudioEditorSettings.audioImport(old.editor.settings))) else old.editor,
                        sources = (old.sources + imported).distinctBy { it.source.uri },
                        selectedUri = if (shared && index == 0) imported.source.uri else old.selectedUri ?: imported.source.uri) }
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
        mutable.update { it.copy(message = "Copy saved.") }
    }
    private fun enqueue(start: Boolean) {
        val draft = mutable.value // immutable request snapshot; later edits cannot rewrite it
        runOperation {
            require(draft.sources.isNotEmpty()) { "Choose media first." }
            val prepared=draft.sources.map { e ->
                val info=e.source.imageInfo
                if(info!=null) {
                    val d=draft.imageDocuments[e.source.uri] ?: error("Image draft is missing.")
                    ImageValidation.requireValid(d)
                    val initial=ImageJobSpec(UUID.randomUUID().toString(),d,info,preferences=draft.editor.preferences)
                    val format=ImagePlanner.resolveFormat(info,initial,draft.capabilities)
                    val job=initial.copy(resolvedFormat=format)
                    val first=ImageFitPolicy.candidates(job,info).first()
                    if(start)ImagePlanner.plan(info,job,first.copy(markupPath=if(d.annotations.isNotEmpty())"pending-private-markup" else null),draft.capabilities)
                    val geometry=ImageGeometry.resolve(info,d,first)
                    ImageValidation.requireMemory(info,geometry.outputSize,(Runtime.getRuntime().maxMemory()*.65).toLong(),d.annotations.isNotEmpty())
                    QueueJobSpec.Image(job)
                } else {
                    val job=JobSpec(UUID.randomUUID().toString(),e.source,e.trim,e.snapshot(draft.editor.settings),
                        preferences=draft.editor.preferences,targetBytes=draft.targetBytes)
                    val problems=JobPlans.validate(job,if(start)draft.capabilities else null)
                    require(problems.isEmpty()){problems.joinToString("\n")}
                    QueueJobSpec.Av(job)
                }
            }
            val values=graph.settings.state.value.document?.values ?: PreferenceValues.EMPTY
            val deliveries=prepared.associate { spec -> spec.id to newDelivery(spec,values) }
            graph.queue.addTagged(prepared,deliveries)
            if (start || ConsumerSettings.autoStart(values,runState.value.mode==dev.forma.app.work.RunMode.IDLE)) startQueue()
            else mutable.update { it.copy(message = "${draft.sources.size} file(s) added to queue.") }
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
            val initial = if (preview) draft.movie.previewJob(id) else draft.movie.toJob(id)
            // Freeze the output document's choices against the same app/default revision as this editor.
            val preferences = draft.editor.preferences.changed(draft.editor.settings,initial.settings)
            val job = initial.copy(preferences=preferences)
            val problems = withContext(Dispatchers.Default) { JobPlans.validate(job, if (start) draft.capabilities else null) }
            require(problems.isEmpty()) { problems.joinToString("\n") }
            val tagged=QueueJobSpec.Av(job)
            val values=graph.settings.state.value.document?.values ?: PreferenceValues.EMPTY
            graph.queue.addTagged(listOf(tagged),if(preview) emptyMap() else mapOf(id to newDelivery(tagged,values)))
            if (preview) mutable.update { it.copy(moviePreviewJobId = id, moviePreviewRevision = draft.movieRevision,
                message = "Rendered preview is queued. Open it after verification in the movie controls or queue.") }
            else mutable.update { it.copy(message = "Movie queued with an independent snapshot and verified size limit.") }
            if (start || ConsumerSettings.autoStart(values,runState.value.mode==dev.forma.app.work.RunMode.IDLE)) startQueue()
        }
    }
    private suspend fun newDelivery(spec: QueueJobSpec, values: PreferenceValues): Delivery = withContext(Dispatchers.IO) {
        val mode=(values["export.destination"] as? SettingValue.Choice)?.value ?: "forma"
        val tree=if(mode=="custom") graph.treeGrants.selected() else null
        if(mode=="custom") require(tree!=null && graph.treeGrants.validate(tree.uri)) {
            "Forma cannot write to your chosen folder. Open Save location and choose it again."
        }
        Delivery(SaveDestinationPolicy.snapshot(spec,values,tree),DeliveryReceipt.Waiting)
    }
    private suspend fun startQueue() {
        graph.previews.cancelAndJoin()
        graph.imagePreviews.cancelAndJoin()
        if (runState.value.mode != dev.forma.app.work.RunMode.IDLE) return // adding during conversion is allowed
        val caps = mutable.value.capabilities
        require(caps.available) { caps.reason }
        val waiting = jobs.value.filter { it.state == JobState.QUEUED }
        require(waiting.isNotEmpty()) { "There are no waiting jobs." }
        val problems = withContext(Dispatchers.Default) { waiting.flatMap { e ->
            if (e.spec is QueueJobSpec.Image) ImageValidation.validate((e.spec as QueueJobSpec.Image).job.document).map { it.message } else JobPlans.validate((e.spec as QueueJobSpec.Av).job,caps).map { "${e.spec.source.name}: $it" }
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
            val send = Intent(Intent.ACTION_SEND).setType(entry.spec.mime).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("Converted media", uri) }
            open(Intent.createChooser(send, "Share converted media"))
        } else open(Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.spec.mime))
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
