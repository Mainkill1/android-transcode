package dev.forma.core.image
import org.junit.Assert.*
import org.junit.Test
class ImageFitPolicyTest {
    private val info=ImageInfo(100,77,ImageFormat.PNG,alpha=ImageAlpha.PRESENT)
    private fun job(p:ImageOutputPolicy)=ImageJobSpec("id",ImageEditDocument(source=ImageSource("content://one","one","a".repeat(64),100),output=p))
    @Test fun strictByteBoundary() { assertTrue(ImageFitPolicy.fits(9999999,10000000));assertFalse(ImageFitPolicy.fits(10000000,10000000));assertFalse(ImageFitPolicy.fits(0,null)) }
    @Test fun losslessWithoutResizeHasOnlyOneCandidate() { assertEquals(1,ImageFitPolicy.candidates(job(ImageOutputPolicy(format=ImageFormat.PNG,lossless=true)),info).size) }
    @Test fun lossyLadderIsBoundedAndGeometryIsConsentBound() {
        val noResize=ImageFitPolicy.candidates(job(ImageOutputPolicy(format=ImageFormat.JPEG)),info)
        assertEquals(7,noResize.size);assertTrue(noResize.all { it.scale==1.0 })
        val resize=ImageFitPolicy.candidates(job(ImageOutputPolicy(format=ImageFormat.JPEG,allowResizeToFit=true)),info)
        assertTrue(resize.size<=7);assertEquals(.45,resize.last().scale,0.0)
    }
}
