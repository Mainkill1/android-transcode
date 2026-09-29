package dev.forma.ffmpeg.image
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
class ImageSniffTest {
    @Test fun fragmentedProviderReadsStillRecognizeBytes() {
        val bytes=byteArrayOf(-119,80,78,71,13,10,26,10,0,0,0,13)
        val input=object:ByteArrayInputStream(bytes){override fun read(b:ByteArray,off:Int,len:Int)=super.read(b,off,minOf(1,len))}
        assertTrue(ImageSniff.isImage(input))
        assertFalse(ImageSniff.isImage(ByteArrayInputStream("not image".toByteArray())))
    }
}
