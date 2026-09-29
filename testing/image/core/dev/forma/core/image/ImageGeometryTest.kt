package dev.forma.core.image
import org.junit.Assert.*
import org.junit.Test
class ImageGeometryTest {
    val source=ImageSource("content://one","image.png","a".repeat(64),100)
    fun info(w:Int=1200,h:Int=800,o:Int=1)=ImageInfo(w,h,ImageFormat.PNG,orientation=o,alpha=ImageAlpha.PRESENT)
    fun attempt()=ImageAttempt(0,ImageFormat.PNG,90)
    @Test fun cropTurnAndLongestEdgeMatchReference() {
        val d=ImageEditDocument(source=source,crop=NormalizedCrop(.25,.25,.75,.75),quarterTurns=1,output=ImageOutputPolicy(resizeMode=ResizeMode.LONGEST_EDGE,longestEdge=300))
        assertEquals(ImageSize(200,300),ImageGeometry.resolve(info(),d,attempt()).outputSize)
    }
    @Test fun oddIdentityIsExact() { assertEquals(ImageSize(101,77),ImageGeometry.resolve(info(101,77),ImageEditDocument(source=source),attempt()).outputSize) }
    @Test fun everyOrientationMapsCornersExactlyOnce() {
        val expected=listOf(ImagePoint(0.0,0.0),ImagePoint(1200.0,0.0),ImagePoint(1200.0,800.0),ImagePoint(0.0,800.0),ImagePoint(0.0,0.0),ImagePoint(800.0,0.0),ImagePoint(800.0,1200.0),ImagePoint(0.0,1200.0))
        for(o in 1..8) {
            val g=ImageGeometry.resolve(info(o=o),ImageEditDocument(source=source),attempt())
            assertEquals(expected[o-1],g.encodedToOutput.map(ImagePoint(0.0,0.0)))
            val p=ImagePoint(120.0,50.0);val back=g.outputToSource.map(g.sourceToOutput.map(p));assertEquals(p.x,back.x,1e-8);assertEquals(p.y,back.y,1e-8)
        }
    }
    @Test fun cropFloorsAndCeilsAndPaddingAnchors() {
        val d=ImageEditDocument(source=source,crop=NormalizedCrop(.11,.21,.91,.81),output=ImageOutputPolicy(canvasWidth=200,canvasHeight=200,anchor=CanvasAnchor.BOTTOM_RIGHT))
        val g=ImageGeometry.resolve(info(101,77),d,attempt())
        assertEquals(11,g.cropLeft);assertEquals(16,g.cropTop);assertEquals(81,g.cropWidth);assertEquals(47,g.cropHeight)
        assertEquals(119,g.padX);assertEquals(153,g.padY)
    }
    @Test fun solidRedactionCoversPartialOutputPixelsAfterResize() {
        val d=ImageEditDocument(source=source,output=ImageOutputPolicy(resizeMode=ResizeMode.PIXELS,width=53))
        val g=ImageGeometry.resolve(info(101,77),d,attempt())
        val o=ImageAnnotation("redact",AnnotationKind.REDACTION,left=.13,top=.13,right=.9,bottom=.9)
        assertEquals(ImagePixelRect(6,5,48,36),ImageGeometry.annotationBounds(g,o))
    }
}
