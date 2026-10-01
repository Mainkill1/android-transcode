package dev.forma.core.image
import org.junit.Assert.*
import org.junit.Test
class ImageMemoryTest {
    @Test fun floatSpatialAndEncodedBuffersAreBudgetedBeforeDecode() {
        try {
            ImageValidation.requireMemory(ImageInfo(1000,1000,ImageFormat.PNG,alpha=ImageAlpha.PRESENT,bytes=8_000_000),ImageSize(1000,1000),48_000_000,false)
            fail("Float processing buffers exceed this budget despite the small source pixel count")
        }catch(e:ImageFailure){assertEquals("RESOURCE_LIMIT",e.code)}
    }
}
