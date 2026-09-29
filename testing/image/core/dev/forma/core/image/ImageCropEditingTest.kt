package dev.forma.core.image

import org.junit.Assert.*
import org.junit.Test

class ImageCropEditingTest {
    private val size = ImageSize(200, 100)
    @Test fun ratioDragKeepsOppositeCornerAndPixelAspect() {
        val crop = ImageCropEditing.drag(NormalizedCrop(.2,.2,.8,.8),2,ImagePoint(.8,.9),size,2.0)
        assertEquals(.2,crop.left,1e-8); assertEquals(.2,crop.top,1e-8)
        assertEquals(2.0,(crop.right-crop.left)*200/((crop.bottom-crop.top)*100),1e-8)
        assertEquals(.9,crop.bottom,1e-8)
    }
    @Test fun ratioDragStaysInsideImageAtEveryCorner() {
        for(corner in 0..3) {
            val c=ImageCropEditing.drag(NormalizedCrop(.2,.2,.8,.8),corner,ImagePoint(if(corner in listOf(0,3))-4.0 else 4.0,if(corner<2)-4.0 else 4.0),size,1.0)
            assertTrue(listOf(c.left,c.top,c.right,c.bottom).all{it in 0.0..1.0})
            assertEquals(1.0,(c.right-c.left)*200/((c.bottom-c.top)*100),1e-8)
        }
    }
    @Test fun freeDragChangesOnlyGrabbedCorner() {
        assertEquals(NormalizedCrop(.1,.3,.8,.8),ImageCropEditing.drag(NormalizedCrop(.2,.2,.8,.8),0,ImagePoint(.1,.3),size,null))
    }
    @Test fun lockedHeightEntryBecomesAuthoritative() {
        val p=ImageCropEditing.resizeHeight(ImageOutputPolicy(resizeMode=ResizeMode.PIXELS,width=70,aspectLock=true),40)
        assertNull(p.width);assertEquals(40,p.height)
        assertEquals(70,ImageCropEditing.resizeHeight(p.copy(width=70,aspectLock=false),40).width)
    }
}
