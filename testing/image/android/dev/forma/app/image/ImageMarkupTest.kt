package dev.forma.app.image
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.image.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import android.graphics.BitmapFactory
import java.io.File
class ImageMarkupTest {
    @Test fun unicodeAndRedactionUseSharedFullResolutionPlane()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val info=ImageInfo(101,77,ImageFormat.PNG,alpha=ImageAlpha.PRESENT)
        val d=ImageEditDocument(source=ImageSource("content://one","one","a".repeat(64),100),annotations=listOf(
            ImageAnnotation("text",AnnotationKind.TEXT,text="Hello\nمرحبا é",textSize=12.0),
            ImageAnnotation("redact",AnnotationKind.REDACTION,left=.5,top=.5,right=.9,bottom=.9,color=Rgba(255,0,0,10))))
        val g=ImageGeometry.resolve(info,d,ImageAttempt(0,ImageFormat.PNG,90))
        val dir=File(context.cacheDir,"markup-test").apply { mkdirs() }
        try { val plane=ImageMarkupRenderer().render(d,g,dir);val bitmap=BitmapFactory.decodeFile(plane.path)
            assertEquals(101,bitmap.width);assertEquals(0xffff0000.toInt(),bitmap.getPixel(70,50));bitmap.recycle()
        }finally{dir.deleteRecursively()}
    }
}
