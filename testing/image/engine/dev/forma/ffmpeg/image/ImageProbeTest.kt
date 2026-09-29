package dev.forma.ffmpeg.image
import dev.forma.core.image.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
class ImageProbeTest {
    private fun png(w:Int=101,h:Int=77,animated:Boolean=false,depth:Int=8,gamma:Int?=null,chroma:List<Int>?=null):ByteArray {
        val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes);out.write(byteArrayOf(-119,80,78,71,13,10,26,10))
        fun chunk(type:String,data:ByteArray) { out.writeInt(data.size);val t=type.toByteArray();out.write(t);out.write(data);val crc=CRC32();crc.update(t);crc.update(data);out.writeInt(crc.value.toInt()) }
        val header=ByteArrayOutputStream();DataOutputStream(header).apply { writeInt(w);writeInt(h);writeByte(depth);writeByte(6);write(byteArrayOf(0,0,0)) }
        chunk("IHDR",header.toByteArray());if(animated)chunk("acTL",ByteArray(8))
        gamma?.let{val b=ByteArrayOutputStream();DataOutputStream(b).writeInt(it);chunk("gAMA",b.toByteArray())}
        chroma?.let{val b=ByteArrayOutputStream();DataOutputStream(b).apply{it.forEach(::writeInt)};chunk("cHRM",b.toByteArray())}
        chunk("IDAT",byteArrayOf(1));chunk("IEND",byteArrayOf());return bytes.toByteArray()
    }
    @Test fun contentHeaderWinsOverExtension() { val info=ImageProbe.inspectBytes(png());assertEquals(ImageFormat.PNG,info.format);assertEquals(101,info.width);assertEquals(ImageAlpha.PRESENT,info.alpha) }
    @Test fun animationAndPrecisionAreRejected() { for(bytes in listOf(png(animated=true),png(depth=16),"GIF89a".toByteArray())) { try { ImageProbe.inspectBytes(bytes);fail() }catch(e:ImageFailure){assertEquals("UNSUPPORTED_IMAGE",e.code)} } }
    @Test fun truncatedAndBadCrcAreRejected() { val bytes=png();for(b in listOf(bytes.copyOf(20),bytes.copyOf().also { it[20]=9 })) { try { ImageProbe.inspectBytes(b);fail() }catch(e:ImageFailure){assertEquals("DECODE_FAILED",e.code)} } }
    @Test fun pixelCeilingUsesLongArithmetic() { for(size in listOf(40_000_001 to 1,Int.MAX_VALUE to Int.MAX_VALUE)) { try { ImageProbe.inspectBytes(png(size.first,size.second));fail() }catch(e:ImageFailure){assertEquals("RESOURCE_LIMIT",e.code)} } }
    @Test fun explicitNonSrgbGammaAndChromaticitiesAreBlocked() {
        for(bytes in listOf(png(gamma=100000),png(chroma=List(8){10000}))) {
            try{ImageProbe.inspectBytes(bytes);fail("Tagged non-sRGB cannot become assumed sRGB")}catch(e:ImageFailure){assertEquals("UNSUPPORTED_IMAGE",e.code)}
        }
        assertEquals(ImageProfile.ASSUMED_SRGB,ImageProbe.inspectBytes(png(gamma=45455,chroma=listOf(31270,32900,64000,33000,30000,60000,15000,6000))).profile)
    }
    @Test fun nestedExifColorSpaceIsCheckedInsideItsSegment() {
        fun jpeg(colorSpace:Int,offset:Int=38):ByteArray {
            val bytes=ByteArrayOutputStream();val out=DataOutputStream(bytes)
            out.writeShort(0xffd8)
            val exif=ByteArrayOutputStream();DataOutputStream(exif).apply {
                write("Exif\u0000\u0000MM".toByteArray());writeShort(42);writeInt(8)
                writeShort(2);writeShort(0x112);writeShort(3);writeInt(1);writeShort(6);writeShort(0)
                writeShort(0x8769);writeShort(4);writeInt(1);writeInt(offset);writeInt(0)
                writeShort(1);writeShort(0xA001);writeShort(3);writeInt(1);writeShort(colorSpace);writeShort(0);writeInt(0)
            }
            val e=exif.toByteArray();out.writeShort(0xffe1);out.writeShort(e.size+2);out.write(e)
            out.writeShort(0xffc0);out.writeShort(11);out.writeByte(8);out.writeShort(77);out.writeShort(101);out.writeByte(1);out.write(byteArrayOf(1,0x11,0))
            out.writeShort(0xffd9);return bytes.toByteArray()
        }
        assertEquals(6,ImageProbe.inspectBytes(jpeg(1)).orientation)
        for((bytes,code) in listOf(jpeg(65535) to "UNSUPPORTED_IMAGE",jpeg(1,200) to "DECODE_FAILED")) {
            try {ImageProbe.inspectBytes(bytes);fail("Unsafe EXIF admitted")}catch(e:ImageFailure){assertEquals(code,e.code)}
        }
    }
}
