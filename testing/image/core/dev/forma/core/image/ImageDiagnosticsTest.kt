package dev.forma.core.image
import org.junit.Assert.*
import org.junit.Test
class ImageDiagnosticsTest {
    @Test fun requestedAndEffectiveValuesRemainVisibleAfterFit() {
        val d=ImageExportDiagnostics(ImageFormat.AUTO,ImageFormat.JPEG,ImageSize(101,77),ImageSize(85,65),90,81,3,31999,ImageAlpha.OPAQUE,"ffmpeg 9 source build")
        val text=d.summary()
        for(part in listOf("AUTO","JPEG","101 × 77","85 × 65","90","81","3","31999","ffmpeg 9"))assertTrue(part,text.contains(part))
    }
}
