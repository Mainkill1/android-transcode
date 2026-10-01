package dev.forma.core.image
import org.junit.Assert.*
import org.junit.Test
class ImageEffectsTest {
    @Test fun neutralOmitsColorFilters() { assertTrue(ImageEffects.compile(ImageAdjustments()).filters.isEmpty()) }
    @Test fun saturationZeroIsKnownGrayscaleAndAlphaUnchanged() { assertEquals(Rgba(54,54,54,77),ImageEffects.pixel(Rgba(255,0,0,77),ImageAdjustments(saturation=0.0))) }
    @Test fun gammaContrastAndBrightnessMatchIndependentGolden() { assertEquals(Rgba(151,198,236,13),ImageEffects.pixel(Rgba(64,128,192,13),ImageAdjustments(brightness=.1,contrast=1.0,gamma=2.0))) }
    @Test fun detailDeclaresPremultipliedEdgePolicy() { val graph=ImageEffects.compile(ImageAdjustments(blurSigma=2.0));assertEquals("format=gbrapf32le",graph.filters.first());assertTrue(graph.filters[1].startsWith("premultiply="));assertTrue(graph.filters.last().startsWith("unpremultiply=")) }
    @Test fun sharpeningKeepsRgbFloatAndHasExplicitGaussianUnsharpMath() {
        val graph=ImageEffects.compile(ImageAdjustments(sharpenAmount=1.0,sharpenRadius=3))
        assertTrue(graph.requiredFilters.containsAll(setOf("gblur","split","blend")))
        assertFalse("YUV-only unsharp cannot negotiate the RGB plane",graph.requiredFilters.contains("unsharp"))
        assertEquals(3.0,graph.sharpenSigma,0.0)
    }
}
