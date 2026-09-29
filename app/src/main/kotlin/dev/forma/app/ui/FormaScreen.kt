@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.forma.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import dev.forma.app.ui.FormaButton as Button
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.app.ui.FormaOutlinedButton as OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forma.app.*
import dev.forma.app.data.LiveProgress
import dev.forma.app.work.*
import dev.forma.core.*
import dev.forma.core.image.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.ceil

/** Static/previews compatibility. The real route supplies progress only to a small child. */
@Composable fun FormaScreen(ui: TranscodeUiState, jobs: List<QueueEntry>, progress: LiveProgress?, onAction: (UiAction) -> Unit) {
    FormaWorkspace(ui, jobs, RunState(), onAction = onAction, progressContent = { ProgressView(it, progress) })
}

@Composable fun FormaWorkspace(
    ui: TranscodeUiState,
    jobs: List<QueueEntry>,
    run: RunState,
    initiallyQueue: Boolean = false,
    workspaceRequest: WorkspaceRequest? = null,
    onAction: (UiAction) -> Unit,
    progressContent: @Composable (QueueEntry) -> Unit
) {
    var page by rememberSaveable { mutableStateOf(if (initiallyQueue) "queue" else "home") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(workspaceRequest) {
        if (workspaceRequest != null) { page = if (workspaceRequest.showQueue) "queue" else "home"; drawer.close() }
    }
    val scope = rememberCoroutineScope()
    val active = remember(jobs) { jobs.firstOrNull { it.state in ACTIVE_STATES } }
    val waiting = remember(jobs) { jobs.count { it.state == JobState.QUEUED } }
    val running = run.mode != RunMode.IDLE
    BackHandler(drawer.isOpen || page != "home") {
        if (drawer.isOpen) scope.launch { drawer.close() } else page = "home"
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
            Text("Forma", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineMedium)
            NavigationDrawerItem(label = { Text("Convert media") }, selected = page == "home", onClick = { page = "home"; scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text("Queue · ${jobs.size}") }, selected = page == "queue", onClick = { page = "queue"; scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text("Advanced settings") }, selected = page == "home" && ui.editor.advanced,
                onClick = { page = "home"; if (!ui.editor.advanced) onAction(UiAction.ToggleAdvanced); scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text("App info") }, selected = page == "engine", onClick = { page = "engine"; scope.launch { drawer.close() } })
        }
    }) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (page == "queue") "Your queue" else if (page == "engine") "App info" else "Forma") },
                navigationIcon = { TextButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.testTag("open-shelf").semantics { contentDescription = "Open navigation" }) { Text("Menu") } },
                actions = { if (page != "queue" && jobs.isNotEmpty()) TextButton(onClick = { page = "queue" }) { Text("Queue ($waiting)") } }) },
            bottomBar = {
                Column(Modifier.imePadding().navigationBarsPadding()) {
                    if (running || active != null) Surface(tonalElevation = 4.dp) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(when (run.mode) {
                                    RunMode.DRAINING -> "Finishing current file"
                                    RunMode.STOPPING -> "Stopping safely…"
                                    else -> active?.let { stateLabel(it.state) } ?: "Starting conversion…"
                                }, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = { page = "queue" }) { Text("View queue") }
                                TextButton(onClick = { onAction(UiAction.StopQueue) }, enabled = run.mode != RunMode.STOPPING,
                                    modifier = Modifier.testTag("stop-queue")) { Text("Stop conversion") }
                            }
                            if (active != null && active.state == JobState.RUNNING) progressContent(active)
                        }
                    }
                    if (page == "home" && ui.sources.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${ui.sources.size} file(s) · ${if(ui.selected?.source?.imageInfo!=null) "Image" else ui.editor.settings.container.name}", style = MaterialTheme.typography.labelMedium)
                            val queueable = ui.ready && !ui.busy && !ui.validating && ui.problems.isEmpty()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onAction(UiAction.Queue) }, enabled = queueable, modifier = Modifier.weight(1f)) { Text("Add to queue") }
                                Button(onClick = { onAction(UiAction.Convert) }, enabled = queueable && ui.capabilities.available && ui.runtimeProblems.isEmpty() && run.mode != RunMode.STOPPING,
                                    modifier = Modifier.weight(1f).testTag("convert")) { Text(if (running) "Queue next" else "Convert") }
                            }
                            if (!ui.capabilities.available) Text("Encoder unavailable", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("editor"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ui.message?.let { message -> item(key = "message") {
                    var expanded by rememberSaveable(message) { mutableStateOf(false) }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(if (expanded) message.take(1200) else message.lineSequence().first().take(140),
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, maxLines = if (expanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            FlowRow {
                                if (message.length > 140 || '\n' in message) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Show details") }
                                if (!ui.ready) TextButton(onClick = { onAction(UiAction.RetryInitialization) }) { Text("Try again") }
                                TextButton(onClick = { onAction(UiAction.DismissMessageIf(message)) }) { Text("Dismiss") }
                            }
                        }
                    }
                } }
                ui.fileTask?.let { task -> item(key = "file-task") {
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(task.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { onAction(UiAction.CancelFileTask) }, enabled = !task.cancelling) {
                                Text(if (task.label.startsWith("Saving")) "Cancel save" else "Cancel import")
                            }
                        }
                        task.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                            ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                    } }
                } }
                when (page) {
                    "home" -> {
                        if (ui.sources.isEmpty()) item(key = "empty-home") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Convert media", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                                Text("Video, audio or image", style = MaterialTheme.typography.bodyLarge)
                                Button(onClick = { onAction(UiAction.Import) }, enabled = ui.ready && ui.fileTask == null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("select-media")) { Text("Add files") }
                                if (!ui.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                        if (ui.sources.isNotEmpty()) {
                            item(key = "sources-heading") { Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Selected media", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { onAction(UiAction.Import) }, enabled = ui.fileTask == null) { Text("Add files") }
                            } }
                            items(ui.sources, key = { "source:${it.source.uri}" }, contentType = { "source" }) { source ->
                                SourceCard(source, ui.selected?.source?.uri == source.source.uri, onAction)
                            }
                            if(ui.selected?.source?.imageInfo != null && ui.imageDocument != null) {
                                item(key="image-editor") { dev.forma.app.ui.image.ImageEditorPanel(ui.imageDocument!!,ui.selected!!.source.imageInfo!!,ui.imageEditor,ui.imagePreview,ui.capabilities,onAction) }
                            } else {
                            item(key = "audio-editor") { dev.forma.app.ui.audio.AudioEditorPanel(ui, onAction) }
                            item(key = "simple-options") { SimpleOptions(ui.editor, onAction) }
                            item(key = "advanced-toggle") {
                                OutlinedButton(onClick = { onAction(UiAction.ToggleAdvanced) }, modifier = Modifier.fillMaxWidth().testTag("mode-toggle")) {
                                    Text(if (ui.editor.advanced) "Hide advanced settings" else "Advanced settings")
                                }
                            }
                            if (ui.editor.advanced) item(key = "advanced-controls") { AdvancedControls(ui, onAction) }
                            item(key = "output-plan") { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Output", style = MaterialTheme.typography.titleMedium)
                                Text(outputDescription(ui.editor.settings))
                                if (ui.validating) Text("Checking settings…", style = MaterialTheme.typography.bodySmall)
                                (ui.problems + ui.runtimeProblems).distinct().take(3).forEach {
                                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                            } } }
                            }
                        }
                    }
                    "queue" -> {
                        item(key = "queue-heading") {
                            Text("$waiting waiting · ${jobs.count { it.state == JobState.COMPLETED }} ready", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (running) {
                                    OutlinedButton(onClick = { onAction(UiAction.FinishCurrent) }, enabled = run.mode == RunMode.RUNNING) { Text("Finish current, then stop") }
                                } else Button(onClick = { onAction(UiAction.StartQueue) }, enabled = ui.ready && !ui.busy && waiting > 0 && ui.capabilities.available) { Text("Convert queued files") }
                            }
                            if (run.mode == RunMode.DRAINING) Text("Finishing current file")
                            if (run.mode == RunMode.STOPPING) Text("Stopping…")
                            if (jobs.isEmpty()) Text("No queued files")
                        }
                        items(jobs, key = { "job:${it.spec.id}" }, contentType = { "job" }) { entry -> QueueCard(entry, onAction) }
                    }
                    else -> item(key = "engine") {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(if (ui.capabilities.available) "Encoder ready" else "Encoder unavailable", style = MaterialTheme.typography.titleLarge)
                            Text("Runs on this phone")
                            Text("Original files · Kept")
                            var details by rememberSaveable { mutableStateOf(false) }
                            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Engine details") }
                            if (details) Text(if (ui.capabilities.available) ui.capabilities.build else ui.capabilities.reason)
                            TextButton(onClick = { onAction(UiAction.RetryInitialization) }) { Text("Check encoder") }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable private fun SourceCard(edit: SourceEdit, selected: Boolean, action: (UiAction) -> Unit) {
    OutlinedCard(onClick = { action(UiAction.Select(edit.source.uri)) }, modifier = Modifier.fillMaxWidth().semantics { this.selected = selected }) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(edit.source.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (selected) Text("✓", Modifier.padding(start = 8.dp))
            }
            Text("${if(edit.source.imageInfo!=null) "Image" else mediaTime(edit.source.durationMs)} · ${if (edit.source.videoTracks > 0 || edit.source.imageInfo!=null) "${edit.source.width} × ${edit.source.height}" else "Audio"}" +
                if (edit.source.bytes > 0) " · ${mediaSize(edit.source.bytes)}" else "", style = MaterialTheme.typography.bodySmall)
            FlowRow {
                TextButton(onClick = { action(UiAction.OpenSource(edit.source.uri)) }) { Text(if(edit.source.imageInfo!=null)"View source" else "Play source") }
                TextButton(onClick = { action(UiAction.RemoveSource(edit.source.uri)) }) { Text("Remove from list") }
            }
        }
    }
}
@Composable private fun SimpleOptions(editor: Editor, action: (UiAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Choice("Convert to", editor.goal, Goal.entries, { it.label }) { action(UiAction.Preset(it, editor.quality)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q -> FilterChip(modifier = Modifier.formaTouchTarget(), selected = editor.quality == q && !editor.custom,
                onClick = { action(UiAction.Preset(editor.goal, q)) }, label = { Text(q.label) }) }
        }
        if (editor.custom) Text("Custom settings", style = MaterialTheme.typography.labelSmall)
    }
}
@Composable private fun QueueCard(entry: QueueEntry, action: (UiAction) -> Unit) {
    var details by rememberSaveable(entry.spec.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(entry.spec.source.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stateLabel(entry.state), style = MaterialTheme.typography.labelLarge)
        Text(if(entry.spec is QueueJobSpec.Image) "Image · ${(entry.spec as QueueJobSpec.Image).format.name}" else "${entry.spec.settings.container.name} · ${mediaTime(Planner.duration(entry.spec.source, entry.spec.trim))}", style = MaterialTheme.typography.bodySmall)
        if (entry.state == JobState.FAILED || entry.state == JobState.INTERRUPTED) {
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details") }
            if (details) Text(entry.message.take(1200), style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (entry.state) {
                JobState.COMPLETED -> {
                    Button(onClick = { action(UiAction.Share(entry.spec.id)) }) { Text("Share output") }
                    TextButton(onClick = { action(UiAction.OpenOutput(entry.spec.id)) }) { Text(if(entry.spec is QueueJobSpec.Image)"View output" else "Play output") }
                    TextButton(onClick = { action(UiAction.Export(entry.spec.id)) }) { Text("Save copy") }
                }
                JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED -> TextButton(onClick = { action(UiAction.Retry(entry.spec.id)) }) { Text("Retry conversion") }
                JobState.QUEUED -> TextButton(onClick = { action(UiAction.RemoveJob(entry.spec.id)) }) { Text("Remove queued file") }
                else -> Unit
            }
        }
    } }
}
@Composable fun ProgressView(entry: QueueEntry, live: LiveProgress?) {
    if(entry.spec is QueueJobSpec.Image) {
        val image=live?.takeIf{it.id==entry.spec.id}?.image
        Column(Modifier.testTag("live-image-progress")){Text(image?.let{"${it.stage.name.lowercase()} · attempt ${it.attempt}"}?:"Preparing image")
            val fraction=image?.fraction
            if(fraction==null)LinearProgressIndicator(Modifier.fillMaxWidth())else LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())}
        return
    }
    val progress = live?.takeIf { it.id == entry.spec.id }?.progress
    val stats = conversionStats(progress, Planner.duration(entry.spec.source, entry.spec.trim), entry.state == JobState.COMPLETED)
    val fraction = stats.percent?.div(100f)
    Column(Modifier.testTag("live-progress")) {
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProgressStat("Done", stats.percent?.let { "$it%" } ?: "—", Modifier.weight(1f))
            ProgressStat("Speed", stats.speed?.let { String.format(Locale.ROOT, "%.1f×", it) } ?: "—", Modifier.weight(1f))
            ProgressStat("ETA", stats.etaMs?.let { "~${mediaTime(ceil(it / 1000.0).toLong() * 1000)}" } ?: "—", Modifier.weight(1f))
            BatteryDrawStat(Modifier.weight(1.3f))
        }
    }
}
internal fun mediaTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
private fun mediaSize(bytes: Long) = if (bytes >= 1_000_000) String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
    else if (bytes >= 1000) "${bytes / 1000} KB" else "$bytes B"
private fun outputDescription(s: Settings) = "${s.container.name} · ${if (s.container == Container.M4A) "Audio only" else if (s.maxHeight == 0) "Original picture size" else "Up to ${s.maxHeight}p"} · ${if (s.audio == AudioEncoder.NONE) "No sound" else s.audio.name}"
private fun stateLabel(s: JobState) = when (s) {
    JobState.QUEUED -> "Waiting"; JobState.PREPARING -> "Preparing source"; JobState.RUNNING -> "Converting"
    JobState.VERIFYING -> "Checking output"; JobState.COMPLETED -> "100% · Ready"; JobState.FAILED -> "Could not convert"
    JobState.CANCELLED -> "Cancelled"; JobState.INTERRUPTED -> "Interrupted"
}
private val ACTIVE_STATES = setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING)
