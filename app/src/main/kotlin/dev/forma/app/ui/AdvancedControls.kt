@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import dev.forma.app.ui.FormaButton as Button
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.app.ui.FormaOutlinedButton as OutlinedButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.forma.app.*
import dev.forma.core.*
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Contextual controls retain existing native features, without listing future features as settings. */
@Composable internal fun AdvancedControls(ui: TranscodeUiState, action: (UiAction) -> Unit) {
    val s = ui.editor.settings
    fun update(value: Settings, vararg explicitIds:String) = action(UiAction.ChangeSettings(value,explicitIds=explicitIds.toSet()))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Video & format", true) {
            Choice("Output format", s.container, Container.entries, { it.name }) { update(s.copy(container = it, audio = when(it) { Container.WAV -> AudioEncoder.PCM_S16LE;Container.FLAC -> AudioEncoder.FLAC;Container.M4A,Container.MP4 -> AudioEncoder.AAC;else -> s.audio }),"export.container","audio.codec") }
            if (!s.container.audioOnly) {
                Choice("Video encoder", s.video, VideoEncoder.entries, { it.label },
                    enabled = { if (it.deviceRequested) ui.capabilities.available && it.isCompiled(ui.capabilities.encoders) else !ui.capabilities.available || it.isCompiled(ui.capabilities.encoders) }) {
                    update(if (it.deviceRequested) s.copy(video = it, rateControl = RateControl.BITRATE, fps = if (s.fps == 0) 30 else s.fps) else s.copy(video = it),"video.codec","engine.encode_backend")
                }
                if (s.video.deviceRequested) Text(if (s.video.automatic)
                    "Tries device encoders, then software for codec failures. Constant quality or source frame rate uses software. Reports show the actual route."
                else "Tries device configurations without software fallback. Older Android versions may not identify whether the component is hardware.", style = MaterialTheme.typography.bodySmall)
                if (ui.targetBytes != null) Text("Remove the size limit to use these quality settings.", style=MaterialTheme.typography.bodySmall)
                Choice("Rate control", s.rateControl, if (s.video.hardware) listOf(RateControl.BITRATE) else RateControl.entries,
                    { if (it == RateControl.QUALITY) "Constant quality" else "Average bitrate" }) { update(s.copy(rateControl = it),"video.rate_control") }
                if (s.rateControl == RateControl.QUALITY) {
                    val upper = if (s.video in setOf(VideoEncoder.VP9, VideoEncoder.AV1)) 63f else 51f
                    Text("Quality: ${s.crf} · lower keeps more detail")
                    Slider(value = s.crf.toFloat().coerceIn(0f, upper), onValueChange = { update(s.copy(crf = it.roundToInt()),"video.quality") },
                        valueRange = 0f..upper, steps = upper.toInt() - 1, modifier = Modifier.semantics { contentDescription = "Constant quality" })
                } else Choice("Video bitrate", s.videoKbps, listOf(500, 1000, 2000, 4000, 8000, 12000, 20000, 40000), { "$it kb/s" }) { update(s.copy(videoKbps = it),"video.bitrate_kbps") }
                Choice("Frame rate", s.fps, if (s.video.hardware) listOf(24, 25, 30, 50, 60, 120) else listOf(0, 24, 25, 30, 50, 60, 120),
                    { if (it == 0) "Same as source" else "$it fps" }) { update(s.copy(fps = it),"video.frame_rate") }
            }
        }
        if (!s.container.audioOnly) Section("Picture & filters") {
            Choice("Maximum height", s.maxHeight, listOf(0, 480, 720, 1080, 1440, 2160, 4320), { if (it == 0) "Same as source" else "$it pixels" }) { update(s.copy(maxHeight = it),"video.max_height") }
            Text("Keeps proportions · No upscaling", style = MaterialTheme.typography.bodySmall)
            Toggle("Deinterlace", s.deinterlace) { update(s.copy(deinterlace = it),"video.deinterlace") }
            Toggle("Reduce noise", s.denoise) { update(s.copy(denoise = it)) }
        }
        Section("Audio") {
            Choice("Audio encoder", s.audio, AudioEncoder.entries, { if (it == AudioEncoder.NONE) "Remove sound" else it.name }) { update(s.copy(audio = it),"audio.codec") }
            val tracks = ui.selected?.source?.audioTracks ?: 0
            if (tracks > 0 && s.audio != AudioEncoder.NONE) Choice("Source track", s.audioTrack, (0 until tracks).toList(), { "Track ${it + 1}" }) { update(s.copy(audioTrack = it)) }
            if (s.audio.usesBitrate) Choice("Audio bitrate", s.audioKbps, listOf(64, 96, 128, 160, 192, 256, 320), { "$it kb/s" }) { update(s.copy(audioKbps = it),"audio.bitrate_kbps") }
            if (s.audio != AudioEncoder.NONE) Toggle("Mix down to stereo", dev.forma.app.audio.AudioEditorSettings.stereoEnabled(s)) {
                update(dev.forma.app.audio.AudioEditorSettings.withStereo(s,it),"audio.channels")
            }
        }
        ui.selected?.let { edit -> Section("Trim selected file") {
            Text(edit.source.name, style = MaterialTheme.typography.labelLarge)
            Text("Keep ${mediaTime(edit.trim.startMs)} – ${mediaTime(edit.trim.endMs ?: edit.source.durationMs)}")
            RangeSlider(value = edit.trim.startMs.toFloat()..(edit.trim.endMs ?: edit.source.durationMs).toFloat(),
                onValueChange = { range -> if (range.endInclusive - range.start >= 50f) action(UiAction.ChangeTrim(edit.source.uri,
                    Trim(range.start.roundToLong(), range.endInclusive.roundToLong()))) },
                valueRange = 0f..edit.source.durationMs.toFloat(),
                startThumb = { Text("[", Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).wrapContentSize(), fontSize = 32.sp) },
                endThumb = { Text("]", Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).wrapContentSize(), fontSize = 32.sp) })
            Text("Keep the selected range", style = MaterialTheme.typography.bodySmall)
            FlowRow {
                TextButton(onClick = { action(UiAction.ChangeTrim(edit.source.uri, Trim((edit.trim.startMs - 100).coerceAtLeast(0), edit.trim.endMs))) }) { Text("Start −0.1s") }
                TextButton(onClick = { action(UiAction.ChangeTrim(edit.source.uri, Trim((edit.trim.startMs + 100).coerceAtMost((edit.trim.endMs ?: edit.source.durationMs) - 50).coerceAtLeast(0), edit.trim.endMs))) }) { Text("Start +0.1s") }
                TextButton(onClick = { action(UiAction.ChangeTrim(edit.source.uri, Trim())) }) { Text("Use entire file") }
            }
        } }
        Section("Output details") {
            Toggle("Keep source metadata", s.keepMetadata) { update(s.copy(keepMetadata = it)) }
            Text("One audio track · No subtitles", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A touch-friendly selector instead of a small cascading menu. */
@Composable internal fun <T> Choice(label: String, value: T, options: List<T>, title: (T) -> String,
    enabled: (T) -> Boolean = { true }, change: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(title(value), style = MaterialTheme.typography.bodyLarge)
        }
        Text("Change", style = MaterialTheme.typography.labelSmall)
    }
    if (open) ModalBottomSheet(onDismissRequest = { open = false }) {
        Text(label, Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).navigationBarsPadding()) {
            items(options) { option ->
                val available = enabled(option)
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(enabled = available, role = Role.RadioButton) { change(option); open = false }
                    .semantics { selected = option == value }
                    .padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = option == value, onClick = null, enabled = available)
                    Text(title(option) + if (available) "" else " · unavailable in this build", Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}
@Composable private fun Section(title: String, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(if (open) "Hide" else "Show")
        }
        if (open) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable private fun Toggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}
