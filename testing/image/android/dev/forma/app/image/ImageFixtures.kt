package dev.forma.app.image
import android.content.Context
import android.graphics.*
import androidx.core.content.FileProvider
import dev.forma.core.image.*
import dev.forma.ffmpeg.image.ImageProbe
import java.io.File
import java.util.Random
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
object ImageFixtures {
    fun png(context:Context,directory:File,w:Int=101,h:Int=77,alpha:Boolean=true,noise:Boolean=false):Pair<File,ImageInfo> {
        directory.mkdirs();val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{setHasAlpha(alpha)};val random=Random(813)
        for(y in 0 until h)for(x in 0 until w)bitmap.setPixel(x,y,if(noise)Color.argb(if(alpha)random.nextInt(256) else 255,random.nextInt(256),random.nextInt(256),random.nextInt(256)) else when {
            x<w/2 && y<h/2->Color.argb(if(alpha)128 else 255,255,0,0)
            x>=w/2 && y<h/2->Color.GREEN
            x<w/2->Color.BLUE
            else->Color.WHITE
        })
        val file=File(directory,"original.png");file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        return file to ImageProbe.inspect(file.path)
    }
    fun transparentEdges(directory:File):Pair<File,ImageInfo> {
        directory.mkdirs();val b=Bitmap.createBitmap(101,77,Bitmap.Config.ARGB_8888)
        for(y in 20..55)for(x in 30..70)b.setPixel(x,y,Color.RED)
        val file=File(directory,"edges.png");file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
        return file to ImageProbe.inspect(file.path)
    }
    /** A real JPEG segment carrying TIFF orientation and nested ColorSpace, independent of the product writer. */
    fun orientedJpeg(directory:File,orientation:Int,colorSpace:Int=1):File {
        directory.mkdirs();val b=Bitmap.createBitmap(101,77,Bitmap.Config.ARGB_8888)
        for(y in 0 until 77)for(x in 0 until 101)b.setPixel(x,y,when{ x<50 && y<38->Color.RED;x>=50 && y<38->Color.GREEN;x<50->Color.BLUE;else->Color.WHITE })
        val original=ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,100,original);b.recycle()
        val exif=ByteArrayOutputStream();DataOutputStream(exif).apply {
            write("Exif\u0000\u0000MM".toByteArray());writeShort(42);writeInt(8)
            writeShort(2);writeShort(0x112);writeShort(3);writeInt(1);writeShort(orientation);writeShort(0)
            writeShort(0x8769);writeShort(4);writeInt(1);writeInt(38);writeInt(0)
            writeShort(1);writeShort(0xA001);writeShort(3);writeInt(1);writeShort(colorSpace);writeShort(0);writeInt(0)
        }
        val bytes=original.toByteArray();val file=File(directory,"exif-$orientation-$colorSpace.jpg")
        DataOutputStream(file.outputStream()).use{it.write(bytes,0,2);it.writeShort(0xffe1);it.writeShort(exif.size()+2);it.write(exif.toByteArray());it.write(bytes,2,bytes.size-2)}
        return file
    }
    fun source(context:Context,file:File,info:ImageInfo)=ImageSource(FileProvider.getUriForFile(context,"${context.packageName}.files",file).toString(),file.name,info.hash,info.bytes)
    fun assertCornerPixels(file:File,w:Int,h:Int){val b=BitmapFactory.decodeFile(file.path)?:error("Output did not decode.");try{org.junit.Assert.assertEquals(w,b.width);org.junit.Assert.assertEquals(h,b.height)}finally{b.recycle()}}
}
