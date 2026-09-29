package dev.forma.app.audio

import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.core.settings.SettingValue
import dev.forma.ffmpeg.audio.AudioPreviewResult

/** The first value of a continuous gesture is one undo point, capped at 50 edits. */
class AudioEditHistory {
    private data class Point(val edit:AudioEdit,val channelOverride:SettingValue?)
    private var point=Point(AudioEdit(),null)
    val current:AudioEdit get()=point.edit
    val currentChannelOverride:SettingValue? get()=point.channelOverride
    private val past=ArrayDeque<Point>();private val future=ArrayDeque<Point>()
    private var gesture:Point?=null
    val canUndo:Boolean get()=past.isNotEmpty() || gesture?.let { it!=point }==true
    val canRedo:Boolean get()=future.isNotEmpty()
    fun resetBaseline(edit:AudioEdit,channelOverride:SettingValue?=null) { point=Point(edit,channelOverride);past.clear();future.clear();gesture=null }
    fun update(edit:AudioEdit,commit:Boolean,channelOverride:SettingValue?=currentChannelOverride) {
        if(gesture==null)gesture=point
        point=Point(edit,channelOverride)
        if(commit)finish()
    }
    private fun finish() {
        gesture?.let { if(it!=point) { past.addLast(it);if(past.size>50)past.removeFirst();future.clear() } };gesture=null
    }
    fun undo():AudioEdit { finish();if(past.isNotEmpty()) { future.addLast(point);point=past.removeLast() };return current }
    fun redo():AudioEdit { finish();if(future.isNotEmpty()) { past.addLast(point);point=future.removeLast() };return current }
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
