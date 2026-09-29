package dev.forma.app.image
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import dev.forma.core.image.*
import kotlinx.coroutines.*
data class DisplayImage(val bitmap:Bitmap,val orientationApplied:Boolean)
/** Both platform routes report normalized pixels, so display never applies EXIF twice. */
object ImageDisplayAdapter {
    suspend fun original(context:Context,uri:String,info:ImageInfo,modern:Boolean=Build.VERSION.SDK_INT>=28):DisplayImage=withContext(Dispatchers.IO) {
        ImageValidation.requireSupported(info)
        val upright=ImageGeometry.orientation(info).first
        val scale=minOf(1.0,1600.0/maxOf(upright.width,upright.height))
        if(modern && Build.VERSION.SDK_INT>=28){
            val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver,Uri.parse(uri))){decoder,_,_->decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE;decoder.setTargetSize((upright.width*scale).toInt().coerceAtLeast(1),(upright.height*scale).toInt().coerceAtLeast(1))}
            DisplayImage(bitmap,true)
        }else{
            var sample=1;while(maxOf(info.width,info.height)/sample>1600)sample*=2
            val bitmap=context.contentResolver.openInputStream(Uri.parse(uri))?.use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888})}
                ?:throw ImageFailure("SOURCE_ACCESS","Original image could not be displayed. Choose it again.")
            val m=ImageGeometry.orientation(info.copy(width=bitmap.width,height=bitmap.height)).second
            val matrix=Matrix().apply{setValues(floatArrayOf(m.a.toFloat(),m.c.toFloat(),0f,m.b.toFloat(),m.d.toFloat(),0f,0f,0f,1f))}
            val normalized=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
            if(normalized!==bitmap)bitmap.recycle();DisplayImage(normalized,true)
        }
    }
}
