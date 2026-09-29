@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.forma.app.ui.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.forma.app.*
import dev.forma.app.ui.*
import dev.forma.app.ui.FormaButton as Button
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.app.ui.FormaOutlinedButton as OutlinedButton
import dev.forma.core.*
import dev.forma.core.audio.*
import java.util.UUID
import kotlin.math.*

@Composable internal fun AudioEditorPanel(ui:TranscodeUiState,action:(UiAction)->Unit) {
    val source=ui.selected ?: return
    if(source.source.audioTracks==0)return
    val state=ui.audioEditor;val settings=ui.editor.settings;val edit=settings.audioEdit
    fun change(value:AudioEdit,commit:Boolean=true)=action(UiAction.ChangeAudioEdit(value,commit))
    fun update(node:AudioEffectNode)=change(edit.copy(nodes=edit.nodes.map { if(it.id==node.id)node else it }))
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick={action(UiAction.ToggleAudioEditor)},modifier=Modifier.fillMaxWidth().testTag("edit-audio")) { Text(if(state.open)"Hide audio editor" else "Edit audio") }
            if(state.open) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Audio",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick={action(UiAction.UndoAudio)},enabled=state.canUndo,modifier=Modifier.testTag("undo-audio")) { Text("Undo") }
                    TextButton(onClick={action(UiAction.RedoAudio)},enabled=state.canRedo) { Text("Redo") }
                }
                Text("${source.source.audioStreams.getOrNull(settings.audioTrack)?.sampleRateHz?.let { "${it/1000.0} kHz · " }.orEmpty()}Keep ${mediaTime(source.trim.startMs)} – ${mediaTime(source.trim.endMs ?: source.source.durationMs)}",style=MaterialTheme.typography.bodySmall)
                val preview=state.preview
                preview.result?.let { result ->
                    Waveform(result.waveform.peaks)
                    Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text("Peak ${db(result.waveform.peakDb)}",style=MaterialTheme.typography.labelMedium)
                        Text("RMS ${db(result.waveform.rmsDb)}",style=MaterialTheme.typography.labelMedium)
                    }
                }
                Text(preview.status,style=MaterialTheme.typography.labelMedium,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                if(preview.status=="Updating") LinearProgressIndicator(Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={action(UiAction.RenderAudioPreview)},enabled=ui.capabilities.available && !ui.busy && preview.status!="Updating",modifier=Modifier.testTag("render-audio-preview")) { Text("Render preview") }
                    if(preview.status=="Updating") OutlinedButton(onClick={action(UiAction.CancelAudioPreview)}) { Text("Cancel preview") }
                }
                if(!ui.capabilities.available) Text(ui.capabilities.reason,style=MaterialTheme.typography.bodySmall)
                if(preview.error!=null) { var details by remember { mutableStateOf(false) };TextButton(onClick={details=!details}) { Text(if(details)"Hide error" else "Preview error") };if(details)Text(preview.error.take(1000),style=MaterialTheme.typography.bodySmall) }
                if(preview.status=="Rendered preview") preview.result?.let { AudioPreviewTransport(it) }
                RangeSlider(value=source.trim.startMs.toFloat()..(source.trim.endMs ?: source.source.durationMs).toFloat(),
                    onValueChange={ if(it.endInclusive-it.start>=50)action(UiAction.ChangeTrim(source.source.uri,Trim(it.start.roundToLong(),it.endInclusive.roundToLong()))) },
                    valueRange=0f..source.source.durationMs.toFloat(),modifier=Modifier.semantics { contentDescription="Keep audio range" },
                    startThumb={Text("[",Modifier.sizeIn(minWidth=52.dp,minHeight=52.dp).wrapContentSize(),style=MaterialTheme.typography.headlineLarge)},
                    endThumb={Text("]",Modifier.sizeIn(minWidth=52.dp,minHeight=52.dp).wrapContentSize(),style=MaterialTheme.typography.headlineLarge)})
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    NumberInput("Start (s)",source.trim.startMs/1000.0,0.0..((source.trim.endMs ?: source.source.durationMs)-50)/1000.0,Modifier.weight(1f)) { action(UiAction.ChangeTrim(source.source.uri,source.trim.copy(startMs=(it*1000).roundToLong()))) }
                    NumberInput("End (s)",(source.trim.endMs ?: source.source.durationMs)/1000.0,(source.trim.startMs+50)/1000.0..source.source.durationMs/1000.0,Modifier.weight(1f)) { action(UiAction.ChangeTrim(source.source.uri,source.trim.copy(endMs=(it*1000).roundToLong()))) }
                }
                BoxWithConstraints {
                    val quick:@Composable ()->Unit={ QuickAudio(edit,source.trim,source.source,::change) }
                    val chain:@Composable ()->Unit={ AudioChain(edit,::change,::update) }
                    if(maxWidth>=840.dp) Row(horizontalArrangement=Arrangement.spacedBy(24.dp)) { Column(Modifier.weight(1f)) { quick() };Column(Modifier.weight(1f)) { chain() } }
                    else Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { quick();chain() }
                }
                AudioOutput(ui,action,::change)
                TextButton(onClick={change(AudioEdit())},enabled=edit!=AudioEdit()) { Text("Reset audio edits") }
            }
        }
    }
}

@Composable private fun QuickAudio(edit:AudioEdit,trim:Trim,source:Source,change:(AudioEdit,Boolean)->Unit) {
    fun put(type:String,parameters:AudioParameters,commit:Boolean=true) {
        val node=edit.nodes.firstOrNull { it.type==type && it.parameters !is UnsupportedParameters }
        change(edit.copy(nodes=if(node==null)edit.nodes+AudioEffectNode(UUID.randomUUID().toString(),type,parameters=parameters)
            else edit.nodes.map { if(it.id==node.id)it.copy(parameters=parameters) else it }),commit)
    }
    val gain=edit.nodes.firstOrNull { it.type=="gain" }?.parameters as? GainParameters ?: GainParameters()
    Text("Gain · ${AudioGraphPlanner.number(gain.gainDb)} dB",style=MaterialTheme.typography.titleSmall)
    Slider(value=gain.gainDb.toFloat().coerceIn(-60f,24f),valueRange=-60f..24f,
        onValueChange={put("gain",gain.copy(gainDb=it.toDouble()),false)},onValueChangeFinished={put("gain",gain,true)},
        modifier=Modifier.heightIn(min=52.dp).semantics { contentDescription="Audio gain in decibels" })
    NumberInput("Gain (dB)",gain.gainDb,-60.0..24.0) { put("gain",gain.copy(gainDb=it)) }
    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.heightIn(min=52.dp)) {
        Text("Mute",Modifier.weight(1f));Switch(gain.muted,{put("gain",gain.copy(muted=it))},modifier=Modifier.formaTouchTarget())
    }
    val fade=edit.nodes.firstOrNull { it.type=="fades" }?.parameters as? FadeParameters ?: FadeParameters()
    val max=Planner.duration(source,trim)/1000.0
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        NumberInput("Fade in (s)",fade.fadeInUs/1000000.0,0.0..max,Modifier.weight(1f)) { put("fades",fade.copy(fadeInUs=(it*1000000).roundToLong())) }
        NumberInput("Fade out (s)",fade.fadeOutUs/1000000.0,0.0..max,Modifier.weight(1f)) { put("fades",fade.copy(fadeOutUs=(it*1000000).roundToLong())) }
    }
}

@Composable private fun AudioChain(edit:AudioEdit,change:(AudioEdit,Boolean)->Unit,update:(AudioEffectNode)->Unit) {
    Text("Effects · ${edit.nodes.count { it.enabled }} active",style=MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf("eq" to "Add EQ","compressor" to "Add compressor","limiter" to "Add limiter").forEach { (type,label) ->
            OutlinedButton(onClick={ val parameters=when(type) { "eq"->EqParameters(listOf(EqBand(UUID.randomUUID().toString())));"compressor"->CompressorParameters();else->LimiterParameters() }
                change(edit.copy(nodes=edit.nodes+AudioEffectNode(UUID.randomUUID().toString(),type,parameters=parameters)),true)
            },enabled=edit.nodes.none { it.type==type },modifier=Modifier.testTag("add-$type")) { Text(label) }
        }
    }
    edit.nodes.forEachIndexed { index,node ->
        var expanded by remember(node.id) { mutableStateOf(node.parameters !is GainParameters && node.parameters !is FadeParameters) }
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={expanded=!expanded},modifier=Modifier.weight(1f)) { Text(AudioEffectRegistry.descriptor(node.type)?.name ?: "Unsupported ${node.type}") }
                Switch(node.enabled,{update(node.copy(enabled=it))},modifier=Modifier.formaTouchTarget().testTag("bypass-${node.type}").semantics { contentDescription="Enable ${node.type}" })
            }
            if(expanded) when(val p=node.parameters) {
                is EqParameters -> {
                    EqCurve(p)
                    p.bands.forEach { band ->
                        fun bandChange(value:EqBand)=update(node.copy(parameters=p.copy(bands=p.bands.map { if(it.id==band.id)value else it })))
                        Choice("EQ shape",band.type,EqType.entries,{it.name.lowercase().replace('_',' ')}) { bandChange(band.copy(type=it)) }
                        NumberInput("Frequency (Hz)",band.frequencyHz,20.0..20000.0,Modifier.testTag("eq-frequency-${band.id}")) { bandChange(band.copy(frequencyHz=it)) }
                        NumberInput("EQ gain (dB)",band.gainDb,-24.0..24.0) { bandChange(band.copy(gainDb=it)) }
                        NumberInput("Width (Q)",band.q,0.1..18.0) { bandChange(band.copy(q=it)) }
                        TextButton(onClick={update(node.copy(parameters=p.copy(bands=p.bands.filterNot { it.id==band.id })))}) { Text("Remove band") }
                    }
                    OutlinedButton(onClick={update(node.copy(parameters=p.copy(bands=p.bands+EqBand(UUID.randomUUID().toString()))))},enabled=p.bands.size<8) { Text("Add band") }
                }
                is CompressorParameters -> {
                    NumberInput("Threshold (dB)",p.thresholdDb,-60.0..0.0) { update(node.copy(parameters=p.copy(thresholdDb=it))) }
                    NumberInput("Ratio",p.ratio,1.0..20.0) { update(node.copy(parameters=p.copy(ratio=it))) }
                    NumberInput("Attack (ms)",p.attackMs,0.01..2000.0) { update(node.copy(parameters=p.copy(attackMs=it))) }
                    NumberInput("Release (ms)",p.releaseMs,0.01..9000.0) { update(node.copy(parameters=p.copy(releaseMs=it))) }
                    NumberInput("Makeup gain (dB)",p.makeupDb,0.0..24.0) { update(node.copy(parameters=p.copy(makeupDb=it))) }
                }
                is LimiterParameters -> NumberInput("Sample-peak ceiling (dB)",p.ceilingDb,-24.0..0.0) { update(node.copy(parameters=p.copy(ceilingDb=it))) }
                is UnsupportedParameters -> Text("Bypass or remove to export",color=MaterialTheme.colorScheme.error)
                else -> Text("Edit above",style=MaterialTheme.typography.bodySmall)
            }
            FlowRow {
                TextButton(onClick={val nodes=edit.nodes.toMutableList();nodes.removeAt(index);nodes.add(index-1,node);change(edit.copy(nodes=nodes),true)},enabled=index>0) { Text("Move earlier") }
                TextButton(onClick={val nodes=edit.nodes.toMutableList();nodes.removeAt(index);nodes.add(index+1,node);change(edit.copy(nodes=nodes),true)},enabled=index<edit.nodes.lastIndex) { Text("Move later") }
                TextButton(onClick={change(edit.copy(nodes=edit.nodes.filterNot { it.id==node.id }),true)}) { Text("Remove effect") }
            }
        } }
    }
}

@Composable private fun AudioOutput(ui:TranscodeUiState,action:(UiAction)->Unit,change:(AudioEdit,Boolean)->Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick={open=!open},modifier=Modifier.fillMaxWidth()) { Text(if(open)"Hide audio output settings" else "Audio output settings") }
    if(open) {
        val s=ui.editor.settings;val edit=s.audioEdit;val output=edit.output
        if(s.container.audioOnly) Choice("Audio file format",s.container,listOf(Container.M4A,Container.WAV,Container.FLAC),{it.name},enabled={c->!ui.capabilities.available || c.muxer in ui.capabilities.muxers}) {
            val encoder=when(it) { Container.WAV->AudioEncoder.PCM_S16LE;Container.FLAC->AudioEncoder.FLAC;else->AudioEncoder.AAC }
            action(UiAction.ChangeSettings(s.copy(container=it,audio=encoder)))
        }
        if(s.container==Container.WAV) Choice("PCM format",s.audio,listOf(AudioEncoder.PCM_S16LE,AudioEncoder.PCM_F32LE),{if(it==AudioEncoder.PCM_F32LE)"32-bit float" else "16-bit PCM"}) { action(UiAction.ChangeSettings(s.copy(audio=it))) }
        Choice("Channels",output.channels ?: ChannelMode.SOURCE,ChannelMode.entries,{it.name.lowercase().replaceFirstChar(Char::uppercase)}) { change(edit.copy(output=output.copy(channels=it)),true) }
        Choice("Sample rate",output.sampleRateHz ?: 0,listOf(0,44100,48000,96000),{if(it==0)"Source rate" else "${it/1000.0} kHz"}) { change(edit.copy(output=output.copy(sampleRateHz=it.takeIf { it>0 })),true) }
        val n=output.normalization
        Choice("Normalization",n.mode,NormalizationMode.entries,{it.name.lowercase().replaceFirstChar(Char::uppercase)}) { change(edit.copy(output=output.copy(normalization=n.copy(mode=it))),true) }
        if(n.mode==NormalizationMode.PEAK) NumberInput("Peak target (dB)",n.peakDb,-24.0..0.0) { change(edit.copy(output=output.copy(normalization=n.copy(peakDb=it))),true) }
        if(n.mode==NormalizationMode.LOUDNESS) {
            NumberInput("Loudness (LUFS)",n.integratedLufs,-36.0..-5.0) { change(edit.copy(output=output.copy(normalization=n.copy(integratedLufs=it))),true) }
            NumberInput("True-peak ceiling (dB)",n.truePeakDb,-9.0..0.0) { change(edit.copy(output=output.copy(normalization=n.copy(truePeakDb=it))),true) }
            Row(verticalAlignment=Alignment.CenterVertically) { Text("Preserve dynamics",Modifier.weight(1f));Switch(n.preserveDynamics,{change(edit.copy(output=output.copy(normalization=n.copy(preserveDynamics=it))),true)},modifier=Modifier.formaTouchTarget()) }
        }
        Choice("File size limit",output.maxBytes ?: 0,listOf(0L,10000000,25000000,50000000,100000000),{if(it==0L)"No limit" else "${it/1000000} MB"}) { change(edit.copy(output=output.copy(maxBytes=it.takeIf { it>0 })),true) }
    }
}

@Composable internal fun NumberInput(label:String,value:Double,range:ClosedFloatingPointRange<Double>,modifier:Modifier=Modifier,onCommit:(Double)->Unit) {
    var text by remember(value) { mutableStateOf(AudioGraphPlanner.number(value)) }
    val number=text.replace(',','.').toDoubleOrNull()
    val valid=number!=null && number.isFinite() && number in range
    OutlinedTextField(value=text,onValueChange={text=it},label={Text(label)},singleLine=true,isError=!valid,
        modifier=modifier.fillMaxWidth().heightIn(min=52.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={if(valid)onCommit(number!!)}),trailingIcon={TextButton(onClick={if(valid)onCommit(number!!)},enabled=valid) { Text("Set") }})
}
@Composable private fun Waveform(peaks:List<Float>) {
    val color=MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(96.dp).testTag("audio-waveform").semantics { contentDescription="Rendered preview waveform" }) {
        val center=size.height/2;peaks.forEachIndexed { i,value -> val x=(i+.5f)*size.width/peaks.size;val h=value.coerceIn(0f,1f)*center;drawLine(color,Offset(x,center-h),Offset(x,center+h),maxOf(1f,size.width/peaks.size)) }
    }
}
@Composable private fun EqCurve(parameters:EqParameters) {
    val color=MaterialTheme.colorScheme.primary;val grid=MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(96.dp).semantics { contentDescription="EQ band overview; numeric controls below" }) {
        drawLine(grid,Offset(0f,size.height/2),Offset(size.width,size.height/2))
        parameters.bands.filter { it.enabled }.forEach { val x=(ln(it.frequencyHz/20)/ln(1000.0)).toFloat().coerceIn(0f,1f)*size.width
            val y=size.height/2-(it.gainDb/24).toFloat()*size.height/2;drawCircle(color,5.dp.toPx(),Offset(x,y)) }
    }
}
private fun db(value:Double?)=value?.let { "${"%.1f".format(java.util.Locale.ROOT,it)} dB" } ?: "—"
