package dev.forma.core.image
import dev.forma.core.Capabilities
import org.junit.Assert.*
import org.junit.Test
class ImagePlannerTest {
    private val info=ImageInfo(101,77,ImageFormat.PNG,alpha=ImageAlpha.PRESENT,hash="a".repeat(64),bytes=1)
    private val job=ImageJobSpec("job",ImageEditDocument(source=ImageSource("content://one","odd.png",info.hash,1)))
    private val caps=Capabilities(true,encoders=setOf("png","mjpeg","libwebp"),muxers=setOf("image2"),filters=setOf("format","crop","scale","pad","transpose","hflip","vflip"))
    @Test fun oneFrameArgumentsPreserveOddGeometryAndTokenPaths() {
        val plan=ImagePlanner.plan(info,job,ImageAttempt(0,ImageFormat.PNG,90),caps)
        val args=plan.arguments("/private/input with spaces.png","/private/output with spaces.png")
        assertTrue(args.contains("-noautorotate"));assertEquals("1",args[args.indexOf("-frames:v")+1]);assertFalse(args.contains("-t"));assertFalse(args.contains("-r"));assertFalse(args.contains("yuv420p"));assertEquals("/private/output with spaces.png",args.last())
    }
    @Test fun missingNativeEncoderBlocks() { try { ImagePlanner.plan(info,job,ImageAttempt(0,ImageFormat.PNG,90),caps.copy(encoders=emptySet()));fail() }catch(e:ImageFailure) { assertEquals("CAPABILITY_UNAVAILABLE",e.code) } }
    @Test fun jpegRequiresFlattenConsent() { try { ImagePlanner.plan(info,job,ImageAttempt(0,ImageFormat.JPEG,90),caps);fail() }catch(e:ImageFailure) { assertEquals("ALPHA_WOULD_BE_LOST",e.code) } }
}
