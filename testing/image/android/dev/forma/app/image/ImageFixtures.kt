package dev.forma.app.image
import android.content.Context
import android.graphics.*
import androidx.core.content.FileProvider
import dev.forma.core.image.*
import dev.forma.ffmpeg.image.ImageProbe
import java.io.File
import java.util.Random
object ImageFixtures {
    fun png(context:Context,directory:File,w:Int=101,h:Int=77,alpha:Boolean=true,noise:Boolean=false):Pair<File,ImageInfo> {
        directory.mkdirs();val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val random=Random(813)
        for(y in 0 until h)for(x in 0 until w)bitmap.setPixel(x,y,if(noise)Color.argb(if(alpha)random.nextInt(256) else 255,random.nextInt(256),random.nextInt(256),random.nextInt(256)) else when {
            x<w/2 && y<h/2->Color.argb(if(alpha)128 else 255,255,0,0)
            x>=w/2 && y<h/2->Color.GREEN
            x<w/2->Color.BLUE
            else->Color.WHITE
        })
        val file=File(directory,"original.png");file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        return file to ImageProbe.inspect(file.path)
    }
    fun source(context:Context,file:File,info:ImageInfo)=ImageSource(FileProvider.getUriForFile(context,"${context.packageName}.files",file).toString(),file.name,info.hash,info.bytes)
    fun assertCornerPixels(file:File,w:Int,h:Int){val b=BitmapFactory.decodeFile(file.path)?:error("Output did not decode.");try{org.junit.Assert.assertEquals(w,b.width);org.junit.Assert.assertEquals(h,b.height)}finally{b.recycle()}}
}
