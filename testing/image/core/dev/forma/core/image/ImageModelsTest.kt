package dev.forma.core.image
import org.junit.Assert.*
import org.junit.Test
class ImageModelsTest {
    private fun document() = ImageEditDocument(source=ImageSource("content://test", "still.png", "a".repeat(64), 10))
    @Test fun fullCropAndNeutralValuesValidate() { assertTrue(ImageValidation.validate(document()).isEmpty()) }
    @Test fun malformedValuesAreBlocked() {
        for (d in listOf(document().copy(quarterTurns=4), document().copy(crop=NormalizedCrop(0.5,0.0,0.5,1.0)), document().copy(adjustments=ImageAdjustments(gamma=Double.NaN)), document().copy(output=ImageOutputPolicy(targetBytes=31999)))) assertTrue(ImageValidation.validate(d).isNotEmpty())
    }
    @Test fun queuedListsAreFrozen() {
        val objects=mutableListOf(ImageAnnotation("one", AnnotationKind.TEXT, text="hello"))
        val job=ImageJobSpec("job",document().copy(annotations=objects))
        objects.clear();assertEquals(1,job.document.annotations.size)
    }
    @Test fun historyRestoresRevisionAndEvictsOldest() {
        var h=ImageHistory(document())
        repeat(101) { h=h.apply(document().copy(revision=(it+1).toLong())) }
        assertEquals(100,h.past.size)
        assertEquals(100L,h.undo().current.revision)
        assertEquals(101L,h.undo().redo().current.revision)
    }
    @Test fun historyEnforcesByteBudget() {
        var h=ImageHistory(document(), byteBudget=1000)
        repeat(100) { h=h.apply(document().copy(revision=(it+1).toLong())) }
        assertTrue(h.estimatedBytes<=1000)
    }
    @Test fun linesAllowHorizontalVerticalAndReversedEndpoints() {
        for(o in listOf(ImageAnnotation("line",AnnotationKind.LINE,left=.8,top=.5,right=.2,bottom=.5),ImageAnnotation("line",AnnotationKind.ARROW,left=.5,top=.8,right=.5,bottom=.2)))assertTrue(ImageValidation.validate(document().copy(annotations=listOf(o))).isEmpty())
        assertTrue(ImageValidation.validate(document().copy(annotations=listOf(ImageAnnotation("point",AnnotationKind.LINE,left=.5,top=.5,right=.5,bottom=.5)))).isNotEmpty())
    }
}
