package dev.forma.ffmpeg.image
import dev.forma.core.image.*
import dev.forma.ffmpeg.FfmpegBridge
import java.io.*
import java.util.zip.CRC32
import kotlinx.coroutines.*
data class VerifiedImage(val info:ImageInfo,val bytes:Long,val path:String)
/** Rewrites only metadata containers; never copies a source profile, thumbnail, or project. */
object ImageMetadata {
    private fun exif():ByteArray {
        val b=ByteArrayOutputStream();val o=DataOutputStream(b)
        o.write("Exif\u0000\u0000MM".toByteArray());o.writeShort(42);o.writeInt(8)
        o.writeShort(2);o.writeShort(0x112);o.writeShort(3);o.writeInt(1);o.writeShort(1);o.writeShort(0)
        o.writeShort(0x8769);o.writeShort(4);o.writeInt(1);o.writeInt(38);o.writeInt(0)
        o.writeShort(1);o.writeShort(0xA001);o.writeShort(3);o.writeInt(1);o.writeShort(1);o.writeShort(0);o.writeInt(0)
        return b.toByteArray()
    }
    fun finalize(file:File,format:ImageFormat) {
        val bytes=file.readBytes();val clean=clean(bytes,format);val temp=File(file.parentFile,"metadata-final.tmp")
        temp.outputStream().use { it.write(clean);it.fd.sync() };if(!temp.renameTo(file))throw ImageFailure("OUTPUT_INVALID","Could not finalize image metadata.")
    }
    fun clean(b:ByteArray,format:ImageFormat):ByteArray {
        val output=ByteArrayOutputStream();val o=DataOutputStream(output)
        fun u(i:Int)=b[i].toInt() and 255
        fun be(i:Int)=(u(i).toLong() shl 24)+(u(i+1).toLong() shl 16)+(u(i+2).toLong() shl 8)+u(i+3)
        fun le(i:Int)=u(i)+(u(i+1) shl 8)+(u(i+2) shl 16)+(u(i+3) shl 24)
        when(format){
            ImageFormat.PNG->{
                o.write(b,0,8);var i=8
                fun srgb(){o.writeInt(1);val t="sRGB".toByteArray();o.write(t);o.writeByte(0);val crc=CRC32();crc.update(t);crc.update(0);o.writeInt(crc.value.toInt())}
                while(i<b.size){val n=be(i).toInt();val type=String(b,i+4,4,Charsets.US_ASCII)
                    if(type in setOf("IHDR","PLTE","tRNS","IDAT","IEND"))o.write(b,i,12+n)
                    if(type=="IHDR")srgb();i+=12+n
                }
            }
            ImageFormat.JPEG->{
                o.write(b,0,2);val e=exif();o.writeShort(0xffe1);o.writeShort(e.size+2);o.write(e);var i=2
                while(i<b.size){val start=i;require(u(i)==255);while(u(i)==255)i++;val marker=u(i++);
                    if(marker==218 || marker==217){o.write(b,start,b.size-start);break}
                    val n=(u(i) shl 8)+u(i+1);if(marker !in 225..239 && marker!=254)o.write(b,start,i+n-start);i+=n
                }
            }
            ImageFormat.WEBP->{
                val chunks=ByteArrayOutputStream();var i=12
                while(i<b.size){val n=le(i+4);val type=String(b,i,4,Charsets.US_ASCII)
                    if(type !in setOf("EXIF","XMP ","ICCP")){val c=b.copyOfRange(i,i+8+n+(n and 1));if(type=="VP8X")c[8]=(c[8].toInt() and 44.inv()).toByte();chunks.write(c)};i+=8+n+(n and 1)
                }
                o.write("RIFF".toByteArray());val n=chunks.size()+4;repeat(4){o.writeByte(n ushr (it*8))};o.write("WEBP".toByteArray());o.write(chunks.toByteArray())
            }
            else->throw ImageFailure("OUTPUT_INVALID","Unknown output format.")
        };return output.toByteArray()
    }
    fun requireClean(file:File,format:ImageFormat) {val b=file.readBytes();if(!b.contentEquals(clean(b,format)))throw ImageFailure("OUTPUT_INVALID","Output contains unexpected ancillary metadata.")}
}
class ImageVerifier(private val bridge:FfmpegBridge) {
    companion object {
        fun requireFacts(actual:ImageInfo,format:ImageFormat,size:ImageSize,alpha:ImageAlpha) {
            if(actual.format!=format || actual.width!=size.width || actual.height!=size.height || actual.frameCount!=1 || actual.bitDepth!=8 || actual.orientation!=1 || actual.gainMap || (alpha==ImageAlpha.PRESENT && actual.alpha!=ImageAlpha.PRESENT))throw ImageFailure("OUTPUT_INVALID","Encoded image format, dimensions, alpha or orientation do not match the prepared plan.")
        }
    }
    suspend fun verify(path:String,plan:ImagePlan):VerifiedImage=withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive();val file=File(path)
        if(!file.isFile || file.length()<=0)throw ImageFailure("OUTPUT_INVALID","Encoder produced no image.")
        val actual=try{bridge.inspectImage(path)}catch(c:CancellationException){throw c}catch(e:Exception){throw ImageFailure("OUTPUT_INVALID",e.message?:"Output decode failed.")}
        requireFacts(actual,plan.attempt.format,plan.geometry.outputSize,plan.expectedAlpha)
        ImageMetadata.requireClean(file,plan.attempt.format)
        currentCoroutineContext().ensureActive();VerifiedImage(actual,file.length(),path)
    }
}
