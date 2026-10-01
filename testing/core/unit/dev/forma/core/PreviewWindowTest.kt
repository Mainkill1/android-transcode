package dev.forma.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewWindowTest {
    private val source=Source("content://clip","clip.mp4",20_000,640,360,1)

    @Test fun centersAndClampsWithinKeptRange() {
        assertEquals(Trim(7_500,12_500),PreviewWindow.select(SourceEdit(source),10_000))
        assertEquals(Trim(2_000,7_000),PreviewWindow.select(SourceEdit(source,Trim(2_000,9_000)),2_000))
        assertEquals(Trim(4_000,9_000),PreviewWindow.select(SourceEdit(source,Trim(2_000,9_000)),9_000))
    }

    @Test fun shortSelectionAndSpeedKeepRenderedDurationBounded() {
        assertEquals(Trim(2_000,4_000),PreviewWindow.select(SourceEdit(source,Trim(2_000,4_000)),3_000))
        val slow=SourceEdit(source,effects=ClipEffects(speedPercent=50))
        assertEquals(Trim(8_750,11_250),PreviewWindow.select(slow,10_000))
        val fast=SourceEdit(source,effects=ClipEffects(speedPercent=200))
        assertEquals(Trim(5_000,15_000),PreviewWindow.select(fast,10_000))
    }
}
