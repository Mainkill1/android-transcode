package dev.forma.app.image
import android.content.Context
import dev.forma.app.data.MediaFiles
import dev.forma.app.work.*
import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.ffmpeg.FfmpegBridge
import dev.forma.ffmpeg.image.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ImagePreviewRequest(val info:ImageInfo,val actualPixels:Boolean=false,val revision:Long,val owner:String="preview")
data class ImagePreviewResult(val path:String,val revision:Long,val key:String,val size:ImageSize,val actualPixels:Boolean)
data class ImagePreviewState(val revision:Long=-1,val key:String="",val status:String="Original",val path:String?=null,val size:ImageSize?=null,val actualPixels:Boolean=false,val error:String?=null) {
    fun accept(revision:Long,key:String,path:String,actualPixels:Boolean)=if(this.revision!=revision || this.key!=key)this else copy(status=if(actualPixels)"Actual pixels" else "Preview",path=path,actualPixels=actualPixels,error=null)
}
class ImagePreviewRenderer(private val files:MediaFiles,private val bridge:FfmpegBridge,private val markup:ImageMarkupRenderer,private val root:File) {
    suspend fun render(document:ImageEditDocument,request:ImagePreviewRequest):ImagePreviewResult=withContext(Dispatchers.IO) {
        val key="${document.source.hash}:${document.revision}:${document.hashCode()}:forma-image-rgb-v1:${request.actualPixels}"
        val spec=ImageJobSpec(UUID.randomUUID().toString(),document,request.info)
        val directory=File(root,spec.id).apply {mkdirs()}
        try {
            val base=ImageGeometry.resolve(request.info,document,ImageAttempt(0,ImageFormat.PNG,90))
            val longEdge=maxOf(base.outputSize.width,base.outputSize.height)
            val scale=if(request.actualPixels)1.0 else minOf(1.0,1600.0/longEdge)
            var attempt=ImageAttempt(0,ImageFormat.PNG,90,scale,rendererIdentity=if(request.actualPixels)"forma-image-actual-v1" else "forma-image-proxy-v1")
            val geometry=ImageGeometry.resolve(request.info,document,attempt)
            val budget=minOf(32L*1024*1024,(Runtime.getRuntime().maxMemory()*.25).toLong())
            if(geometry.outputSize.width.toLong()*geometry.outputSize.height*4>budget)throw ImageFailure("RESOURCE_LIMIT","Actual-pixel view exceeds the 32 MiB preview budget. Reduce the output size before inspecting at 100%.")
            ImageValidation.requireMemory(request.info,geometry.outputSize,(Runtime.getRuntime().maxMemory()*.65).toLong(),document.annotations.isNotEmpty())
            val input=files.stage(spec);val actual=bridge.inspectImage(input.path)
            val plane=if(document.annotations.isNotEmpty())markup.render(document,geometry,directory)else null
            attempt=attempt.copy(markupPath=plane?.path)
            // Preview encodes lossless PNG even when the chosen export format is lossy.
            val output=File(directory,"preview.png")
            val args=bridge.prepare(spec,actual,attempt,input.path,output.path)
            val result=bridge.execute(args){}
            if(result.exitCode!=0)throw ImageFailure("PREVIEW_FAILED",result.diagnostics)
            ImageMetadata.finalize(output,ImageFormat.PNG)
            ImageVerifier(bridge).verify(output.path,ImagePlanner.plan(actual,spec,attempt,bridge.capabilities()))
            plane?.let{File(it.path).delete()};currentCoroutineContext().ensureActive()
            ImagePreviewResult(output.path,document.revision,key,geometry.outputSize,request.actualPixels)
        }catch(e:Throwable){directory.deleteRecursively();throw e}
        finally {files.workDir(spec).deleteRecursively()}
    }
}
class ImagePreviewController(private val scope:CoroutineScope,private val runs:RunCoordinator,files:MediaFiles,bridge:FfmpegBridge,markup:ImageMarkupRenderer,context:Context) {
    private val root=File(context.cacheDir,"image-preview").apply{mkdirs()}
    private val renderer=ImagePreviewRenderer(files,bridge,markup,root)
    private val mutable=MutableStateFlow(ImagePreviewState())
    val state=mutable.asStateFlow()
    private var current:Job?=null
    private var serial=0L
    fun request(document:ImageEditDocument,info:ImageInfo,actualPixels:Boolean=false) {
        val old=current;old?.cancel();val token=++serial
        val key="${document.source.hash}:${document.revision}:${document.hashCode()}:forma-image-rgb-v1:$actualPixels"
        mutable.value=ImagePreviewState(document.revision,key,"Updating",path=mutable.value.path,size=mutable.value.size)
        current=scope.launch {
            old?.join();delay(150)
            if(runs.state.value.mode!=RunMode.IDLE){if(token==serial)mutable.value=mutable.value.copy(status="Updating after conversion");return@launch}
            try {
                val rendered=renderer.render(document,ImagePreviewRequest(info,actualPixels,document.revision))
                if(token==serial){val previous=mutable.value.path
                    mutable.value=mutable.value.accept(rendered.revision,rendered.key,rendered.path,actualPixels).copy(size=rendered.size)
                    if(previous!=null && previous!=rendered.path)File(previous).parentFile?.deleteRecursively()
                }else File(rendered.path).parentFile?.deleteRecursively()
            }catch(c:CancellationException){throw c}catch(e:Exception){if(token==serial)mutable.value=mutable.value.copy(status="Unavailable",error=e.message)}
        }
    }
    suspend fun cancelAndJoin(){++serial;current?.cancelAndJoin();current=null;mutable.value=mutable.value.copy(status="Original",path=null);root.listFiles()?.forEach{it.deleteRecursively()}}
}
