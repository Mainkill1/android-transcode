package dev.forma.ffmpeg.image
import dev.forma.core.image.*
import org.junit.Assert.*
import org.junit.Test
class ImageVerifierTest {
    @Test fun opaqueEditedResultNeedsAnOpaqueReferenceAndIdenticalAlphaPlane() {
        val opaque=ImageAlphaFacts(255,"a".repeat(64),1)
        ImageVerifier.requireAlpha(opaque,opaque)
        try{ImageVerifier.requireAlpha(opaque,ImageAlphaFacts(0,"b".repeat(64),1));fail()}catch(e:ImageFailure){assertEquals("OUTPUT_INVALID",e.code)}
        try{ImageVerifier.requireAlpha(opaque,opaque.copy(hash="b".repeat(64)));fail()}catch(e:ImageFailure){assertEquals("OUTPUT_INVALID",e.code)}
    }
    @Test fun emptyWrongFormatWrongSizeAndMetadataAreRejected() {
        val expected=ImageInfo(101,77,ImageFormat.PNG,alpha=ImageAlpha.PRESENT)
        for(actual in listOf(expected.copy(width=100),expected.copy(format=ImageFormat.JPEG),expected.copy(frameCount=null))) {
            try { ImageVerifier.requireFacts(actual,ImageFormat.PNG,ImageSize(101,77),ImageAlpha.PRESENT);fail() }catch(e:ImageFailure){assertEquals("OUTPUT_INVALID",e.code)}
        }
    }
}
