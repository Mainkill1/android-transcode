@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.forma.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.forma.app.*
import dev.forma.app.video.RenderedPreviewController
import dev.forma.app.video.VideoRenderState
import dev.forma.app.work.*
import dev.forma.core.*
import kotlin.math.roundToLong

/** A compact native document inspector. Preview/export remain ordinary service-owned queue jobs. */
@Composable internal fun MovieControls(ui: TranscodeUiState, jobs: List<QueueEntry>, run: RunState, action: (UiAction) -> Unit,
    render:VideoRenderState=VideoRenderState.Idle) {
    val movie = ui.movie
    val clips = movie.sequence.timeline.clips
    val selected = clips.firstOrNull { it.id == ui.selectedMovieClipId } ?: clips.firstOrNull()
    val problems = remember(movie, ui.capabilities) {
        if (clips.isEmpty()) listOf("Add a selected source to begin.") else
            runCatching { JobPlans.validate(movie.toJob("draft"), if (ui.capabilities.available) ui.capabilities else null) }
                .getOrElse { listOf(it.message ?: "Invalid movie.") }
    }
    val ready = ui.ready && !ui.busy && problems.isEmpty()
    val native = ready && ui.capabilities.available && run.mode != RunMode.STOPPING
    var split by remember { mutableStateOf(false) }
    var exactTrim by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(movie.name, style = MaterialTheme.typography.titleLarge)
        Text("Arrange clips in order. Original files stay untouched; previews and exports use separate snapshots.")
        FormaOutlinedButton(onClick = { action(UiAction.MovieAppendSelected) }, enabled = ui.ready && ui.selected?.source?.imageInfo == null && ui.selected != null && clips.size < SequencePlanner.MAX_RENDER_CLIPS,
            modifier = Modifier.fillMaxWidth().testTag("movie-append")) { Text("Append selected source${ui.selected?.let { ": ${it.source.name}" } ?: ""}") }
        Text("To append another source, select it on Convert media, then return here.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormaOutlinedButton(onClick = { action(UiAction.MovieUndo) }, enabled = ui.movieCanUndo, modifier = Modifier.testTag("movie-undo")) { Text("Undo movie edit") }
            FormaOutlinedButton(onClick = { action(UiAction.MovieRedo) }, enabled = ui.movieCanRedo, modifier = Modifier.testTag("movie-redo")) { Text("Redo movie edit") }
        }
        clips.forEachIndexed { index, clip ->
            OutlinedCard(onClick = { action(UiAction.MovieSelect(clip.id)) }, modifier = Modifier.fillMaxWidth().testTag("movie-clip-$index")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${index + 1}. ${clip.source.name}${if (selected?.id == clip.id) " · selected" else ""}")
                    Text("${clip.trim.startMs}–${clip.trim.endMs ?: clip.source.durationMs} ms · ${runCatching { Planner.outputDuration(clip.source,clip.trim,clip.settings) }.getOrNull()?.let(::mediaTime) ?: "Duration unavailable"}", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FormaTextButton(onClick = { action(UiAction.MovieEdit(TimelineCommand.Move(clip.id,index-1))) }, enabled = index > 0,
                            modifier = Modifier.semantics { contentDescription = "Move clip ${index + 1} earlier" }) { Text("Earlier") }
                        FormaTextButton(onClick = { action(UiAction.MovieEdit(TimelineCommand.Move(clip.id,index+1))) }, enabled = index < clips.lastIndex,
                            modifier = Modifier.semantics { contentDescription = "Move clip ${index + 1} later" }) { Text("Later") }
                        FormaTextButton(onClick = { action(UiAction.MovieEdit(TimelineCommand.Duplicate(clip.id,java.util.UUID.randomUUID().toString()))) }, enabled = clips.size < SequencePlanner.MAX_RENDER_CLIPS) { Text("Duplicate") }
                        FormaTextButton(onClick = { action(UiAction.MovieEdit(TimelineCommand.Remove(clip.id))) }) { Text("Remove clip") }
                    }
                }
            }
        }
        if (selected != null) key(selected.id) {
            Text("Movie length ${TimelineTimecode.format(SequencePlanner.duration(movie.sequence))}",style=MaterialTheme.typography.labelMedium)
            if(selected.source.videoTracks>0) {
                var playhead by remember(selected.id) {mutableLongStateOf(selected.trim.startMs)}
                VideoTimeline(selected.source,selected.trim,playhead,onSeek={playhead=it},
                    onTrim={action(UiAction.MovieEdit(TimelineCommand.TrimClip(selected.id,it)))})
                FormaOutlinedButton(onClick={
                    SequenceWindowPlanner.movieTimeForClip(movie.sequence,selected.id,playhead)?.let {
                        action(UiAction.MoviePreviewAt(it))
                    }
                },enabled=native,modifier=Modifier.fillMaxWidth().testTag("movie-play-window")) {Text("Play 5 seconds here")}
                val playable=(render as? VideoRenderState.Ready)?.takeIf {
                    it.sourceKey==RenderedPreviewController.MOVIE_KEY && it.revision==ui.movieRevision && it.movie==movie
                }
                if(playable!=null) {
                    VideoPreviewPlayer(playable)
                    FormaTextButton(onClick={action(UiAction.CloseVideoPreview)}) {Text("Edit movie timeline")}
                } else {
                    val status=when(render) {
                        is VideoRenderState.Waiting -> if(render.sourceKey==RenderedPreviewController.MOVIE_KEY)"Waiting for conversion to finish" else null
                        is VideoRenderState.Rendering -> if(render.sourceKey==RenderedPreviewController.MOVIE_KEY)"Rendering short movie preview" else null
                        is VideoRenderState.Error -> if(render.sourceKey==RenderedPreviewController.MOVIE_KEY)render.message else null
                        else -> null
                    }
                    if(status!=null)Text(status,style=MaterialTheme.typography.labelSmall)
                }
            } else {
                var range by remember(selected.trim) { mutableStateOf(selected.trim.startMs.toFloat()..(selected.trim.endMs ?: selected.source.durationMs).toFloat()) }
                Text("Keep ${range.start.roundToLong()}–${range.endInclusive.roundToLong()} ms of ${selected.source.name}")
                RangeSlider(value=range, onValueChange={ if (it.endInclusive-it.start >= 50f) range=it },
                    onValueChangeFinished={ action(UiAction.MovieEdit(TimelineCommand.TrimClip(selected.id,Trim(range.start.roundToLong(),range.endInclusive.roundToLong())))) },
                    valueRange=0f..selected.source.durationMs.toFloat(), modifier=Modifier.testTag("movie-bracket-trim").semantics { contentDescription="Kept range of selected movie clip" },
                    startThumb={ Text("[",Modifier.sizeIn(minWidth=52.dp,minHeight=52.dp).wrapContentSize(),fontSize=32.sp) },
                    endThumb={ Text("]",Modifier.sizeIn(minWidth=52.dp,minHeight=52.dp).wrapContentSize(),fontSize=32.sp) })
            }
            FlowRow {
                if(selected.source.videoTracks==0)FormaTextButton(onClick={ exactTrim=true }) { Text("Exact trim times") }
                FormaTextButton(onClick={ split=true }, modifier=Modifier.testTag("movie-split")) { Text("Split selected clip") }
            }
            EditControls(SourceEdit(selected.source,selected.trim,selected.settings.effects),selected.settings) {
                if (it is UiAction.ChangeEffects) action(UiAction.MovieEdit(TimelineCommand.UpdateSettings(selected.id,selected.settings.copy(effects=it.effects))))
            }
            if(selected.source.audioTracks>0 && selected.settings.audio!=AudioEncoder.NONE)
                Choice("Movie source audio track",selected.settings.audioTrack,(0 until selected.source.audioTracks).toList(),{"Track ${it+1}"}) {
                    action(UiAction.MovieEdit(TimelineCommand.UpdateSettings(selected.id,selected.settings.copy(audioTrack=it))))
                }
        }
        Choice("Movie canvas",movie.sequence.canvas.width to movie.sequence.canvas.height,listOf(1280 to 720,720 to 1280,640 to 640,1920 to 1080,1080 to 1920),{"${it.first} × ${it.second}"}) {
            action(UiAction.MovieChange(movie.copy(sequence=movie.sequence.copy(canvas=movie.sequence.canvas.copy(width=it.first,height=it.second)))))
        }
        Choice("Movie frame rate",movie.sequence.canvas.fps,listOf(24,25,30,50,60),{"$it fps"}) {
            action(UiAction.MovieChange(movie.copy(sequence=movie.sequence.copy(canvas=movie.sequence.canvas.copy(fps=it)))))
        }
        Choice("Picture fitting",movie.sequence.canvas.fit,CanvasFit.entries,{it.name}) {
            action(UiAction.MovieChange(movie.copy(sequence=movie.sequence.copy(canvas=movie.sequence.canvas.copy(fit=it)))))
        }
        Choice("Transition between clips",movie.sequence.transitionMs,listOf(0L,250L,500L,1000L,2000L),{if(it==0L) "Cut" else "Dissolve · $it ms"}) {
            action(UiAction.MovieChange(movie.copy(sequence=movie.sequence.copy(transitionMs=it))))
        }
        Choice("Movie output format",movie.settings.container,Container.entries,{it.name}, enabled={ !ui.capabilities.available || it.muxer in ui.capabilities.muxers }) {
            val s=when(it) {
                Container.WEBM -> movie.settings.copy(container=it,video=VideoEncoder.VP9,audio=AudioEncoder.OPUS)
                Container.WAV -> movie.settings.copy(container=it,audio=AudioEncoder.PCM_S16LE)
                Container.FLAC -> movie.settings.copy(container=it,audio=AudioEncoder.FLAC)
                else -> movie.settings.copy(container=it,video=VideoEncoder.X264,audio=AudioEncoder.AAC)
            }
            action(UiAction.MovieChange(movie.copy(settings=s)))
        }
        Choice("Movie upload limit",movie.targetBytes,listOf(10_000_000L,25_000_000L,50_000_000L,100_000_000L,null),{if(it==null) "Manual · no byte limit" else "${it/1_000_000} MB · output below $it bytes"}) {
            action(UiAction.MovieChange(movie.copy(targetBytes=it)))
        }
        problems.take(3).forEach { Text(it,color=MaterialTheme.colorScheme.error) }
        if(!ui.capabilities.available) Text(ui.capabilities.reason,style=MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FormaOutlinedButton(onClick={action(UiAction.MovieQueue)},enabled=ready,modifier=Modifier.testTag("movie-queue")) { Text("Queue movie") }
            FormaOutlinedButton(onClick={action(UiAction.MoviePreview)},enabled=native,modifier=Modifier.testTag("movie-preview")) { Text("Render full preview") }
            FormaButton(onClick={action(UiAction.MovieExport)},enabled=native,modifier=Modifier.testTag("movie-export")) { Text("Export movie") }
        }
        Text("Preview renders the whole movie at up to 480 pixels. It uses the same edits and queue; final export is checked separately.",style=MaterialTheme.typography.bodySmall)
        val preview=jobs.firstOrNull { it.spec.id==ui.moviePreviewJobId }
        if(preview!=null) {
            Text(if(ui.moviePreviewRevision!=ui.movieRevision) "Preview belongs to an earlier movie edit." else "Rendered preview: ${preview.state.name}",style=MaterialTheme.typography.bodySmall)
            FormaOutlinedButton(onClick={action(UiAction.OpenMoviePreview)},enabled=preview.state==JobState.COMPLETED) { Text("Open rendered preview") }
        }
    }
    if(selected!=null && (split || exactTrim)) {
        val end=selected.trim.endMs ?: selected.source.durationMs
        MovieTimeDialog(selected.trim.startMs,end,split,onDismiss={split=false;exactTrim=false}) { start,finish ->
            val command=if(split) TimelineCommand.Split(selected.id,start,java.util.UUID.randomUUID().toString()) else TimelineCommand.TrimClip(selected.id,Trim(start,finish))
            action(UiAction.MovieEdit(command));split=false;exactTrim=false
        }
    }
}

@Composable private fun MovieTimeDialog(start: Long,end: Long,split: Boolean,onDismiss:()->Unit,apply:(Long,Long)->Unit) {
    var a by remember { mutableStateOf((if(split) start+(end-start)/2 else start).toString()) }
    var b by remember { mutableStateOf(end.toString()) }
    val av=a.toLongOrNull();val bv=b.toLongOrNull()
    val valid=av!=null && bv!=null && if(split) av>start && av<end else av>=0 && bv>av
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(split) "Split at source time" else "Keep source range")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(a,{a=it.take(12)},label={Text(if(split) "Split time (ms)" else "Start (ms)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
            if(!split) OutlinedTextField(b,{b=it.take(12)},label={Text("End (ms)")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        }
    },confirmButton={FormaTextButton(onClick={apply(av!!,bv!!)},enabled=valid){Text("Apply")}},dismissButton={FormaTextButton(onClick=onDismiss){Text("Cancel")}})
}
