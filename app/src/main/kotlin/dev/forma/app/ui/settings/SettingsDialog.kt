package dev.forma.app.ui.settings

import android.content.Intent
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forma.app.work.RunMode
import dev.forma.app.FormaApplication
import dev.forma.app.ui.FormaTheme
import dev.forma.core.Settings
import dev.forma.core.settings.*
import kotlinx.coroutines.launch

/** Full-screen native settings destination. No source, export, or worker lifecycle is replaced. */
@Composable fun SettingsDialog(current: Settings, jobScope: Boolean, onApplyToJob: (Settings) -> Unit, onDismiss: () -> Unit,
    preferences: MediaPreferences = MediaPreferences.legacy(current), onApplyPreferences: ((Settings, MediaPreferences) -> Unit)? = null) {
    val graph = (LocalContext.current.applicationContext as FormaApplication).graph
    val context = LocalContext.current
    val loaded by graph.settings.state.collectAsStateWithLifecycle()
    val run by graph.runs.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var baselineWire by rememberSaveable { mutableStateOf<String?>(null) }
    var draftWire by rememberSaveable { mutableStateOf<String?>(null) }
    var inheritedWire by rememberSaveable { mutableStateOf<String?>(null) }
    var presetWire by rememberSaveable { mutableStateOf<String?>(null) }
    var presetName by rememberSaveable { mutableStateOf<String?>(null) }
    var legacySnapshot by rememberSaveable { mutableStateOf(false) }
    var backRequest by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    var retentionPending by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val jobs by graph.queue.entries.collectAsStateWithLifecycle()
    LaunchedEffect(loaded.document) {
        if (baselineWire == null) loaded.document?.let { saved ->
            try {
                inheritedWire=SettingsCodec.encode(if(jobScope) preferences.app else saved)
                presetWire=SettingsCodec.encode(SettingsDocument(values=preferences.preset))
                presetName=preferences.presetName;legacySnapshot=preferences.legacySnapshot
                val initial=if (jobScope) SettingsDocument(preferences.app.revision, preferences.overrides) else saved
                baselineWire=SettingsCodec.encode(initial); draftWire=baselineWire
            } catch (error: IllegalArgumentException) { failure="Current settings could not be imported: ${error.message}" }
        }
    }
    fun decodeDraft(wire:String)=if(jobScope && legacySnapshot) SettingsCodec.decodeLegacyMediaSnapshot(wire) else SettingsCodec.decode(wire)
    val base = remember(baselineWire) { baselineWire?.let(::decodeDraft) }
    val draft = remember(base, draftWire) { base?.let { SettingsDraft(it, draftWire?.let(::decodeDraft)?.values ?: it.values) } }
    val chooseSaveFolder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null && draft!=null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val document=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
                val label=context.contentResolver.query(document,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                    if(cursor.moveToFirst()) cursor.getString(0) else null
                }?.takeIf { it.isNotBlank() } ?: "Selected folder"
                graph.treeGrants.choose(uri.toString(),label)
                draftWire=SettingsCodec.encode(SettingsDocument(draft.saved.revision,
                    draft.values.with("export.destination",SettingValue.Choice("custom"))))
                failure=null
            } catch(error:Exception) { failure="Could not use that folder: ${error.message}" }
        }
    }
    val inherited = remember(inheritedWire) { inheritedWire?.let(SettingsCodec::decode)?.values ?: PreferenceValues.EMPTY }
    val preset=remember(presetWire) { presetWire?.let(SettingsCodec::decode)?.values ?: PreferenceValues.EMPTY }
    val frozen=if(jobScope) MediaPreferences(inheritedWire?.let(SettingsCodec::decode) ?: SettingsDocument(),preset,
        draft?.values ?: PreferenceValues.EMPTY,presetName,legacySnapshot) else MediaPreferences()
    val validation = remember(draft, inherited, preset, jobScope, current) {
        draft?.let { runCatching {
            NativePreferences.apply(current, SettingsResolver.resolve(if (jobScope) inherited else it.values,
                preset=if(jobScope) preset else PreferenceValues.EMPTY,
                job=if (jobScope) it.values else PreferenceValues.EMPTY))
        }.exceptionOrNull()?.message }
    }
    val changedElsewhere = base != null && loaded.document != null && base.revision != loaded.document?.revision
    fun close() { if (!busy) { if (draft?.dirty == true) confirmClose=true else onDismiss() } }
    fun save(closeAfter: Boolean = false, retentionConfirmed:Boolean = false) {
        val editing=draft ?: return
        if (busy || validation != null) return
        if(!jobScope && !retentionConfirmed && ConsumerSettings.historyDays(editing.values)<ConsumerSettings.historyDays(editing.saved.values)) {
            confirmClose=false;retentionPending=closeAfter;return
        }
        failure=null; confirmClose=false
        if (jobScope) {
            try {
                val result=NativePreferences.apply(current, SettingsResolver.resolve(inherited, preset, editing.values))
                if(onApplyPreferences!=null) onApplyPreferences(result,frozen.copy(overrides=editing.values)) else onApplyToJob(result)
                onDismiss()
            } catch (error: IllegalArgumentException) { failure=error.message }
        } else {
            busy=true
            scope.launch {
                try {
                    graph.settings.save(editing).fold(onSuccess={ document ->
                        baselineWire=SettingsCodec.encode(document); draftWire=baselineWire; inheritedWire=baselineWire
                        if (closeAfter) onDismiss()
                    }, onFailure={ failure=it.message ?: "Preferences were not saved." })
                } finally { busy=false }
            }
        }
    }
    val preview = if (jobScope) loaded.document?.values ?: inherited else draft?.values ?: loaded.document?.values ?: PreferenceValues.EMPTY
    Dialog(onDismissRequest={ if (draft == null) close() else backRequest++ },
        properties=DialogProperties(usePlatformDefaultWidth=false, dismissOnClickOutside=false, dismissOnBackPress=true)) {
        FormaTheme(preview) {
            BackHandler(enabled=draft == null) { onDismiss() }
            Surface(Modifier.fillMaxSize()) {
                if (draft == null) Column(Modifier.padding(24.dp)) {
                    Text(failure ?: loaded.error ?: "Loading settings…")
                    TextButton(onClick={ scope.launch { graph.settings.load() } }) { Text("Retry") }
                    TextButton(onClick=onDismiss) { Text("Close") }
                    SettingsRunControls(run.mode != RunMode.IDLE, run.mode == RunMode.STOPPING) { graph.runs.stop() }
                } else Column {
                    if (changedElsewhere) {
                        Text("App defaults changed while this view was open. This draft still uses its original snapshot.", Modifier.padding(16.dp))
                        if (!jobScope) TextButton(enabled=!busy, onClick={
                            // Explicit reload discards this draft; confirm through the existing discard action first.
                            if (!draft.dirty) loaded.document?.let { document ->
                                baselineWire=SettingsCodec.encode(document); draftWire=baselineWire; inheritedWire=baselineWire; failure=null
                            }
                            else failure="Discard your edits before reloading saved defaults."
                        }) { Text("Reload saved defaults") }
                    }
                    SettingsPanel(draft, if(jobScope) PreferenceValues.of(inherited.entries +
                        loaded.document?.values?.entries.orEmpty().filterKeys { SettingCatalog[it].scope!=SettingScope.JOB }) else inherited, jobScope,
                        onValuesChanged={ values -> draftWire=SettingsCodec.encode(SettingsDocument(draft.saved.revision,values)); failure=null },
                        onSave={ save() }, onDiscard={ draftWire=baselineWire; failure=null }, onClose={ close() },
                        busy=busy, error=validation ?: failure, canSave=validation == null, backRequest=backRequest,
                        preset=if(jobScope) preset else PreferenceValues.EMPTY,
                        onChooseSaveFolder={ chooseSaveFolder.launch(null) },
                        saveFolderLabel=runCatching { graph.treeGrants.selected()?.label }.getOrNull(),
                        runControls={ SettingsRunControls(run.mode != RunMode.IDLE, run.mode == RunMode.STOPPING) { graph.runs.stop() } })
                }
            }
            retentionPending?.let { closeAfter ->
                val expired=ConsumerSettings.expiredHistory(jobs,ConsumerSettings.historyDays(draft?.values ?: PreferenceValues.EMPTY),System.currentTimeMillis())
                AlertDialog(onDismissRequest={retentionPending=null},title={Text("Shorter history retention?")},
                    text={Column {
                        Text("At next app launch, ${expired.size} completed items and their app-managed outputs will be removed. Active, recoverable and undated legacy entries are kept.")
                        jobs.filter { it.spec.id in expired }.take(8).forEach { Text(it.spec.source.name,style=MaterialTheme.typography.bodySmall) }
                        if(expired.size>8) Text("And ${expired.size-8} more")
                    }},confirmButton={TextButton(onClick={retentionPending=null;save(closeAfter,true)}) {Text("Save retention")}},
                    dismissButton={TextButton(onClick={retentionPending=null}) {Text("Keep editing")}})
            }
            if (confirmClose) AlertDialog(onDismissRequest={ confirmClose=false }, title={ Text("Keep your changes?") },
                text={ Text("Changes have not been ${if (jobScope) "applied to the editor" else "saved"}.") },
                confirmButton={ TextButton(enabled=validation == null && !busy, onClick={ save(true) }) { Text("Save and close") } },
                dismissButton={ Row {
                    TextButton(onClick={ confirmClose=false; onDismiss() }) { Text("Discard") }
                    TextButton(onClick={ confirmClose=false }) { Text("Keep editing") }
                } })
        }
    }
}
