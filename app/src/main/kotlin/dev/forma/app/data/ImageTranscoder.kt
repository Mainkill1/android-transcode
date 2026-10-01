package dev.forma.app.data
import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.ffmpeg.FfmpegBridge
import dev.forma.ffmpeg.image.*
import dev.forma.app.image.ImageMarkupRenderer
import java.io.File
import kotlinx.coroutines.*
/** Every candidate rereads the original, reapplies edits and crosses typed prepare. */
class ImageTranscoder(private val files:MediaFiles,private val bridge:FfmpegBridge,private val markup:ImageMarkupRenderer,
    private val memoryBudget:Long=(Runtime.getRuntime().maxMemory()*0.65).toLong()) {
    suspend fun run(spec:ImageJobSpec,onState:suspend(JobState)->Unit,onProgress:(ImageStageProgress)->Unit)=withContext(Dispatchers.IO) {
        ImageValidation.requireValid(spec.document)
        val directory=files.workDir(spec)
        var published:File?=null
        try {
            val caps=bridge.capabilities();if(!caps.available)throw ImageFailure("CAPABILITY_UNAVAILABLE",caps.reason)
            onProgress(ImageStageProgress(ImageStage.STAGING));val input=files.stage(spec)
            onProgress(ImageStageProgress(ImageStage.INSPECTING))
            val header=ImageProbe.inspect(input.path)
            val preflight=ImageGeometry.resolve(header,spec.document,ImageAttempt(0,ImageFormat.PNG,90))
            ImageValidation.requireMemory(header,preflight.outputSize,memoryBudget,spec.document.annotations.isNotEmpty())
            val actual=bridge.inspectImage(input.path)
            val format=ImagePlanner.resolveFormat(actual,spec,caps)
            // Resolve Auto once in this queued snapshot; retries never switch codec.
            val outputSpec=QueueJobSpec.Image(ImageJobSpec(spec.id,spec.document,actual,format))
            val output=files.output(outputSpec);if(output.exists())throw ImageFailure("OUTPUT_EXISTS","An output already exists. Retry as a new job.")
            val candidates=ImageFitPolicy.candidates(spec,actual,format)
            for(candidate in candidates){
                currentCoroutineContext().ensureActive()
                if(ImageProbe.hash(input)!=spec.document.source.hash)throw ImageFailure("SOURCE_CHANGED","Staged original changed.")
                onProgress(ImageStageProgress(ImageStage.PREPARING,candidate.index+1))
                val geometry=ImageGeometry.resolve(actual,spec.document,candidate)
                ImageValidation.requireMemory(actual,geometry.outputSize,memoryBudget,spec.document.annotations.isNotEmpty())
                val requiredDisk=actual.bytes+geometry.outputSize.width.toLong()*geometry.outputSize.height*12+64L*1024*1024
                if(directory.usableSpace<requiredDisk)throw ImageFailure("RESOURCE_LIMIT","Not enough private storage for source, markup and output.")
                val plane=if(spec.document.annotations.isNotEmpty())markup.render(spec.document,geometry,directory) else null
                val attempt=candidate.copy(markupPath=plane?.path)
                val plan=ImagePlanner.plan(actual,spec,attempt,caps)
                val temp=File(directory,"candidate.${format.extension}")
                temp.delete();val arguments=bridge.prepare(spec,actual,attempt,input.path,temp.path)
                val referenceAlpha=if(format!=ImageFormat.JPEG)ImageAlphaProbe.reference(bridge,plan,arguments,directory) else null
                if(candidate.index==0)onState(JobState.RUNNING)
                onProgress(ImageStageProgress(ImageStage.ENCODING,candidate.index+1))
                val result=bridge.execute(arguments){}
                if(result.exitCode!=0)throw ImageFailure("ENCODE_FAILED","Native ${format.name} encode failed (${result.exitCode}). ${result.diagnostics}")
                currentCoroutineContext().ensureActive()
                ImageMetadata.finalize(temp,format)
                if(candidate.index==0)onState(JobState.VERIFYING)
                onProgress(ImageStageProgress(ImageStage.VERIFYING,candidate.index+1))
                val verified=ImageVerifier(bridge).verify(temp.path,plan,referenceAlpha)
                if(ImageProbe.hash(input)!=spec.document.source.hash)throw ImageFailure("SOURCE_CHANGED","Original changed during export.")
                plane?.let { File(it.path).delete() }
                if(!ImageFitPolicy.fits(verified.bytes,spec.document.output.targetBytes)){temp.delete();continue}
                currentCoroutineContext().ensureActive()
                val requestedSize=ImageGeometry.resolve(actual,spec.document,ImageAttempt(0,format,candidate.quality)).outputSize
                val requestedQuality=when(format){ImageFormat.JPEG->spec.document.output.jpegQuality;ImageFormat.WEBP->if(spec.document.output.lossless)null else spec.document.output.webpQuality;else->null}
                val diagnostics=ImageExportDiagnostics(spec.document.output.format,format,requestedSize,geometry.outputSize,requestedQuality,
                    if(requestedQuality==null)null else candidate.quality,candidate.index+1,verified.bytes,verified.info.alpha,caps.build)
                onProgress(ImageStageProgress(ImageStage.PUBLISHING,candidate.index+1,diagnostics=diagnostics))
                publishVerifiedImage(temp,output){onState(JobState.COMPLETED)}
                published=output
                return@withContext
            }
            throw ImageFailure("CANNOT_FIT","Cannot fit these constraints. Increase the limit or explicitly allow quality/resize changes.")
        }catch(e:Exception){if(e !is CancellationException)published?.delete();throw e}
        finally { withContext(NonCancellable){directory.deleteRecursively()} }
    }
}
