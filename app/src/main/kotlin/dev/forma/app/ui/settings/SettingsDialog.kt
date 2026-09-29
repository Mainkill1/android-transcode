package dev.forma.app.ui.settings

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
@Composable fun SettingsDialog(current: Settings, jobScope: Boolean, onApplyToJob: (Settings) -> Unit, onDismiss: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as FormaApplication).graph
    val loaded by graph.settings.state.collectAsStateWithLifecycle()
    val run by graph.runs.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var baselineWire by rememberSaveable { mutableStateOf<String?>(null) }
    var draftWire by rememberSaveable { mutableStateOf<String?>(null) }
    var inheritedWire by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var confirmClose by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loaded.document) {
        if (baselineWire == null) loaded.document?.let { saved ->
            try {
                inheritedWire=SettingsCodec.encode(saved)
                val initial=if (jobScope) SettingsDocument(saved.revision, NativePreferences.capture(current)) else saved
                baselineWire=SettingsCodec.encode(initial); draftWire=baselineWire
            } catch (error: IllegalArgumentException) { failure="Current settings could not be imported: ${error.message}" }
        }
    }
    val base = remember(baselineWire) { baselineWire?.let(SettingsCodec::decode) }
    val draft = remember(base, draftWire) { base?.let { SettingsDraft(it, draftWire?.let(SettingsCodec::decode)?.values ?: it.values) } }
    val inherited = remember(inheritedWire) { inheritedWire?.let(SettingsCodec::decode)?.values ?: PreferenceValues.EMPTY }
    val validation = remember(draft, inherited, jobScope, current) {
        draft?.let { runCatching {
            NativePreferences.apply(current, SettingsResolver.resolve(if (jobScope) inherited else it.values,
                job=if (jobScope) it.values else PreferenceValues.EMPTY))
        }.exceptionOrNull()?.message }
    }
    val changedElsewhere = base != null && loaded.document != null && base.revision != loaded.document?.revision
    fun close() { if (!busy) { if (draft?.dirty == true) confirmClose=true else onDismiss() } }
    fun save(closeAfter: Boolean = false) {
        val editing=draft ?: return
        if (busy || validation != null) return
        failure=null; confirmClose=false
        if (jobScope) {
            try {
                val result=NativePreferences.apply(current, SettingsResolver.resolve(inherited, job=editing.values))
                onApplyToJob(result); onDismiss()
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
    val preview = if (jobScope) inherited else draft?.values ?: loaded.document?.values ?: PreferenceValues.EMPTY
    Dialog(onDismissRequest={ close() }, properties=DialogProperties(usePlatformDefaultWidth=false, dismissOnClickOutside=false, dismissOnBackPress=false)) {
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
                    SettingsPanel(draft, inherited, jobScope,
                        onValuesChanged={ values -> draftWire=SettingsCodec.encode(SettingsDocument(draft.saved.revision,values)); failure=null },
                        onSave={ save() }, onDiscard={ draftWire=baselineWire; failure=null }, onClose={ close() },
                        busy=busy, error=validation ?: failure, canSave=validation == null,
                        runControls={ SettingsRunControls(run.mode != RunMode.IDLE, run.mode == RunMode.STOPPING) { graph.runs.stop() } })
                }
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
