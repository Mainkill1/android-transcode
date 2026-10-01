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
    /** Actual Android WebP bitstream with a deliberately conflicting extended canvas. */
    fun conflictingWebpCanvas(directory:File):File {
        directory.mkdirs()
        val bitmap=Bitmap.createBitmap(64,48,Bitmap.Config.ARGB_8888).apply{setHasAlpha(false);eraseColor(Color.RED)}
        val encoded=ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.WEBP,90,it)}.toByteArray()
        bitmap.recycle()
        check(String(encoded,0,4,Charsets.US_ASCII)=="RIFF" && String(encoded,8,4,Charsets.US_ASCII)=="WEBP")
        var at=12;var frame:ByteArray?=null
        while(at+8<=encoded.size){
            val size=(encoded[at+4].toInt() and 255) or ((encoded[at+5].toInt() and 255) shl 8) or
                ((encoded[at+6].toInt() and 255) shl 16) or ((encoded[at+7].toInt() and 255) shl 24)
            check(size>=0 && at.toLong()+8+size+(size and 1)<=encoded.size)
            val end=at+8+size+(size and 1)
            if(String(encoded,at,4,Charsets.US_ASCII)=="VP8 "){frame=encoded.copyOfRange(at,end);break}
            at=end
        }
        val vp8=checkNotNull(frame){"Android did not encode a lossy VP8 frame"}
        val payload=ByteArrayOutputStream();DataOutputStream(payload).use {out->
            out.write("WEBP".toByteArray());out.write("VP8X".toByteArray());out.write(byteArrayOf(10,0,0,0))
            out.write(ByteArray(10)) // 1 × 1 canvas (minus-one fields are zero)
            out.write(vp8)
        }
        val body=payload.toByteArray();val file=File(directory,"conflicting-canvas.webp")
        DataOutputStream(file.outputStream()).use{out->
            out.write("RIFF".toByteArray());repeat(4){out.writeByte(body.size ushr (8*it))};out.write(body)
        }
        return file
    }
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
    fun sparseAlpha(directory:File):Pair<File,ImageInfo> {
        directory.mkdirs();val b=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE);b.setPixel(0,0,Color.TRANSPARENT)
        val file=File(directory,"sparse.png");file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
        return file to ImageProbe.inspect(file.path)
    }
    fun corruptDeflate(file:File) {
        val bytes=file.readBytes();var at=8
        fun u(i:Int)=bytes[i].toInt() and 255
        while(at<bytes.size){val n=(u(at) shl 24)+(u(at+1) shl 16)+(u(at+2) shl 8)+u(at+3)
            if(String(bytes,at+4,4,Charsets.US_ASCII)=="IDAT"){
                check(n>0);bytes[at+8]=0
                val crc=java.util.zip.CRC32();crc.update(bytes,at+4,n+4)
                repeat(4){bytes[at+8+n+it]=(crc.value ushr (24-it*8)).toByte()};file.writeBytes(bytes);return
            };at+=n+12
        };error("No PNG image data")
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
        // Bitmap compression can add an ICC segment. These fixture pixels were generated
        // in known sRGB; remove that segment before declaring the test's explicit EXIF.
        val raw=original.toByteArray();val stripped=ByteArrayOutputStream();stripped.write(raw,0,2)
        var at=2
        fun u(i:Int)=raw[i].toInt() and 255
        while(at<raw.size){val start=at;check(u(at)==255);while(u(at)==255)at++;val marker=u(at++)
            if(marker==218 || marker==217){stripped.write(raw,start,raw.size-start);break}
            val length=(u(at) shl 8)+u(at+1);check(length>=2 && at+length<=raw.size)
            if(marker!=226)stripped.write(raw,start,at+length-start)
            at+=length
        }
        val bytes=stripped.toByteArray();val file=File(directory,"exif-$orientation-$colorSpace.jpg")
        DataOutputStream(file.outputStream()).use{it.write(bytes,0,2);it.writeShort(0xffe1);it.writeShort(exif.size()+2);it.write(exif.toByteArray());it.write(bytes,2,bytes.size-2)}
        return file
    }
    fun source(context:Context,file:File,info:ImageInfo)=ImageSource(FileProvider.getUriForFile(context,"${context.packageName}.files",file).toString(),file.name,info.hash,info.bytes)
    fun assertCornerPixels(file:File,w:Int,h:Int){val b=BitmapFactory.decodeFile(file.path)?:error("Output did not decode.");try{org.junit.Assert.assertEquals(w,b.width);org.junit.Assert.assertEquals(h,b.height)}finally{b.recycle()}}
}
