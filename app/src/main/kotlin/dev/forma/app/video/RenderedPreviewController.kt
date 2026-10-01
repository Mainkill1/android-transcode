package dev.forma.app.video

import android.content.Context
import dev.forma.app.data.MediaFiles
import dev.forma.app.work.RunCoordinator
import dev.forma.app.work.RunMode
import dev.forma.core.*
import dev.forma.ffmpeg.FfmpegBridge
import java.io.File
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

sealed interface VideoRenderState {
    data object Idle:VideoRenderState
    data object Stale:VideoRenderState
    data class Waiting(val sourceKey:String,val revision:Long):VideoRenderState
    data class Rendering(val sourceKey:String,val revision:Long):VideoRenderState
    data class Ready(val file:File,val sourceKey:String,val revision:Long,val window:Trim,
        val edit:SourceEdit?=null,val settings:Settings?=null,val movie:MovieProject?=null):VideoRenderState
    data class Error(val sourceKey:String,val revision:Long,val message:String):VideoRenderState
}

/** Short private preview; all FFmpeg work passes through the process-shared native bridge. */
class RenderedPreviewController(context:Context,private val files:MediaFiles,private val bridge:FfmpegBridge,
    private val runs:RunCoordinator,private val scope:CoroutineScope) {
    private val root=File(context.cacheDir,"video-preview")
    private val mutable=MutableStateFlow<VideoRenderState>(VideoRenderState.Idle)
    val state=mutable.asStateFlow()
    private var task:Job?=null
    private var serial=0L

    fun request(edit:SourceEdit,settings:Settings,playheadMs:Long,revision:Long) {
        schedule(edit.source.uri,revision,edit,settings,null) {render(edit,settings,playheadMs)}
    }

    fun requestSequence(movie:MovieProject,playheadMs:Long,revision:Long) {
        when(val result=SequenceWindowPlanner.window(movie,playheadMs)) {
            is MovieWindowResult.Unsupported -> {
                invalidate()
                mutable.value=VideoRenderState.Error(MOVIE_KEY,revision,result.reason)
            }
            is MovieWindowResult.Planned ->
                schedule(MOVIE_KEY,revision,null,null,movie) {renderSequence(movie,result.window)}
        }
    }

    private fun schedule(key:String,revision:Long,edit:SourceEdit?,settings:Settings?,movie:MovieProject?,
        renderNow:suspend ()->Pair<File,Trim>) {
        val previous=task
        previous?.cancel()
        val token=++serial
        mutable.value=VideoRenderState.Waiting(key,revision)
        task=scope.launch {
            previous?.join()
            if(token!=serial)return@launch
            root.listFiles()?.forEach {it.deleteRecursively()}
            var output:File?=null
            var lease:RunCoordinator.PreviewLease?=null
            try {
                val renderingJob=currentCoroutineContext()[Job]!!
                while(lease==null) {
                    runs.state.first {it.mode==RunMode.IDLE}
                    ensureActive()
                    lease=runs.tryAcquirePreview {
                        if(token==serial)mutable.value=VideoRenderState.Stale
                        renderingJob.cancel(CancellationException("Foreground conversion started."))
                    }
                    if(lease==null)delay(25)
                }
                if(token!=serial)return@launch
                mutable.value=VideoRenderState.Rendering(key,revision)
                val result=renderNow()
                output=result.first
                ensureActive()
                if(token==serial)mutable.value=VideoRenderState.Ready(result.first,key,revision,result.second,edit,settings,movie)
            } catch(cancel:CancellationException) {throw cancel}
            catch(error:Exception) {if(token==serial)mutable.value=VideoRenderState.Error(key,revision,
                error.message ?: "Could not render preview." )}
            finally {
                if(token!=serial || mutable.value !is VideoRenderState.Ready)
                    output?.parentFile?.deleteRecursively()
                lease?.close()
            }
        }
    }

    fun invalidate() {
        val old=(mutable.value as? VideoRenderState.Ready)?.file
        val pending=task
        pending?.cancel()
        ++serial
        mutable.value=VideoRenderState.Stale
        if(old!=null)scope.launch {pending?.join();old.parentFile?.deleteRecursively()}
    }

    suspend fun cancelAndJoin() {
        ++serial;task?.cancelAndJoin();task=null
        root.listFiles()?.forEach {it.deleteRecursively()}
        mutable.value=VideoRenderState.Idle
    }

    private suspend fun render(edit:SourceEdit,selected:Settings,playheadMs:Long):Pair<File,Trim> = withContext(Dispatchers.IO) {
        require(edit.source.videoTracks>0 && edit.source.imageInfo==null) {"Choose a video for preview."}
        val window=PreviewWindow.select(edit,playheadMs)
        val settings=selected.copy(container=Container.MP4,video=VideoEncoder.X264,rateControl=RateControl.QUALITY,
            maxHeight=720,fps=30,audio=if(edit.source.audioTracks>0)AudioEncoder.AAC else AudioEncoder.NONE,
            keepMetadata=false,effects=edit.effects)
        val id=UUID.randomUUID().toString()
        val spec=JobSpec(id,edit.source,window,settings,targetBytes=null)
        val directory=File(root,id).apply {check(isDirectory || mkdirs()) {"Could not prepare preview storage."}}
        val output=File(directory,"preview.mp4")
        try {
            val input=files.stage(spec)
            val caps=bridge.capabilities()
            val problems=Planner.validate(edit.source,window,settings,caps)
            require(problems.isEmpty()) {problems.joinToString(" ")}
            val args=bridge.prepare(edit.source,window,settings,input.path,output.path)
            val result=bridge.execute(args) {}
            check(result.exitCode==0) {"Could not render preview. ${result.diagnostics}"}
            currentCoroutineContext().ensureActive()
            check(output.length() in 1..MAX_PREVIEW_BYTES) {"Preview exceeded the private storage limit."}
            val actual=bridge.probe(output.path)
            check(actual.videoTracks>0 && actual.durationMs in 1..5_500) {"Rendered preview duration or video track is invalid."}
            output to window
        } catch(error:Throwable) {directory.deleteRecursively();throw error}
        finally {files.workDir(spec).deleteRecursively()}
    }

    private suspend fun renderSequence(movie:MovieProject,window:MovieWindow):Pair<File,Trim> = withContext(Dispatchers.IO) {
        val c=window.sequence.canvas
        val ratio=minOf(1.0,720.0/maxOf(c.width,c.height))
        val canvas=c.copy(width=maxOf(4,(c.width*ratio).toInt()/2*2),
            height=maxOf(4,(c.height*ratio).toInt()/2*2))
        val sequence=window.sequence.copy(canvas=canvas)
        val settings=movie.settings.copy(container=Container.MP4,video=VideoEncoder.X264,
            rateControl=RateControl.QUALITY,crf=28,keepMetadata=false,
            audio=if(movie.settings.audio==AudioEncoder.NONE)AudioEncoder.NONE else AudioEncoder.AAC)
        val id=UUID.randomUUID().toString()
        val first=sequence.timeline.clips.first().source
        val spec=JobSpec(id,first,Trim(),settings,targetBytes=null,sequence=sequence)
        val directory=File(root,id).apply {check(isDirectory || mkdirs()) {"Could not prepare movie preview storage."}}
        val output=File(directory,"preview.mp4")
        try {
            val inputs=files.stageInputs(spec)
            val prepared=bridge.prepareSequence(sequence,settings,inputs.map {it.path},output.path)
            val duration=window.outputFrames.toDouble()/c.fps
            val seek=window.seekFrames.toDouble()/c.fps
            val limit=prepared.indexOfLast {it=="-t"}
            check(limit>=0 && limit+1<prepared.size) {"The movie preview renderer has no output duration."}
            val args=prepared.toMutableList().apply {
                this[limit+1]=String.format(Locale.ROOT,"%.6f",duration)
                add(limit,"-ss");add(limit+1,String.format(Locale.ROOT,"%.6f",seek))
            }
            val result=bridge.execute(args) {}
            check(result.exitCode==0) {"Could not render movie preview. ${result.diagnostics}"}
            currentCoroutineContext().ensureActive()
            check(output.length() in 1..MAX_PREVIEW_BYTES) {"Movie preview exceeded the private storage limit."}
            val actual=bridge.probe(output.path)
            check(actual.videoTracks>0 && actual.durationMs in 1..5_500 &&
                kotlin.math.abs(actual.durationMs-duration*1_000)<250) {"Movie preview duration or video track is invalid."}
            output to Trim(window.requestStartFrame*1_000/c.fps,window.requestEndFrame*1_000/c.fps)
        } catch(error:Throwable) {directory.deleteRecursively();throw error}
        finally {files.workDir(spec).deleteRecursively()}
    }

    companion object {const val MAX_PREVIEW_BYTES=96L*1024*1024;const val MOVIE_KEY="movie-preview"}
}
