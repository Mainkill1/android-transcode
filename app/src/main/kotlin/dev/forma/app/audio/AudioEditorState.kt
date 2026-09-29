package dev.forma.app.audio

import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.ffmpeg.audio.AudioPreviewResult

/** The first value of a continuous gesture is one undo point, capped at 50 edits. */
class AudioEditHistory {
    var current:AudioEdit=AudioEdit();private set
    private val past=ArrayDeque<AudioEdit>();private val future=ArrayDeque<AudioEdit>()
    private var gesture:AudioEdit?=null
    val canUndo:Boolean get()=past.isNotEmpty() || gesture?.let { it!=current }==true
    val canRedo:Boolean get()=future.isNotEmpty()
    fun resetBaseline(edit:AudioEdit) { current=edit;past.clear();future.clear();gesture=null }
    fun update(edit:AudioEdit,commit:Boolean) {
        if(gesture==null)gesture=current
        current=edit
        if(commit)finish()
    }
    private fun finish() {
        gesture?.let { if(it!=current) { past.addLast(it);if(past.size>50)past.removeFirst();future.clear() } };gesture=null
    }
    fun undo():AudioEdit { finish();if(past.isNotEmpty()) { future.addLast(current);current=past.removeLast() };return current }
    fun redo():AudioEdit { finish();if(future.isNotEmpty()) { past.addLast(current);current=future.removeLast() };return current }
}
data class AudioPreviewState(val identity:String="",val status:String="Not rendered",val path:String?=null,
    val result:AudioPreviewResult?=null,val error:String?=null) {
    fun accept(identity:String,path:String)=if(this.identity==identity)copy(status="Rendered preview",path=path) else this
}
data class AudioEditorState(val open:Boolean=false,val canUndo:Boolean=false,val canRedo:Boolean=false,val revision:Long=0,
    val preview:AudioPreviewState=AudioPreviewState())
object AudioEditorSettings {
    fun audioImport(current:Settings)=if(current.container.audioOnly)current else when(current.audio) {
        AudioEncoder.AAC -> current.copy(container=Container.M4A)
        AudioEncoder.FLAC -> current.copy(container=Container.FLAC)
        AudioEncoder.PCM_S16LE,AudioEncoder.PCM_F32LE -> current.copy(container=Container.WAV)
        else -> current
    }
    fun audioImportProblem(current:Settings):String?=if(current.container.audioOnly) null else when(current.audio) {
        AudioEncoder.AAC,AudioEncoder.FLAC,AudioEncoder.PCM_S16LE,AudioEncoder.PCM_F32LE -> null
        AudioEncoder.OPUS -> "Opus audio-only export is unavailable. Choose M4A, WAV or FLAC in Audio output settings."
        AudioEncoder.NONE -> "Choose an audio encoder to export this file."
    }
    fun stereoEnabled(current:Settings)=current.audioEdit.output.channels?.let { it==ChannelMode.STEREO } ?: current.stereo
    fun withStereo(current:Settings,enabled:Boolean)=current.copy(audioEdit=current.audioEdit.copy(output=
        current.audioEdit.output.copy(channels=if(enabled)ChannelMode.STEREO else ChannelMode.SOURCE)))
    fun previewKey(source:SourceEdit?,settings:Settings)=listOf(source,settings.audioTrack,settings.audioEdit,settings.stereo,settings.container.audioOnly,settings.audio)
    fun preset(current:Settings,goal:Goal,quality:Quality)=Planner.preset(goal,quality).copy(audioEdit=current.audioEdit,audioTrack=current.audioTrack)
}
