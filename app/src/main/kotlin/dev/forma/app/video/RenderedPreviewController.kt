package dev.forma.app.video

import android.content.Context
import dev.forma.app.data.MediaFiles
import dev.forma.app.work.RunCoordinator
import dev.forma.app.work.RunMode
import dev.forma.core.*
import dev.forma.ffmpeg.FfmpegBridge
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

sealed interface VideoRenderState {
    data object Idle:VideoRenderState
    data object Stale:VideoRenderState
    data class Waiting(val sourceKey:String,val revision:Long):VideoRenderState
    data class Rendering(val sourceKey:String,val revision:Long):VideoRenderState
    data class Ready(val file:File,val sourceKey:String,val revision:Long,val window:Trim):VideoRenderState
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
        val previous=task
        previous?.cancel()
        val token=++serial
        mutable.value=VideoRenderState.Waiting(edit.source.uri,revision)
        task=scope.launch {
            previous?.join()
            if(token!=serial)return@launch
            root.listFiles()?.forEach {it.deleteRecursively()}
            var output:File?=null
            try {
                runs.state.first {it.mode==RunMode.IDLE}
                ensureActive()
                if(token!=serial)return@launch
                mutable.value=VideoRenderState.Rendering(edit.source.uri,revision)
                val result=render(edit,settings,playheadMs)
                output=result.first
                ensureActive()
                if(token==serial)mutable.value=VideoRenderState.Ready(result.first,edit.source.uri,revision,result.second)
            } catch(cancel:CancellationException) {throw cancel}
            catch(error:Exception) {if(token==serial)mutable.value=VideoRenderState.Error(edit.source.uri,revision,
                error.message ?: "Could not render preview." )}
            finally {if(token!=serial || mutable.value !is VideoRenderState.Ready)
                output?.parentFile?.deleteRecursively()}
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

    companion object {const val MAX_PREVIEW_BYTES=96L*1024*1024}
}
