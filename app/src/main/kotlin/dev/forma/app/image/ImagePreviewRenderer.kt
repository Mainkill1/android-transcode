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

data class ImagePreviewRegion(val left:Int,val top:Int,val width:Int,val height:Int)
data class ImagePreviewRequest(val info:ImageInfo,val actualPixels:Boolean=false,val revision:Long,val owner:String="preview",val centerX:Int?=null,val centerY:Int?=null,val retainedBytes:Long=0)
data class ImagePreviewResult(val path:String,val revision:Long,val key:String,val size:ImageSize,val actualPixels:Boolean,val region:ImagePreviewRegion?=null,val geometry:ImageGeometryResult?=null)
object ImagePreviewBudget {
    const val MAX_ENCODED_BYTES=32L*1024*1024
    fun pngUpperBound(size:ImageSize):Long {
        val raw=size.height.toLong()*(4L*size.width+1)
        return raw+raw/8+1024*1024
    }
    fun previewScale(full:ImageSize,actualPixels:Boolean,markup:Boolean):Double {
        if(actualPixels)return 1.0
        var scale=minOf(1.0,1600.0/maxOf(full.width,full.height))
        repeat(100){
            val size=ImageSize(kotlin.math.floor(full.width*scale+.5).toInt().coerceAtLeast(1),kotlin.math.floor(full.height*scale+.5).toInt().coerceAtLeast(1))
            if(renderReserve(size,markup)<=MAX_ENCODED_BYTES)return scale
            scale*=.95
        }
        throw ImageFailure("RESOURCE_LIMIT","No bounded preview size is available.")
    }
    fun renderReserve(size:ImageSize,markup:Boolean)=pngUpperBound(size)*(if(markup)3 else 2)+size.width.toLong()*size.height
    fun requireFits(candidate:Long,retained:Long){if(candidate<0 || retained<0 || candidate>MAX_ENCODED_BYTES-retained)throw ImageFailure("RESOURCE_LIMIT","Preview cache exceeds its shared 32 MiB budget. Use Fit or reduce output dimensions.")}
}
data class ImagePreviewState(val revision:Long=-1,val key:String="",val status:String="Original",val path:String?=null,val size:ImageSize?=null,val actualPixels:Boolean=false,val error:String?=null,val region:ImagePreviewRegion?=null,val geometry:ImageGeometryResult?=null) {
    fun updating(revision:Long,key:String)=copy(revision=revision,key=key,status="Updating",error=null)
    fun accept(revision:Long,key:String,path:String,actualPixels:Boolean)=if(this.revision!=revision || this.key!=key)this else copy(status=if(actualPixels)"Actual pixels" else "Preview",path=path,actualPixels=actualPixels,error=null)
}
class ImagePreviewRenderer(private val files:MediaFiles,private val bridge:FfmpegBridge,private val markup:ImageMarkupRenderer,private val root:File) {
    suspend fun render(document:ImageEditDocument,request:ImagePreviewRequest):ImagePreviewResult=withContext(Dispatchers.IO) {
        val key="${document.source.hash}:${document.revision}:${document.hashCode()}:forma-image-rgb-v1:${request.actualPixels}:${request.centerX}:${request.centerY}"
        val spec=ImageJobSpec(UUID.randomUUID().toString(),document,request.info)
        val directory=File(root,spec.id).apply {mkdirs()}
        try {
            val base=ImageGeometry.resolve(request.info,document,ImageAttempt(0,ImageFormat.PNG,90))
            val scale=ImagePreviewBudget.previewScale(base.outputSize,request.actualPixels,document.annotations.isNotEmpty())
            var attempt=ImageAttempt(0,ImageFormat.PNG,90,scale,rendererIdentity=if(request.actualPixels)"forma-image-actual-v1" else "forma-image-proxy-v1")
            val geometry=ImageGeometry.resolve(request.info,document,attempt)
            if(directory.usableSpace<request.info.bytes+geometry.outputSize.width.toLong()*geometry.outputSize.height*12+64L*1024*1024)throw ImageFailure("RESOURCE_LIMIT","Not enough private storage for the preview.")
            val reserve=ImagePreviewBudget.renderReserve(geometry.outputSize,document.annotations.isNotEmpty())
            ImagePreviewBudget.requireFits(reserve,request.retainedBytes)
            val budget=minOf(32L*1024*1024,(Runtime.getRuntime().maxMemory()*.25).toLong())
            val region=if(request.actualPixels){
                val rw=minOf(1024,geometry.outputSize.width);val rh=minOf(1024,geometry.outputSize.height)
                ImagePreviewRegion(((request.centerX?:geometry.outputSize.width/2)-rw/2).coerceIn(0,geometry.outputSize.width-rw),((request.centerY?:geometry.outputSize.height/2)-rh/2).coerceIn(0,geometry.outputSize.height-rh),rw,rh)
            }else null
            val displayedPixels=region?.let{it.width.toLong()*it.height}?:geometry.outputSize.width.toLong()*geometry.outputSize.height
            if(displayedPixels*4>budget)throw ImageFailure("RESOURCE_LIMIT","Preview exceeds its decoded bitmap budget.")
            ImageValidation.requireMemory(request.info,geometry.outputSize,(Runtime.getRuntime().maxMemory()*.65).toLong(),document.annotations.isNotEmpty())
            val input=files.stage(spec);val actual=bridge.inspectImage(input.path)
            val plane=if(document.annotations.isNotEmpty())markup.render(document,geometry,directory)else null
            attempt=attempt.copy(markupPath=plane?.path)
            // Preview encodes lossless PNG even when the chosen export format is lossy.
            val output=File(directory,"preview.png")
            val args=bridge.prepare(spec,actual,attempt,input.path,output.path)
            val plan=ImagePlanner.plan(actual,spec,attempt,bridge.capabilities())
            val referenceAlpha=ImageAlphaProbe.reference(bridge,plan,args,directory)
            val result=bridge.execute(args){}
            if(result.exitCode!=0)throw ImageFailure("PREVIEW_FAILED",result.diagnostics)
            ImageMetadata.finalize(output,ImageFormat.PNG)
            ImagePreviewBudget.requireFits(output.length(),request.retainedBytes)
            ImageVerifier(bridge).verify(output.path,plan,referenceAlpha)
            plane?.let{File(it.path).delete()};currentCoroutineContext().ensureActive()
            ImagePreviewResult(output.path,document.revision,key,geometry.outputSize,request.actualPixels,region,geometry)
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
    fun request(document:ImageEditDocument,info:ImageInfo,actualPixels:Boolean=false,centerX:Int?=null,centerY:Int?=null) {
        val old=current;old?.cancel();val token=++serial
        val key="${document.source.hash}:${document.revision}:${document.hashCode()}:forma-image-rgb-v1:$actualPixels:$centerX:$centerY"
        mutable.value=mutable.value.updating(document.revision,key)
        current=scope.launch {
            old?.join();delay(150)
            if(runs.state.value.mode!=RunMode.IDLE){if(token==serial)mutable.value=mutable.value.copy(status="Updating after conversion");return@launch}
            try {
                var retained=mutable.value.path?.let{File(it).length()}?:0
                val full=ImageGeometry.resolve(info,document,ImageAttempt(0,ImageFormat.PNG,90)).outputSize
                val scale=ImagePreviewBudget.previewScale(full,actualPixels,document.annotations.isNotEmpty())
                val size=ImageGeometry.resolve(info,document,ImageAttempt(0,ImageFormat.PNG,90,scale)).outputSize
                val reserve=ImagePreviewBudget.renderReserve(size,document.annotations.isNotEmpty())
                if(retained+reserve>ImagePreviewBudget.MAX_ENCODED_BYTES && token==serial){
                    val prior=mutable.value.path
                    mutable.value=mutable.value.copy(path=null,size=null,region=null,actualPixels=false,geometry=null)
                    prior?.let{File(it).parentFile?.deleteRecursively()};retained=0
                }
                val rendered=renderer.render(document,ImagePreviewRequest(info,actualPixels,document.revision,centerX=centerX,centerY=centerY,retainedBytes=retained))
                if(token==serial){val previous=mutable.value.path
                    mutable.value=mutable.value.accept(rendered.revision,rendered.key,rendered.path,actualPixels).copy(size=rendered.size,region=rendered.region,geometry=rendered.geometry)
                    if(previous!=null && previous!=rendered.path)File(previous).parentFile?.deleteRecursively()
                }else File(rendered.path).parentFile?.deleteRecursively()
            }catch(c:CancellationException){throw c}catch(e:Exception){if(token==serial)mutable.value=mutable.value.copy(status="Unavailable",error=e.message)}
        }
    }
    suspend fun cancelAndJoin(){++serial;current?.cancelAndJoin();current=null;mutable.value=mutable.value.copy(status="Original",path=null);root.listFiles()?.forEach{it.deleteRecursively()}}
}
