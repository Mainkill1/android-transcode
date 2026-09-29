package dev.forma.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.forma.app.UiAction
import dev.forma.core.*

/** These controls edit the selected source, never a mutable global export job. */
@Composable internal fun EditControls(edit: SourceEdit, settings: Settings, action: (UiAction) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var cropOpen by remember(edit.source.uri) { mutableStateOf(false) }
    val e = edit.effects
    fun change(value: ClipEffects) = action(UiAction.ChangeEffects(edit.source.uri, value))
    OutlinedCard(Modifier.fillMaxWidth()) {
        FormaTextButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Text("Edit selected clip", Modifier.weight(1f))
            Text(if (open) "Hide" else "Show")
        }
        if (open) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(edit.source.name, style = MaterialTheme.typography.titleSmall)
            Text("Edits apply to this file only and survive preset changes. Open original does not preview these effects; inspect the exported result.", style = MaterialTheme.typography.bodySmall)
            val duration = runCatching { EditPipeline.duration(edit.source, edit.trim, e) }.getOrNull()
            duration?.let { Text("Edited duration: ${mediaTime(it)}", style = MaterialTheme.typography.labelLarge) }
            Choice("Speed (pitch preserved)", e.speedPercent, listOf(25, 50, 75, 100, 125, 150, 200, 400), { "$it%" }) { change(e.copy(speedPercent = it)) }
            if (settings.container != Container.M4A) {
                Choice("Added rotation", e.rotation, QuarterTurn.entries, { when (it) {
                    QuarterTurn.NONE -> "None"; QuarterTurn.CLOCKWISE -> "90° clockwise"
                    QuarterTurn.HALF -> "180°"; QuarterTurn.COUNTERCLOCKWISE -> "90° counterclockwise"
                } }) { change(e.copy(rotation = it)) }
                EditToggle("Mirror horizontally", e.flipHorizontal) { change(e.copy(flipHorizontal = it)) }
                EditToggle("Mirror vertically", e.flipVertical) { change(e.copy(flipVertical = it)) }
                FormaOutlinedButton(onClick = { cropOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(e.crop?.let { "Crop: ${it.width} × ${it.height} at ${it.x}, ${it.y}" } ?: "Crop picture")
                }
                Choice("Brightness", e.brightnessPercent, listOf(-50, -25, -10, 0, 10, 25, 50), { "$it%" }) { change(e.copy(brightnessPercent = it)) }
                Choice("Contrast", e.contrastPercent, listOf(50, 75, 100, 125, 150, 200), { "$it%" }) { change(e.copy(contrastPercent = it)) }
                Choice("Saturation", e.saturationPercent, listOf(0, 50, 75, 100, 125, 150, 200), { "$it%" }) { change(e.copy(saturationPercent = it)) }
                Choice("Gamma", e.gammaPercent, listOf(50, 75, 100, 125, 150, 200), { "$it%" }) { change(e.copy(gammaPercent = it)) }
                Choice("Blur", e.blurSigma, listOf(0, 1, 2, 4, 8, 16), { if (it == 0) "Off" else "Sigma $it" }) { change(e.copy(blurSigma = it)) }
                EditToggle("Sharpen", e.sharpen) { change(e.copy(sharpen = it)) }
                FadeChoice("Video fade in", e.fadeInMs) { change(e.copy(fadeInMs = it)) }
                FadeChoice("Video fade out", e.fadeOutMs) { change(e.copy(fadeOutMs = it)) }
            }
            if (edit.source.audioTracks > 0 && settings.audio != AudioEncoder.NONE) {
                Choice("Volume", e.volumePercent, listOf(0, 25, 50, 75, 100, 125, 150, 200), { if (it == 0) "Mute" else "$it%" }) { change(e.copy(volumePercent = it)) }
                EditToggle("Normalize loudness", e.normalizeAudio) { change(e.copy(normalizeAudio = it)) }
                if (e.normalizeAudio) Text("Single-pass normalization before volume and fades, not broadcast loudness certification.", style = MaterialTheme.typography.bodySmall)
                FadeChoice("Audio fade in", e.audioFadeInMs) { change(e.copy(audioFadeInMs = it)) }
                FadeChoice("Audio fade out", e.audioFadeOutMs) { change(e.copy(audioFadeOutMs = it)) }
            }
            Text("Fades use the edited duration. Software encoding is required for edited video in this version.", style = MaterialTheme.typography.bodySmall)
            FormaTextButton(onClick = { change(ClipEffects()) }) { Text("Reset this clip's effects") }
        }
    }
    if (cropOpen) CropDialog(edit, onDismiss = { cropOpen = false }) { change(e.copy(crop = it)); cropOpen = false }
}

@Composable private fun FadeChoice(label: String, value: Long, change: (Long) -> Unit) =
    Choice(label, value, listOf(0L, 250L, 500L, 1000L, 2000L), { if (it == 0L) "Off" else "$it ms" }, change = change)

@Composable private fun EditToggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value, role = Role.Switch, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(checked = value, onCheckedChange = null)
    }
}

@Composable private fun CropDialog(edit: SourceEdit, onDismiss: () -> Unit, apply: (CropRect?) -> Unit) {
    val c = edit.effects.crop
    var x by remember { mutableStateOf((c?.x ?: 0).toString()) }
    var y by remember { mutableStateOf((c?.y ?: 0).toString()) }
    var width by remember { mutableStateOf((c?.width ?: edit.source.width / 2 * 2).toString()) }
    var height by remember { mutableStateOf((c?.height ?: edit.source.height / 2 * 2).toString()) }
    val values = listOf(x, y, width, height).map { it.toIntOrNull() }
    val crop = if (values.all { it != null }) CropRect(values[0]!!, values[1]!!, values[2]!!, values[3]!!) else null
    val valid = crop != null && crop.x >= 0 && crop.y >= 0 && crop.width >= 4 && crop.height >= 4 &&
        listOf(crop.x, crop.y, crop.width, crop.height).all { it % 2 == 0 } &&
        crop.x.toLong() + crop.width <= edit.source.width && crop.y.toLong() + crop.height <= edit.source.height
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Crop in source pixels") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Even coordinates and dimensions inside ${edit.source.width} × ${edit.source.height}. Display-matrix sources need separate crop qualification.")
            CropNumber("X", x) { x = it }; CropNumber("Y", y) { y = it }
            CropNumber("Width", width) { width = it }; CropNumber("Height", height) { height = it }
            if (!valid) Text("Enter an in-bounds crop of at least 4 × 4.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { FormaTextButton(onClick = { apply(crop) }, enabled = valid) { Text("Apply") } },
        dismissButton = { Row {
            FormaTextButton(onClick = { apply(null) }) { Text("Remove crop") }
            FormaTextButton(onClick = onDismiss) { Text("Cancel") }
        } })
}
@Composable private fun CropNumber(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value, onValueChange = { if (it.length <= 10) change(it) }, label = { Text(label) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}
