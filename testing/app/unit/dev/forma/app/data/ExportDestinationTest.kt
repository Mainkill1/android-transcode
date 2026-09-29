package dev.forma.app.data
import org.junit.Assert.*
import org.junit.Test
class ExportDestinationTest {
    @Test fun privateImportIdentityCannotAllowReplacingIncomingOriginalOrAlias() {
        for(destination in listOf("content://sender/original","content://sender/alias"))
            assertTrue(runCatching { requireEmptyExportDestination("content://forma/private-copy",destination,82) }.isFailure)
    }
    @Test fun unreadableDestinationCannotBeAssumedEmpty() {
        assertTrue(runCatching { requireEmptyExportDestination("content://forma/private-copy","content://sender/new",null) }.isFailure)
    }
    @Test fun newEmptyDestinationWorksAndOriginalIdentityAlwaysFails() {
        requireEmptyExportDestination("content://forma/source","content://sender/new",-1)
        assertTrue(runCatching { requireEmptyExportDestination("content://forma/source","content://forma/source",-1) }.isFailure)
    }
}
