package dev.forma.ffmpeg.image
import dev.forma.core.image.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
class ImageProbeTest {
    private fun png(w:Int=101,h:Int=77,animated:Boolean=false,depth:Int=8):ByteArray {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes);out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        fun chunk(type:String,data:ByteArray) { out.writeInt(data.size);val t=type.toByteArray();out.write(t);out.write(data);val crc=CRC32();crc.update(t);crc.update(data);out.writeInt(crc.value.toInt()) }
        val header=ByteArrayOutputStream();DataOutputStream(header).apply { writeInt(w);writeInt(h);writeByte(depth);writeByte(6);write(byteArrayOf(0,0,0)) }
        chunk("IHDR",header.toByteArray());if(animated)chunk("acTL",ByteArray(8));chunk("IDAT",byteArrayOf(1));chunk("IEND",byteArrayOf());return bytes.toByteArray()
    }
    @Test fun contentHeaderWinsOverExtension() { val info=ImageProbe.inspectBytes(png());assertEquals(ImageFormat.PNG,info.format);assertEquals(101,info.width);assertEquals(ImageAlpha.PRESENT,info.alpha) }
    @Test fun animationAndPrecisionAreRejected() { for(bytes in listOf(png(animated=true),png(depth=16),"GIF89a".toByteArray())) { try { ImageProbe.inspectBytes(bytes);fail() }catch(e:ImageFailure){assertEquals("UNSUPPORTED_IMAGE",e.code)} } }
    @Test fun truncatedAndBadCrcAreRejected() { val bytes=png();for(b in listOf(bytes.copyOf(20),bytes.copyOf().also { it[20]=9 })) { try { ImageProbe.inspectBytes(b);fail() }catch(e:ImageFailure){assertEquals("DECODE_FAILED",e.code)} } }
    @Test fun pixelCeilingUsesLongArithmetic() { for(size in listOf(40_000_001 to 1,Int.MAX_VALUE to Int.MAX_VALUE)) { try { ImageProbe.inspectBytes(png(size.first,size.second));fail() }catch(e:ImageFailure){assertEquals("RESOURCE_LIMIT",e.code)} } }
}
