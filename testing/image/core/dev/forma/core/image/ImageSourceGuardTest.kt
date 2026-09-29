package dev.forma.core.image
import org.junit.Assert.*
import org.junit.Test
class ImageSourceGuardTest {
    @Test fun saveGuardsBothStagedAndOriginalDocumentUris() {
        val s=ImageSource("content://private/source","one","a".repeat(64),100,originalUri="content://documents/original")
        assertTrue(s.isOriginalDestination(s.uri));assertTrue(s.isOriginalDestination(s.originalUri!!));assertFalse(s.isOriginalDestination("content://documents/new-copy"))
    }
}
