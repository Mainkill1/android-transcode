@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.forma.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.forma.app.data.DeliveryCopyProgress
import dev.forma.app.work.*
import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.ffmpeg.AttemptStatus
import java.math.BigDecimal
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
    progressContent: @Composable (QueueEntry) -> Unit,
    deliveryProgressContent: @Composable (QueueEntry) -> Unit = {}
) {
    var page by rememberSaveable { mutableStateOf(if (initiallyQueue) "queue" else "home") }
    val listState = rememberLazyListState()
    LaunchedEffect(page) { listState.scrollToItem(0) }
    var settingsScope by rememberSaveable { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    LaunchedEffect(workspaceRequest) {
        if (workspaceRequest != null) { page = if (workspaceRequest.showQueue) "queue" else "home"; drawer.close() }
    }
    val scope = rememberCoroutineScope()
    val active = remember(jobs) { jobs.firstOrNull { it.state in ACTIVE_STATES } }
    val waiting = remember(jobs) { jobs.count { it.state == JobState.QUEUED } }
    val failed = remember(jobs) { jobs.count { it.state == JobState.FAILED } }
    val queuedJobs = remember(jobs) { jobs.filter { QueueLists.inQueue(it.state) } }
    val finishedJobs = remember(jobs) {
        jobs.filter { it.state == JobState.FAILED } + jobs.filter { QueueLists.inFinished(it.state) && it.state != JobState.FAILED }
    }
    val queueLabel = "Queue (${queuedJobs.size})"
    val running = run.mode != RunMode.IDLE
    BackHandler(drawer.isOpen || page != "home") {
        if (drawer.isOpen) scope.launch { drawer.close() } else page = "home"
    }
    ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = {
        ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
            Text("Forma", Modifier.padding(24.dp), style = MaterialTheme.typography.headlineMedium)
            NavigationDrawerItem(label = { Text("Convert media") }, selected = page == "home", onClick = { page = "home"; scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text("Make a movie") }, selected = page == "movie", onClick = { page = "movie"; scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text(queueLabel) }, selected = page == "queue", onClick = { page = "queue"; scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text("Finished (${finishedJobs.size})") }, selected = page == "finished", onClick = { page = "finished"; scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text("Advanced settings") }, selected = page == "home" && ui.editor.advanced,
                onClick = { page = "home"; if (!ui.editor.advanced) onAction(UiAction.ToggleAdvanced); scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text("App info") }, selected = page == "engine", onClick = { page = "engine"; scope.launch { drawer.close() } })
            NavigationDrawerItem(label = { Text("Settings") }, selected = false,
                onClick = { settingsScope = "app"; scope.launch { drawer.close() } }, modifier = Modifier.testTag("open-settings"))

        }
    }) {
        Scaffold(
            topBar = { TopAppBar(title = { Text(if (page == "queue") "Queue" else if (page == "finished") "Finished" else if (page == "engine") "App info" else if (page == "movie") "Your movie" else "Forma") },
                navigationIcon = { TextButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.testTag("open-shelf").semantics { contentDescription = "Open navigation" }) { Text("Menu") } },
                actions = { if (page != "queue" && queuedJobs.isNotEmpty()) TextButton(onClick = { page = "queue" }) { Text(queueLabel) } }) },
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
                            if (active != null && (active.state == JobState.RUNNING || (active.spec is QueueJobSpec.Image && active.state == JobState.VERIFYING))) progressContent(active)
                        }
                    }
                    if (page == "home" && ui.sources.isNotEmpty()) Surface(tonalElevation = 3.dp) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val image=ui.imageDocument;val info=ui.selected?.source?.imageInfo
                            val summary=if(image!=null && info!=null){
                                val size=runCatching{ImageGeometry.resolve(info,image,ImageAttempt(0,ImageFormat.PNG,90)).outputSize}.getOrNull()
                                "${size?.let{"${it.width} × ${it.height}"}?:"Check dimensions"} · ${image.output.format.name} · ${image.output.targetBytes?.let{"< $it bytes"}?:"No size limit"}"
                            }else "${ui.sources.size} file(s) · ${ui.editor.settings.container.name} · ${byteLimitLabel(ui.targetBytes)}"
                            Text(summary, style = MaterialTheme.typography.labelMedium)
                            Text("Saves to ${ui.saveLocationLabel}", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.testTag("save-location"))
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
            LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("editor"), state = listState,
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
                                if (failed > 0) TextButton(onClick = { page = "finished" }) { Text("View failed job") }
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
                        if(ui.sources.isEmpty() || ui.sources.any { it.source.imageInfo==null }) item(key="size-limit") {
                            if(ui.sources.any { it.source.imageInfo!=null }) Text("Video/audio limit",style=MaterialTheme.typography.labelMedium)
                            ByteLimitControl(ui.targetBytes) { onAction(UiAction.SetTargetBytes(it)) }
                        }
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
                                item(key="image-editor") { dev.forma.app.ui.image.ImageEditorPanel(ui.imageDocument!!,ui.selected!!.source.imageInfo!!,ui.imageEditor,ui.imagePreview,ui.capabilities,onAction)
                                    (ui.problems+ui.runtimeProblems).distinct().take(3).forEach{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                                }
                            } else {
                                item(key = "audio-editor") { dev.forma.app.ui.audio.AudioEditorPanel(ui, onAction) }
                                item(key = "movie-entry") { OutlinedButton(onClick = { page = "movie" }, modifier = Modifier.fillMaxWidth().testTag("open-movie")) { Text("Make a movie") } }
                                item(key = "simple-options") { SimpleOptions(ui.editor, onAction) }
                                item(key = "override-summary") {
                                    if(ui.editor.preferences.overrideCount>0) AssistChip(onClick={settingsScope="job"},
                                        label={Text("${ui.editor.preferences.overrideCount} overrides")}, modifier=Modifier.testTag("overrides-summary"))
                                }
                                item(key = "advanced-toggle") {
                                    OutlinedButton(onClick = { onAction(UiAction.ToggleAdvanced) }, modifier = Modifier.fillMaxWidth().testTag("mode-toggle")) {
                                        Text(if (ui.editor.advanced) "Hide advanced settings" else "Advanced settings")
                                    }
                                }
                                if (ui.editor.advanced) item(key = "advanced-controls") { Column {
                                    TextButton(onClick = { settingsScope = "job" }, modifier = Modifier.testTag("job-settings")) { Text("Defaults & overrides · ${ui.editor.preferences.overrideCount}") }
                                    AdvancedControls(ui, onAction)
                                } }

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
                    "movie" -> {
                        item(key = "movie-controls") { MovieControls(ui, jobs, run, onAction) }
                    }
                    "queue" -> {
                        item(key = "queue-heading") {
                            QueueListSwitcher(page, queuedJobs.size, finishedJobs.size) { page = it }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (running) {
                                    OutlinedButton(onClick = { onAction(UiAction.FinishCurrent) }, enabled = run.mode == RunMode.RUNNING) { Text("Finish current, then stop") }
                                } else Button(onClick = { onAction(UiAction.StartQueue) }, enabled = ui.ready && !ui.busy && waiting > 0 && ui.capabilities.available) { Text("Convert queued files") }
                            }
                            if (run.mode == RunMode.DRAINING) Text("Finishing current file")
                            if (run.mode == RunMode.STOPPING) Text("Stopping…")
                            if (queuedJobs.isEmpty()) Text("Queue is empty")
                        }
                        items(queuedJobs,
                            key = { "job:${it.spec.id}" }, contentType = { "job" }) { entry -> QueueCard(entry, onAction,deliveryProgressContent) }
                    }
                    "finished" -> {
                        item(key = "finished-heading") { QueueListSwitcher(page, queuedJobs.size, finishedJobs.size) { page = it } }
                        if (finishedJobs.isEmpty()) item(key = "finished-empty") { Text("No finished conversions yet") }
                        items(finishedJobs, key = { "job:${it.spec.id}" }, contentType = { "job" }) { entry -> QueueCard(entry, onAction,deliveryProgressContent) }
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
    settingsScope?.let { selectedScope ->
        dev.forma.app.ui.settings.SettingsDialog(ui.editor.settings, selectedScope == "job",
            onApplyToJob = { settings ->
                val problems = ui.sources.filter { it.source.imageInfo == null }.flatMap { JobPlans.validate(JobSpec("settings",it.source,it.trim,it.snapshot(settings),targetBytes=ui.targetBytes)) }.distinct()
                require(problems.isEmpty()) { problems.joinToString("\n") }
                onAction(UiAction.ChangeSettings(settings))
            }, onDismiss = { settingsScope = null }, preferences=ui.editor.preferences,
            onApplyPreferences={ settings,preferences ->
                val problems=ui.sources.filter { it.source.imageInfo == null }.flatMap { JobPlans.validate(JobSpec("settings",it.source,it.trim,it.snapshot(settings),targetBytes=ui.targetBytes)) }.distinct()
                require(problems.isEmpty()) { problems.joinToString("\n") }
                onAction(UiAction.ChangeSettings(settings,preferences))
            })
    }
}

@Composable private fun QueueListSwitcher(page: String, queued: Int, finished: Int, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = page == "queue", onClick = { onSelect("queue") },
            label = { Text("Queue ($queued)") }, modifier = Modifier.testTag("queue-list"))
        FilterChip(selected = page == "finished", onClick = { onSelect("finished") },
            label = { Text("Finished ($finished)") }, modifier = Modifier.testTag("finished-list"))
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
    var pending by remember { mutableStateOf<UiAction.Preset?>(null) }
    fun select(value: UiAction.Preset) { if(editor.preferences.overrideCount>0) pending=value else action(value) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Choice("Convert to", editor.goal, Goal.entries, { it.label }) { select(UiAction.Preset(it, editor.quality)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q -> FilterChip(modifier = Modifier.formaTouchTarget(), selected = editor.quality == q && !editor.custom,
                onClick = { select(UiAction.Preset(editor.goal, q)) }, label = { Text(q.label) }) }
        }
        if (editor.custom) Text("Custom settings", style = MaterialTheme.typography.labelSmall)
    }
    pending?.let { value ->
        AlertDialog(onDismissRequest={pending=null},title={Text("Apply preset?")},
            text={Column {
                Text("${value.goal.label} · ${value.quality.label}")
                val preset=dev.forma.app.audio.AudioEditorSettings.preset(editor.settings,value.goal,value.quality)
                val before=dev.forma.core.settings.NativePreferences.capture(editor.settings)
                val after=dev.forma.core.settings.NativePreferences.capture(preset)
                after.entries.filter { (id,v) -> before[id]!=v }.forEach { (id,v) ->
                    val spec=dev.forma.core.settings.SettingCatalog[id]
                    Text("${spec.label}: ${spec.display(v)}",style=MaterialTheme.typography.bodySmall)
                }
                Text("${editor.preferences.overrideCount} job overrides can be kept or replaced.")
            }}, confirmButton={TextButton(onClick={pending=null;action(value.copy(keepOverrides=true))}) {Text("Keep my overrides")}},
            dismissButton={TextButton(onClick={pending=null;action(value)}) {Text("Replace overrides")}})
    }
}
@Composable private fun QueueCard(entry: QueueEntry, action: (UiAction) -> Unit,
    deliveryProgressContent: @Composable (QueueEntry) -> Unit) {
    var details by rememberSaveable(entry.spec.id) { mutableStateOf(false) }
    var settingsDetails by rememberSaveable(entry.spec.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(entry.spec.source.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(stateLabel(entry.state), style = MaterialTheme.typography.labelLarge)
        val description=when(val spec=entry.spec) {
            is QueueJobSpec.Image -> "Image · ${spec.format.name}"
            is QueueJobSpec.Av -> (spec.job.sequence?.let { "Movie · ${it.timeline.clips.size} clips · " } ?: "") +
                "${spec.settings.container.name} · ${queueDurationLabel(spec.job)} · " +
                if (spec.settings.container.audioOnly) spec.settings.audio.name else spec.settings.video.label
        }
        Text(description, style = MaterialTheme.typography.bodySmall)
        Text(byteLimitLabel(entry.spec.targetBytes),style=MaterialTheme.typography.bodySmall)
        if(entry.state==JobState.COMPLETED) {
            val location=when(val destination=entry.delivery.destination) {
                is SaveDestination.FormaLibrary -> when(destination.category) {
                    MediaCategory.VIDEO -> "Movies/Forma"
                    MediaCategory.AUDIO -> "Music/Forma"
                    MediaCategory.IMAGE -> "Pictures/Forma"
                }
                is SaveDestination.DocumentTree -> destination.label
                null -> "Forma"
            }
            val receipt=entry.delivery.receipt
            Text(when(receipt) {
                is DeliveryReceipt.Saved -> "Saved to $location · ${receipt.displayName}"
                is DeliveryReceipt.Copying -> "Saving to $location…"
                DeliveryReceipt.Waiting -> "Saving to $location…"
                is DeliveryReceipt.Failed -> "Converted; save failed · $location"
                DeliveryReceipt.PrivateLegacy -> "Private in Forma"
            },style=MaterialTheme.typography.bodySmall)
            if(receipt is DeliveryReceipt.Failed) Text(receipt.message,style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.error)
            if(receipt is DeliveryReceipt.Copying) deliveryProgressContent(entry)
        }
        TextButton(onClick={settingsDetails=!settingsDetails}) { Text(if(settingsDetails) "Hide settings" else "Settings snapshot") }
        if(settingsDetails) {
            Text("Defaults revision ${entry.spec.preferences.app.revision} · ${entry.spec.preferences.overrideCount} overrides",style=MaterialTheme.typography.labelSmall)
            entry.spec.preferences.presetName?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            entry.spec.preferences.resolve().filterKeys { it in dev.forma.core.settings.NativePreferences.boundIds }.forEach { (id,value) ->
                val spec=dev.forma.core.settings.SettingCatalog[id]
                Text("${spec.label}: ${spec.display(value.value)} · ${value.origin.name.lowercase()}",style=MaterialTheme.typography.bodySmall)
            }
        }
        if (entry.state == JobState.FAILED || entry.state == JobState.INTERRUPTED) {
            Text(if (details) entry.message.take(1200) else entry.message.lineSequence().first().take(180),
                style = MaterialTheme.typography.bodySmall, maxLines = if (details) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis)
            if (entry.message.length > 180 || '\n' in entry.message)
                TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when (entry.state) {
                JobState.COMPLETED -> {
                    Button(onClick = { action(UiAction.Share(entry.spec.id)) }) { Text("Share output") }
                    TextButton(onClick = { action(UiAction.OpenOutput(entry.spec.id)) }) { Text(if(entry.delivery.receipt is DeliveryReceipt.Saved) "View saved file"
                        else if(entry.spec is QueueJobSpec.Image) "View output" else "Play output") }
                    if(entry.delivery.receipt==DeliveryReceipt.Waiting || entry.delivery.receipt is DeliveryReceipt.Copying ||
                        entry.delivery.receipt is DeliveryReceipt.Failed)
                        TextButton(onClick = { action(UiAction.RetrySave(entry.spec.id)) }) { Text("Retry save") }
                    TextButton(onClick = { action(UiAction.Export(entry.spec.id)) }) { Text("Save another copy") }
                }
                JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED -> TextButton(onClick = { action(UiAction.Retry(entry.spec.id)) }) { Text("Add retry to queue") }
                JobState.QUEUED -> TextButton(onClick = { action(UiAction.RemoveJob(entry.spec.id)) }) { Text("Remove queued file") }
                else -> Unit
            }
        }
    } }
}
@Composable fun DeliveryProgressView(progress:DeliveryCopyProgress?) {
    if(progress==null) return
    val copied=progress.copiedBytes.coerceAtLeast(0)
    val total=progress.totalBytes.coerceAtLeast(0)
    val label=if(total<1_048_576) "${copied/1_024} of ${total/1_024} KiB copied"
        else "${copied/1_048_576} of ${total/1_048_576} MB copied"
    Column(Modifier.testTag("save-copy-progress")) {
        Text(label,style=MaterialTheme.typography.bodySmall)
        if(total>0) LinearProgressIndicator(progress={ (copied.toFloat()/total).coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
@Composable fun ProgressView(entry: QueueEntry, live: LiveProgress?) {
    if(entry.spec is QueueJobSpec.Image) {
        val image=live?.takeIf{it.id==entry.spec.id}?.image
        Column(Modifier.testTag("live-image-progress")){Text(image?.let{"${it.stage.name.lowercase()} · attempt ${it.attempt}"}?:"Preparing image")
            val fraction=image?.fraction
            if(fraction==null)LinearProgressIndicator(Modifier.fillMaxWidth())else LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth())}
        return
    }
    val current=live?.takeIf { it.id == entry.spec.id }
    val progress = current?.progress
    val stats = conversionStats(progress, JobPlans.duration((entry.spec as QueueJobSpec.Av).job), entry.state == JobState.COMPLETED)
    val fraction = stats.percent?.div(100f)
    Column(Modifier.testTag("live-progress")) {
        current?.attempt?.let { event ->
            val route=event.attempt.decision
            val backend=when(route?.backend) { EncodeBackend.MEDIACODEC->"MediaCodec";EncodeBackend.SOFTWARE->"Software";else->null }
            Text("Attempt ${event.number}/${event.total}" + (backend?.let { " · $it" } ?: ""),style=MaterialTheme.typography.labelSmall)
            route?.let { (it.codecName ?: it.encoder)?.let { name -> Text(name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall) } }
            if(event.status==AttemptStatus.REJECTED)Text("Trying next encoder",style=MaterialTheme.typography.bodySmall)
        }
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
private fun byteLimitLabel(bytes:Long?) = bytes?.let { "< ${BigDecimal(it).movePointLeft(6).stripTrailingZeros().toPlainString()} MB" } ?: "No size limit"
private fun outputDescription(s: Settings) = "${s.container.name} · ${if (s.container.audioOnly) "Audio only" else if (s.maxHeight == 0) "Original picture size" else "Up to ${s.maxHeight}p"} · ${if (s.audio == AudioEncoder.NONE) "No sound" else s.audio.name}"
private fun stateLabel(s: JobState) = when (s) {
    JobState.QUEUED -> "Waiting"; JobState.PREPARING -> "Preparing source"; JobState.RUNNING -> "Converting"
    JobState.VERIFYING -> "Checking output"; JobState.COMPLETED -> "100% · Ready"; JobState.FAILED -> "Could not convert"
    JobState.CANCELLED -> "Cancelled"; JobState.INTERRUPTED -> "Interrupted"
}
private val ACTIVE_STATES = setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING)
