package dev.forma.app.image
import org.junit.Assert.*
import org.junit.Test
class ImagePreviewStateTest {
    @Test fun lateRevisionCannotReplaceNewerPreview() {
        val state=ImagePreviewState(revision=2,key="new",status="Updating")
        assertEquals(state,state.accept(1,"old","old.png",false))
        assertEquals("Preview",state.accept(2,"new","new.png",false).status)
        assertEquals("Actual pixels",state.accept(2,"new","new.png",true).status)
    }
}
