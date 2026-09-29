package dev.forma.app.image
import org.junit.Assert.*
import org.junit.Test
import dev.forma.core.image.ImageSize
class ImagePreviewStateTest {
    @Test fun lateRevisionCannotReplaceNewerPreview() {
        val state=ImagePreviewState(revision=2,key="new",status="Updating")
        assertEquals(state,state.accept(1,"old","old.png",false))
        assertEquals("Preview",state.accept(2,"new","new.png",false).status)
        assertEquals("Actual pixels",state.accept(2,"new","new.png",true).status)
    }
    @Test fun updatingRetainsActualPixelRegionWithItsPath() {
        val region=ImagePreviewRegion(100,100,1024,1024)
        val old=ImagePreviewState(1,"old","Actual pixels","full.png",ImageSize(4000,3000),true,region=region)
        val next=old.updating(2,"new")
        assertEquals(region,next.region);assertTrue(next.actualPixels);assertEquals("full.png",next.path);assertEquals("Updating",next.status)
    }
    @Test fun worstCasePngReservePreventsAnOversizedInflightCache() {
        val upper=ImagePreviewBudget.pngUpperBound(ImageSize(2000,2000))
        assertTrue(upper>16_000_000)
        try{ImagePreviewBudget.requireFits(upper,20L*1024*1024);fail()}catch(e:dev.forma.core.image.ImageFailure){assertEquals("RESOURCE_LIMIT",e.code)}
    }
    @Test fun encodedCurrentAndInflightPreviewShareOneBudget() {
        try{ImagePreviewBudget.requireFits(20L*1024*1024,20L*1024*1024);fail()}catch(e:dev.forma.core.image.ImageFailure){assertEquals("RESOURCE_LIMIT",e.code)}
        ImagePreviewBudget.requireFits(12L*1024*1024,20L*1024*1024)
    }
}
