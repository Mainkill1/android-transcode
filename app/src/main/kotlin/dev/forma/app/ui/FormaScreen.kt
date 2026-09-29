@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.forma.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
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
import kotlinx.coroutines.launch

/** Static/previews compatibility. The real route supplies progress only to a small child. */
@Composable fun FormaScreen(ui: TranscodeUiState, jobs: List<QueueEntry>, progress: LiveProgress?, onAction: (UiAction) -> Unit) {
    FormaWorkspace(ui, jobs, RunState(), onAction = onAction, progressContent = { ProgressView(it, progress) })
}

@Composable fun FormaWorkspace(
    ui: TranscodeUiState,
    jobs: List<QueueEntry>,
    run: RunState,
    initiallyQueue: Boolean = false,
    onAction: (UiAction) -> Unit,
    progressContent: @Composable (QueueEntry) -> Unit
) {
    var page by rememberSaveable { mutableStateOf(if (initiallyQueue) "queue" else "home") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
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
            NavigationDrawerItem(label = { Text("Advanced controls") }, selected = page == "home" && ui.editor.advanced,
                onClick = { page = "home"; if (!ui.editor.advanced) onAction(UiAction.ToggleAdvanced); scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text("Engine & background work") }, selected = page == "engine", onClick = { page = "engine"; scope.launch { drawer.close() } })
            Text("Original files stay untouched.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall)
        }
    }) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (page == "queue") "Your queue" else if (page == "engine") "Engine & background work" else "Forma") },
                navigationIcon = { TextButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.testTag("open-shelf").semantics { contentDescription = "Open navigation" }) { Text("Menu") } },
                actions = { if (page != "queue" && jobs.isNotEmpty()) TextButton(onClick = { page = "queue" }) { Text("Queue ($waiting)") } }) },
            bottomBar = {
                Column(Modifier.imePadding()) {
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
                                    modifier = Modifier.testTag("stop-queue")) { Text("Stop") }
                            }
                            if (active != null && active.state == JobState.RUNNING) progressContent(active)
                        }
                    }
                    if (page == "home" && ui.sources.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                        Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${ui.sources.size} file(s) · ${ui.editor.settings.container.name} · originals unchanged", style = MaterialTheme.typography.labelMedium)
                            val queueable = ui.ready && !ui.busy && !ui.validating && ui.problems.isEmpty()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onAction(UiAction.Queue) }, enabled = queueable, modifier = Modifier.weight(1f)) { Text("Add to queue") }
                                Button(onClick = { onAction(UiAction.Convert) }, enabled = queueable && ui.capabilities.available && ui.runtimeProblems.isEmpty() && run.mode != RunMode.STOPPING,
                                    modifier = Modifier.weight(1f).testTag("convert")) { Text(if (running) "Queue next" else "Convert") }
                            }
                            if (!ui.capabilities.available) Text("Conversion needs the native FFmpeg build. Jobs can still be prepared.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("editor"),
                contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ui.message?.let { message -> item(key = "message") {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(message.take(1200), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                            FlowRow {
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
                            TextButton(onClick = { onAction(UiAction.CancelFileTask) }, enabled = !task.cancelling) { Text("Cancel") }
                        }
                        task.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
                            ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("You can keep editing. This does not stop the conversion queue.", style = MaterialTheme.typography.bodySmall)
                    } }
                } }
                when (page) {
                    "home" -> {
                        if (ui.sources.isEmpty()) item(key = "empty-home") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text("Your media.\nReady to share.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                                Text("Select a video or audio file. Choose the result next.", style = MaterialTheme.typography.bodyLarge)
                                Button(onClick = { onAction(UiAction.Import) }, enabled = ui.ready && ui.fileTask == null,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("select-media")) { Text("Select media") }
                                if (!ui.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Everything converts on your device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (ui.sources.isNotEmpty()) {
                            item(key = "sources-heading") { Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Selected media", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = { onAction(UiAction.Import) }, enabled = ui.fileTask == null) { Text("Add media") }
                            } }
                            items(ui.sources, key = { "source:${it.source.uri}" }, contentType = { "source" }) { source ->
                                SourceCard(source, ui.selected?.source?.uri == source.source.uri, onAction)
                            }
                            item(key = "simple-options") { SimpleOptions(ui.editor, onAction) }
                            item(key = "advanced-toggle") { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (ui.editor.custom) "Custom settings are active" else "Need more control?", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { onAction(UiAction.ToggleAdvanced) }, modifier = Modifier.testTag("mode-toggle")) { Text(if (ui.editor.advanced) "Less settings" else "More settings") }
                            } }
                            if (ui.editor.advanced) item(key = "advanced-controls") { AdvancedControls(ui, onAction) }
                            item(key = "output-plan") { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Output", style = MaterialTheme.typography.titleMedium)
                                Text(outputDescription(ui.editor.settings))
                                if (ui.validating) Text("Checking settings…", style = MaterialTheme.typography.bodySmall)
                                (ui.problems + ui.runtimeProblems).distinct().take(3).forEach {
                                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Save or share after the output has been checked.", style = MaterialTheme.typography.bodySmall)
                            } } }
                        }
                    }
                    "queue" -> {
                        item(key = "queue-heading") {
                            Text(if (running) "Keep using your phone while Forma works." else "$waiting waiting · ${jobs.count { it.state == JobState.COMPLETED }} ready", style = MaterialTheme.typography.titleMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (running) {
                                    OutlinedButton(onClick = { onAction(UiAction.FinishCurrent) }, enabled = run.mode == RunMode.RUNNING) { Text("Finish current, then stop") }
                                } else Button(onClick = { onAction(UiAction.StartQueue) }, enabled = ui.ready && !ui.busy && waiting > 0 && ui.capabilities.available) { Text("Start waiting jobs") }
                            }
                            if (run.mode == RunMode.DRAINING) Text("The current file will finish. Remaining jobs stay queued.")
                            if (run.mode == RunMode.STOPPING) Text("Waiting for the encoder to release its files. New conversions are temporarily held.")
                            if (jobs.isEmpty()) Text("No jobs yet. Select media to prepare your first conversion.")
                        }
                        items(jobs, key = { "job:${it.spec.id}" }, contentType = { "job" }) { entry -> QueueCard(entry, onAction) }
                    }
                    else -> item(key = "engine") {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(if (ui.capabilities.available) "FFmpeg ready" else "Native encoder not available", style = MaterialTheme.typography.titleLarge)
                            Text(if (ui.capabilities.available) ui.capabilities.build else ui.capabilities.reason)
                            Text("Conversions use a foreground task with a notification. You can leave this screen; do not force-stop the app. Android may stop work when its processing allowance expires.")
                            Text("Stop cancels the active file. Finish current lets that file complete and leaves the rest waiting. Interrupted files restart as new jobs, not from a partial output.")
                            Text("The native size-goal, media-URL, image and full timeline ports remain separate milestones. No unsupported controls are presented as working.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onAction(UiAction.RetryInitialization) }) { Text("Check engine again") }
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
            Text(edit.source.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${mediaTime(edit.source.durationMs)} · ${if (edit.source.videoTracks > 0) "${edit.source.width} × ${edit.source.height}" else "Audio"}", style = MaterialTheme.typography.bodySmall)
            FlowRow {
                TextButton(onClick = { action(UiAction.OpenSource(edit.source.uri)) }) { Text("Open original") }
                TextButton(onClick = { action(UiAction.RemoveSource(edit.source.uri)) }) { Text("Remove") }
                if (selected) Text("Selected", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
@Composable private fun SimpleOptions(editor: Editor, action: (UiAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("What would you like to do?", style = MaterialTheme.typography.titleMedium)
        Choice("Result", editor.goal, Goal.entries, { it.label }) { action(UiAction.Preset(it, editor.quality)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q -> FilterChip(selected = editor.quality == q && !editor.custom,
                onClick = { action(UiAction.Preset(editor.goal, q)) }, label = { Text(q.label) }) }
        }
        if (editor.custom) Text("Choosing a result or quality replaces custom encoder settings, not your trims.", style = MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun QueueCard(entry: QueueEntry, action: (UiAction) -> Unit) {
    var details by rememberSaveable(entry.spec.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(entry.spec.source.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stateLabel(entry.state), style = MaterialTheme.typography.labelLarge)
        Text("${entry.spec.settings.container.name} · ${mediaTime(Planner.duration(entry.spec.source, entry.spec.trim))}", style = MaterialTheme.typography.bodySmall)
        if (entry.state == JobState.FAILED || entry.state == JobState.INTERRUPTED) {
            Text("This job needs attention. Other queued files are kept.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details") }
            if (details) Text(entry.message.take(1200), style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (entry.state) {
                JobState.COMPLETED -> {
                    Button(onClick = { action(UiAction.Share(entry.spec.id)) }) { Text("Share") }
                    TextButton(onClick = { action(UiAction.OpenOutput(entry.spec.id)) }) { Text("Open") }
                    TextButton(onClick = { action(UiAction.Export(entry.spec.id)) }) { Text("Save copy") }
                }
                JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED -> TextButton(onClick = { action(UiAction.Retry(entry.spec.id)) }) { Text("Retry as new job") }
                JobState.QUEUED -> TextButton(onClick = { action(UiAction.RemoveJob(entry.spec.id)) }) { Text("Remove") }
                else -> Unit
            }
        }
    } }
}
@Composable fun ProgressView(entry: QueueEntry, live: LiveProgress?) {
    val fraction = if (live?.id == entry.spec.id) WorkPolicy.fraction(live.progress.processedMs, Planner.duration(entry.spec.source, entry.spec.trim)) else null
    Column(Modifier.testTag("live-progress")) {
        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(if (fraction == null) "Waiting for encoder progress…" else "${(fraction * 100).toInt()}% · ${entry.spec.source.name}",
            maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
    }
}
internal fun mediaTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
private fun outputDescription(s: Settings) = "${s.container.name} · ${if (s.container == Container.M4A) "Audio only" else if (s.maxHeight == 0) "Original picture size" else "Up to ${s.maxHeight}p"} · ${if (s.audio == AudioEncoder.NONE) "No sound" else s.audio.name}"
private fun stateLabel(s: JobState) = when (s) {
    JobState.QUEUED -> "Waiting"; JobState.PREPARING -> "Preparing source"; JobState.RUNNING -> "Converting"
    JobState.VERIFYING -> "Checking output"; JobState.COMPLETED -> "Ready to share"; JobState.FAILED -> "Could not convert"
    JobState.CANCELLED -> "Cancelled"; JobState.INTERRUPTED -> "Interrupted"
}
private val ACTIVE_STATES = setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING)
