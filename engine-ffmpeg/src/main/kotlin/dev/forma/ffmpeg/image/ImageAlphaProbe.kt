package dev.forma.ffmpeg.image
import dev.forma.core.image.*
import dev.forma.ffmpeg.FfmpegBridge
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
data class ImageAlphaFacts(val minimum:Int,val hash:String,val bytes:Long)
object ImageAlphaProbe {
    fun read(file:File,size:ImageSize):ImageAlphaFacts {
        if(file.length()!=size.width.toLong()*size.height)throw ImageFailure("OUTPUT_INVALID","Alpha plane dimensions disagree with the prepared graph.")
        var minimum=255
        file.inputStream().use { input->val buffer=ByteArray(65536);while(true){val count=input.read(buffer);if(count<0)break;for(i in 0 until count)minimum=minOf(minimum,buffer[i].toInt() and 255)} }
        return ImageAlphaFacts(minimum,ImageProbe.hash(file),file.length())
    }
    suspend fun reference(bridge:FfmpegBridge,plan:ImagePlan,prepared:List<String>,directory:File):ImageAlphaFacts {
        val file=File(directory,"reference-${UUID.randomUUID()}.gray")
        try {
            val result=bridge.execute(plan.alphaArguments(prepared,file.path)){}
            if(result.exitCode!=0)throw ImageFailure("ENCODE_FAILED","Native reference alpha failed. ${result.diagnostics}")
            currentCoroutineContext().ensureActive();return read(file,plan.geometry.outputSize)
        }finally{withContext(NonCancellable){file.delete()}}
    }
    suspend fun decoded(bridge:FfmpegBridge,path:String,size:ImageSize):ImageAlphaFacts {
        val file=File(File(path).parentFile,"decoded-alpha-${UUID.randomUUID()}.gray")
        try {
            val result=bridge.execute(listOf("-hide_banner","-nostdin","-v","error","-xerror","-n","-filter_threads","2","-filter_complex_threads","2","-noautorotate","-threads","2","-i",path,"-map","0:v:0","-an","-sn","-dn","-vf","format=rgba,alphaextract","-frames:v","1","-c:v","rawvideo","-threads:v","1","-pix_fmt","gray","-f","rawvideo",file.path)){}
            if(result.exitCode!=0)throw ImageFailure("OUTPUT_INVALID","Encoded alpha could not be decoded. ${result.diagnostics}")
            currentCoroutineContext().ensureActive();return read(file,size)
        }finally{withContext(NonCancellable){file.delete()}}
    }
}
