package dev.forma.app.image
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dev.forma.core.image.*
import dev.forma.ffmpeg.image.ImageProbe
import java.io.File
import kotlinx.coroutines.*
data class StagedImage(val path:String,val source:ImageSource,val info:ImageInfo)
class ImageInputAdapter(private val context:Context) {
    suspend fun stage(sourceUri:String,jobId:String):StagedImage=withContext(Dispatchers.IO) {
        require(jobId.matches(Regex("[a-zA-Z0-9-]{1,128}")))
        val uri=Uri.parse(sourceUri);if(uri.scheme!="content")throw ImageFailure("SOURCE_ACCESS","Choose or share an image through Gallery or Files.")
        val directory=File(context.filesDir,"imports/$jobId").apply{mkdirs()}
        val original=File(directory,"source.image")
        try {
            context.contentResolver.openInputStream(uri)?.use { input->original.outputStream().use { out->
                val b=ByteArray(65536);var count=0L
                while(true){currentCoroutineContext().ensureActive();val n=input.read(b);if(n<0)break;count+=n
                    if(count>64L*1024*1024 || directory.usableSpace<64L*1024*1024+n)throw ImageFailure("RESOURCE_LIMIT","Not enough private storage or image exceeds the 64 MiB encoded limit.")
                    out.write(b,0,n)
                }
            }}?:throw ImageFailure("SOURCE_ACCESS","Image permission has expired. Share or choose it again.")
            val info=ImageProbe.inspect(original.path)
            val privateUri=FileProvider.getUriForFile(context,"${context.packageName}.files",original).toString()
            StagedImage(original.path,ImageSource(privateUri,"Image",info.hash,info.bytes,originalUri=sourceUri),info)
        }catch(c:CancellationException){directory.deleteRecursively();throw c}
        catch(e:SecurityException){directory.deleteRecursively();throw ImageFailure("SOURCE_ACCESS","Image permission has expired. Share or choose it again.")}
        catch(e:Exception){directory.deleteRecursively();if(e is ImageFailure)throw e;throw ImageFailure("SOURCE_ACCESS",e.message?:"Could not read the source image.")}
    }
}
