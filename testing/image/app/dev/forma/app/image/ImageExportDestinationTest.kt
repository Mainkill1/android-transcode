package dev.forma.app.data
import org.junit.Assert.*
import org.junit.Test
class ImageExportDestinationTest {
    @Test fun importedOriginalAndWritableAliasMustNeverReachTruncation() {
        for(uri in listOf("content://sender/original","content://sender/alias"))assertTrue(runCatching{requireEmptyExportDestination("content://forma/private-copy",uri,137)}.isFailure)
    }
    @Test fun unreadableIsNotEvidenceOfAnEmptyDestination() {
        assertTrue(runCatching{requireEmptyExportDestination("content://forma/private-copy","content://sender/unreadable",null)}.isFailure)
    }
    @Test fun genuineEmptyCopyIsAcceptedAndPrivateOriginalStillRejected() {
        requireEmptyExportDestination("content://forma/private-copy","content://sender/empty",-1)
        assertTrue(runCatching{requireEmptyExportDestination("content://forma/private-copy","content://forma/private-copy",-1)}.isFailure)
    }
}
