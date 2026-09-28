@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.forma.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.forma.app.*
import dev.forma.app.data.LiveProgress
import dev.forma.core.*
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** One workflow; Advanced reveals controls in-place, never replaces the editor. */
@Composable fun FormaScreen(
    ui: TranscodeUiState,
    jobs: List<QueueEntry>,
    progress: LiveProgress?,
    onAction: (UiAction) -> Unit
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it.take(600)); onAction(UiAction.DismissMessage) }
    }
    val s = ui.editor.settings
    val queueable = ui.ready && !ui.busy && ui.sources.isNotEmpty() && ui.problems.isEmpty()
    val runtimeProblems = if (ui.capabilities.available) ui.sources.flatMap {
        Planner.validate(it.source, it.trim, s, ui.capabilities)
    }.distinct() else emptyList()
    val running = jobs.any { it.state in setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING) }
    Scaffold(
        topBar = {
            Surface {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("forma", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Your media. A better fit.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("ON DEVICE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 5.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ui.sources.isEmpty()) "Choose a file to get started" else
                        "${ui.sources.size} file(s) · ${s.container.name} · ${if (s.container == Container.M4A) "Audio only" else if (s.maxHeight == 0) "Source size" else "Up to ${s.maxHeight}p"}",
                        style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { onAction(UiAction.Queue) }, enabled = queueable, modifier = Modifier.weight(1f)) { Text("Add to queue") }
                        Button(onClick = { onAction(UiAction.Convert) }, enabled = queueable && ui.capabilities.available && runtimeProblems.isEmpty() && !running,
                            modifier = Modifier.weight(1f).testTag("convert")) { Text("Convert") }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxWidth().testTag("editor"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Your files", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onAction(UiAction.Import) }, enabled = ui.ready && !ui.busy) { Text("Add files") }
                    }
                }
                if (ui.sources.isEmpty()) item {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Less file. Same story.", style = MaterialTheme.typography.headlineSmall)
                            Text("Make a video smaller, save it for sharing, or keep just the sound.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { onAction(UiAction.Import) }, enabled = ui.ready && !ui.busy) { Text("Choose media") }
                            Text("Originals stay untouched.", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                items(ui.sources, key = { it.source.uri }) { edit ->
                    val selected = ui.selected?.source?.uri == edit.source.uri
                    OutlinedCard(onClick = { onAction(UiAction.Select(edit.source.uri)) }, modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(edit.source.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${time(edit.source.durationMs)} · ${if (edit.source.width > 0) "${edit.source.width} × ${edit.source.height}" else "Audio"}", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { onAction(UiAction.OpenSource(edit.source.uri)) }) { Text("Open original") }
                                TextButton(onClick = { onAction(UiAction.RemoveSource(edit.source.uri)) }, enabled = !ui.busy) { Text("Remove") }
                                if (selected) Text("Editing", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterVertically))
                            }
                        }
                    }
                }
                if (ui.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("What would you like to do?", style = MaterialTheme.typography.titleMedium)
                        Goal.entries.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { goal ->
                                    val selected = ui.editor.goal == goal && !ui.editor.custom
                                    OutlinedCard(onClick = { onAction(UiAction.Preset(goal, ui.editor.quality)) }, modifier = Modifier.weight(1f),
                                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                        Column(Modifier.padding(12.dp).heightIn(min = 80.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text(goal.label, style = MaterialTheme.typography.titleSmall, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                            Text(goal.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Quality.entries.forEach { q -> FilterChip(selected = ui.editor.quality == q && !ui.editor.custom,
                                onClick = { onAction(UiAction.Preset(ui.editor.goal, q)) }, label = { Text(q.label) }) }
                        }
                        if (ui.editor.custom) Text("Your custom settings are still active. Choosing a goal or quality above applies a new preset.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().testTag("mode-toggle").toggleable(ui.editor.advanced, role = Role.Switch,
                        onValueChange = { onAction(UiAction.ToggleAdvanced) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Advanced", style = MaterialTheme.typography.titleMedium)
                            Text("More control, same conversion", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = ui.editor.advanced, onCheckedChange = null)
                    }
                }
                if (ui.editor.advanced) {
                    item {
                        Section("Video & format", "Format, encoder, quality, frame rate", true) {
                            Choice("Output format", s.container, Container.entries, { it.name }, { onAction(UiAction.ChangeSettings(s.copy(container = it))) })
                            if (s.container != Container.M4A) {
                                Choice("Video encoder", s.video, VideoEncoder.entries, { it.label }, { onAction(UiAction.ChangeSettings(s.copy(video = it))) },
                                    enabled = { !it.hardware && (!ui.capabilities.available || it.ffmpeg in ui.capabilities.encoders) })
                                Text("Device encoders need capability and real-device qualification; they are not enabled yet.", style = MaterialTheme.typography.bodySmall)
                                Choice("Rate control", s.rateControl, RateControl.entries, { if (it == RateControl.QUALITY) "Constant quality" else "Average bitrate" },
                                    { onAction(UiAction.ChangeSettings(s.copy(rateControl = it))) })
                                if (s.rateControl == RateControl.QUALITY) {
                                    val upper = if (s.video in setOf(VideoEncoder.VP9, VideoEncoder.AV1)) 63f else 51f
                                    Text("Quality (CRF): ${s.crf} · lower means more detail", style = MaterialTheme.typography.labelLarge)
                                    Slider(value = s.crf.toFloat().coerceIn(0f, upper), onValueChange = { onAction(UiAction.ChangeSettings(s.copy(crf = it.roundToInt()))) }, valueRange = 0f..upper, steps = upper.toInt() - 1)
                                    Text("File size depends on the content. This is not a guaranteed size target.", style = MaterialTheme.typography.bodySmall)
                                } else Choice("Video bitrate", s.videoKbps, listOf(500, 1000, 2000, 4000, 8000, 12000, 20000, 40000), { "$it kb/s" }, { onAction(UiAction.ChangeSettings(s.copy(videoKbps = it))) })
                                Choice("Frame rate", s.fps, listOf(0, 24, 25, 30, 50, 60, 120), { if (it == 0) "Same as source" else "$it fps · constant" }, { onAction(UiAction.ChangeSettings(s.copy(fps = it))) })
                            }
                        }
                    }
                    if (s.container != Container.M4A) item {
                        Section("Picture & filters", "Size, deinterlacing, noise reduction") {
                            Choice("Maximum height", s.maxHeight, listOf(0, 480, 720, 1080, 1440, 2160, 4320), { if (it == 0) "Same as source" else "$it pixels" }, { onAction(UiAction.ChangeSettings(s.copy(maxHeight = it))) })
                            Text("Aspect ratio is retained; no upscaling. Output is aligned to even pixels.", style = MaterialTheme.typography.bodySmall)
                            Toggle("Deinterlace", s.deinterlace) { onAction(UiAction.ChangeSettings(s.copy(deinterlace = it))) }
                            Toggle("Reduce noise", s.denoise) { onAction(UiAction.ChangeSettings(s.copy(denoise = it))) }
                            Text("Crop, rotation, sharpening and HDR tone mapping are planned, not applied by this skeleton.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Section("Audio", "Select a track, codec and sound quality") {
                            Choice("Audio encoder", s.audio, AudioEncoder.entries, { if (it == AudioEncoder.NONE) "No audio" else it.name }, { onAction(UiAction.ChangeSettings(s.copy(audio = it))) })
                            val tracks = ui.selected?.source?.audioTracks ?: 0
                            if (tracks > 0 && s.audio != AudioEncoder.NONE) Choice("Source audio track", s.audioTrack, (0 until tracks).toList(), { "Track ${it + 1}" }, { onAction(UiAction.ChangeSettings(s.copy(audioTrack = it))) })
                            if (s.audio !in setOf(AudioEncoder.NONE, AudioEncoder.FLAC)) Choice("Audio bitrate", s.audioKbps, listOf(64, 96, 128, 160, 192, 256, 320), { "$it kb/s" }, { onAction(UiAction.ChangeSettings(s.copy(audioKbps = it))) })
                            if (s.audio != AudioEncoder.NONE) Toggle("Mix down to stereo", s.stereo) { onAction(UiAction.ChangeSettings(s.copy(stereo = it))) }
                            Text("This first slice exports one selected audio track. Multi-track layouts, gain and delay will extend this section.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    item {
                        Section("Trim this file", ui.selected?.source?.name ?: "Choose media first") {
                            ui.selected?.let { edit ->
                                Text("${time(edit.trim.startMs)} → ${time(edit.trim.endMs ?: edit.source.durationMs)}")
                                RangeSlider(value = edit.trim.startMs.toFloat()..(edit.trim.endMs ?: edit.source.durationMs).toFloat(),
                                    onValueChange = { range -> onAction(UiAction.ChangeTrim(edit.source.uri, Trim(range.start.roundToLong(), range.endInclusive.roundToLong()))) },
                                    valueRange = 0f..edit.source.durationMs.toFloat())
                                Text("Only this selected file is trimmed. Other files keep their own ranges.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onAction(UiAction.ChangeTrim(edit.source.uri, Trim())) }) { Text("Use entire file") }
                            }
                        }
                    }
                    item {
                        Section("Subtitles, chapters & metadata", "Reserved extension points; explicit output scope") {
                            Toggle("Keep source metadata", s.keepMetadata) { onAction(UiAction.ChangeSettings(s.copy(keepMetadata = it))) }
                            Text("Subtitles, attachments and chapter markers are not included in this first encoding slice. Their editors, preview encoding and strict file-size targets are next-stage features.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Card {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("Output plan", style = MaterialTheme.typography.titleSmall)
                            Text("${s.container.name} · ${if (s.container == Container.M4A) "Sound only" else "First video track"} · ${if (s.audio == AudioEncoder.NONE) "No sound" else "Audio track ${s.audioTrack + 1}, when present"}", style = MaterialTheme.typography.bodyMedium)
                            Text("Saved privately first. Open, share or save a verified result to a folder afterward. Subtitles and chapters are not included yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            (ui.problems + runtimeProblems).distinct().take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                item {
                    OutlinedCard {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (ui.capabilities.available) "FFmpeg ready" else "Encoder not bundled", style = MaterialTheme.typography.titleSmall)
                            Text(if (ui.capabilities.available) ui.capabilities.build else ui.capabilities.reason, style = MaterialTheme.typography.bodySmall)
                            if (!ui.capabilities.available) Text("You can prepare jobs. Conversion stays disabled until the native build is supplied.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Queue · ${jobs.size}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (running) TextButton(onClick = { onAction(UiAction.StopQueue) }) { Text("Stop queue") }
                        else TextButton(onClick = { onAction(UiAction.StartQueue) }, enabled = !ui.busy && ui.capabilities.available && jobs.any { it.state == JobState.QUEUED }) { Text("Start queue") }
                    }
                    if (jobs.isEmpty()) Text("Add files when you are ready. Each job keeps its own settings.", style = MaterialTheme.typography.bodySmall)
                }
                items(jobs, key = { it.spec.id }) { entry -> QueueCard(entry, progress, onAction) }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable private fun Section(title: String, subtitle: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (expanded) content()
        }
    }
}
@Composable private fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(value, onCheckedChange = null)
    }
}
@Composable private fun <T> Choice(label: String, value: T, options: List<T>, title: (T) -> String, change: (T) -> Unit, enabled: (T) -> Boolean = { true }) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(title(value), modifier = Modifier.weight(1f), maxLines = 2)
                Text("Change", style = MaterialTheme.typography.labelSmall)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option -> DropdownMenuItem(text = { Text(title(option) + if (enabled(option)) "" else " · unavailable") },
                    enabled = enabled(option), onClick = { change(option); expanded = false }) }
            }
        }
    }
}
@Composable private fun QueueCard(entry: QueueEntry, live: LiveProgress?, action: (UiAction) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.spec.source.name, style = MaterialTheme.typography.titleSmall)
            Text(when (entry.state) {
                JobState.QUEUED -> "Waiting"; JobState.PREPARING -> "Preparing"; JobState.RUNNING -> "Converting"
                JobState.VERIFYING -> "Checking output"; JobState.COMPLETED -> "Ready"; JobState.FAILED -> "Failed"
                JobState.CANCELLED -> "Cancelled"; JobState.INTERRUPTED -> "Interrupted"
            }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            Text("${entry.spec.settings.container.name} · ${time(Planner.duration(entry.spec.source, entry.spec.trim))}", style = MaterialTheme.typography.bodySmall)
            if (live?.id == entry.spec.id) {
                val fraction = live.progress.fraction(Planner.duration(entry.spec.source, entry.spec.trim))
                if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            if (entry.message.isNotEmpty()) Text(entry.message.take(600), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (entry.state) {
                    JobState.COMPLETED -> {
                        TextButton(onClick = { action(UiAction.OpenOutput(entry.spec.id)) }) { Text("Open") }
                        TextButton(onClick = { action(UiAction.Export(entry.spec.id)) }) { Text("Save as…") }
                        TextButton(onClick = { action(UiAction.Share(entry.spec.id)) }) { Text("Share") }
                    }
                    JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED -> TextButton(onClick = { action(UiAction.Retry(entry.spec.id)) }) { Text("Retry as new job") }
                    JobState.QUEUED -> TextButton(onClick = { action(UiAction.RemoveJob(entry.spec.id)) }) { Text("Remove") }
                    else -> Unit
                }
            }
        }
    }
}
private fun time(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
@Preview(widthDp = 390, heightDp = 844, showBackground = true)
@Composable private fun SimplePreview() { FormaTheme { FormaScreen(TranscodeUiState(ready = true), emptyList(), null, {}) } }
