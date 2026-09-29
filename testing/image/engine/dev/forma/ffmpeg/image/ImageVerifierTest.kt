package dev.forma.ffmpeg.image
import dev.forma.core.image.*
import org.junit.Assert.*
import org.junit.Test
class ImageVerifierTest {
    @Test fun emptyWrongFormatWrongSizeAndMetadataAreRejected() {
        val expected=ImageInfo(101,77,ImageFormat.PNG,alpha=ImageAlpha.PRESENT)
        for(actual in listOf(expected.copy(width=100),expected.copy(format=ImageFormat.JPEG),expected.copy(frameCount=null))) {
            try { ImageVerifier.requireFacts(actual,ImageFormat.PNG,ImageSize(101,77),ImageAlpha.PRESENT);fail() }catch(e:ImageFailure){assertEquals("OUTPUT_INVALID",e.code)}
        }
    }
}
