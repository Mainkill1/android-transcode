package dev.forma.core.image
import dev.forma.core.Capabilities
import org.junit.Assert.*
import org.junit.Test
class ImagePlannerTest {
    private val info=ImageInfo(101,77,ImageFormat.PNG,alpha=ImageAlpha.PRESENT,hash="a".repeat(64),bytes=1)
    private val job=ImageJobSpec("job",ImageEditDocument(source=ImageSource("content://one","odd.png",info.hash,1)))
    private val caps=Capabilities(true,encoders=setOf("png","mjpeg","libwebp","rawvideo"),muxers=setOf("image2","rawvideo"),decoders=setOf("png","mjpeg","webp"),demuxers=setOf("image2","png_pipe","jpeg_pipe","webp_pipe"),pixelFormats=setOf("rgba","gbrap","yuvj444p","yuva420p","yuv420p","bgra","gray"),filters=setOf("alphaextract","format","crop","scale","pad","transpose","hflip","vflip"))
    @Test fun alphaVerificationRequiresItsNativeHelpers() {
        for(broken in listOf(caps.copy(encoders=caps.encoders-"rawvideo"),caps.copy(filters=caps.filters-"alphaextract"),caps.copy(pixelFormats=caps.pixelFormats-"gray"))) {
            try{ImagePlanner.plan(info,job,ImageAttempt(0,ImageFormat.PNG,90),broken);fail("Unverifiable alpha route accepted")}catch(e:ImageFailure){assertEquals("CAPABILITY_UNAVAILABLE",e.code)}
        }
    }
    @Test fun outputMustBeNativelyDecodableForVerification() {
        val opaque=info.copy(alpha=ImageAlpha.OPAQUE)
        try{ImagePlanner.plan(opaque,job,ImageAttempt(0,ImageFormat.JPEG,90),caps.copy(decoders=caps.decoders-"mjpeg"));fail("Unverifiable JPEG accepted")}catch(e:ImageFailure){assertEquals("CAPABILITY_UNAVAILABLE",e.code)}
    }
    @Test fun autoSkipsUnverifiableWebpRoute() {
        assertEquals(ImageFormat.PNG,ImagePlanner.resolveFormat(info,job,caps.copy(decoders=caps.decoders-"webp")))
    }
    @Test fun queuedAutoRetainsItsResolvedChoiceWhenCapabilitiesChange() {
        val frozen=ImageJobSpec("frozen",job.document,info,ImageFormat.PNG)
        assertEquals(ImageFormat.AUTO,frozen.document.output.format)
        assertEquals(ImageFormat.PNG,ImagePlanner.resolveFormat(info,frozen,caps))
        assertEquals("image/png",QueueJobSpec.Image(frozen).mime)
        assertTrue(ImageFitPolicy.candidates(frozen,info.copy(alpha=ImageAlpha.OPAQUE)).all{it.format==ImageFormat.PNG})
    }
    @Test fun oneFrameArgumentsPreserveOddGeometryAndTokenPaths() {
        val plan=ImagePlanner.plan(info,job,ImageAttempt(0,ImageFormat.PNG,90),caps)
        val args=plan.arguments("/private/input with spaces.png","/private/output with spaces.png")
        assertTrue(args.contains("-noautorotate"));assertEquals("1",args[args.indexOf("-frames:v")+1]);assertFalse(args.contains("-t"));assertFalse(args.contains("-r"));assertFalse(args.contains("yuv420p"));assertEquals("/private/output with spaces.png",args.last())
    }
    @Test fun missingNativeEncoderBlocks() { try { ImagePlanner.plan(info,job,ImageAttempt(0,ImageFormat.PNG,90),caps.copy(encoders=emptySet()));fail() }catch(e:ImageFailure) { assertEquals("CAPABILITY_UNAVAILABLE",e.code) } }
    @Test fun jpegRequiresFlattenConsent() { try { ImagePlanner.plan(info,job,ImageAttempt(0,ImageFormat.JPEG,90),caps);fail() }catch(e:ImageFailure) { assertEquals("ALPHA_WOULD_BE_LOST",e.code) } }
    @Test fun transparentPaddingNeedsExplicitJpegFlatten() {
        val opaque=info.copy(alpha=ImageAlpha.OPAQUE)
        val padded=ImageJobSpec("pad",job.document.copy(output=ImageOutputPolicy(format=ImageFormat.JPEG,canvasWidth=200,canvasHeight=200)),opaque)
        try {ImagePlanner.plan(opaque,padded,ImageAttempt(0,ImageFormat.JPEG,90),caps);fail()}catch(e:ImageFailure){assertEquals("ALPHA_WOULD_BE_LOST",e.code)}
    }
    @Test fun opaqueWebpHasNoInventedPaddingAlpha() {
        val opaque=info.copy(alpha=ImageAlpha.OPAQUE)
        assertEquals(ImageAlpha.OPAQUE,ImagePlanner.plan(opaque,job,ImageAttempt(0,ImageFormat.WEBP,80),caps).expectedAlpha)
    }
    @Test fun videoNativePayloadWithoutImageDecoderIsUnavailable() {
        try {ImagePlanner.plan(info,job,ImageAttempt(0,ImageFormat.PNG,90),caps.copy(decoders=emptySet()));fail()}catch(e:ImageFailure){assertEquals("CAPABILITY_UNAVAILABLE",e.code)}
    }
    @Test fun losslessWebpAcceptsRenderedBgraWithoutChromaSubsampling() {
        val lossless=ImageJobSpec("lossless",job.document.copy(output=job.document.output.copy(lossless=true)))
        val args=ImagePlanner.plan(info,lossless,ImageAttempt(0,ImageFormat.WEBP,80),caps).arguments("source","out")
        assertEquals("bgra",args[args.indexOf("-pix_fmt")+1])
    }
    @Test fun alphaReferenceUsesExactlyThePreparedGraphAndBothInputs() {
        val marked=ImageJobSpec("marked",job.document.copy(annotations=listOf(ImageAnnotation("cover",AnnotationKind.REDACTION))))
        val plan=ImagePlanner.plan(info,marked,ImageAttempt(0,ImageFormat.PNG,90,markupPath="plane.png"),caps.copy(filters=caps.filters+setOf("overlay","color")))
        val original=plan.arguments("input.png","candidate.png")
        val alpha=plan.alphaArguments(original,"reference.gray")
        assertEquals(2,alpha.count{it=="-i"});assertTrue(alpha[alpha.indexOf("-filter_complex")+1].contains("[out]alphaextract[alphaReference]"));assertEquals("rawvideo",alpha[alpha.indexOf("-c:v")+1]);assertEquals("reference.gray",alpha.last())
    }
}
