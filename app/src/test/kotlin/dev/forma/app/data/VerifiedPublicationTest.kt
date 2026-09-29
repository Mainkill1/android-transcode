package dev.forma.app.data

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class VerifiedPublicationTest {
    @Test fun failedDurableCompletionRemovesOnlyItsOwnOutput() = runBlocking {
        val dir=Files.createTempDirectory("publish-failure-").toFile()
        try {
            val source=File(dir,"source").apply { writeText("original") }
            val temporary=File(dir,"candidate").apply { writeText("verified") }
            val published=File(dir,"completed")
            val error=runCatching { publishVerified(temporary,published) { error("Disk full while completing queue") } }.exceptionOrNull()
            assertNotNull(error);assertFalse(published.exists());assertEquals("original",source.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun stopAfterSuccessfulDurableCompletionRetainsCompletedOutput() = runBlocking {
        val dir=Files.createTempDirectory("publish-stop-").toFile()
        try {
            val temporary=File(dir,"candidate").apply { writeText("verified") };val output=File(dir,"completed")
            lateinit var worker:Job
            worker=launch(start=CoroutineStart.LAZY) { publishVerified(temporary,output) { worker.cancel() } }
            worker.start();worker.join();assertTrue(worker.isCancelled);assertEquals("verified",output.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun existingOutputIsNeverReplaced() = runBlocking {
        val dir=Files.createTempDirectory("publish-existing-").toFile()
        try {
            val temporary=File(dir,"candidate").apply { writeText("new") };val output=File(dir,"completed").apply { writeText("existing") }
            assertTrue(runCatching { publishVerified(temporary,output) {} }.isFailure)
            assertEquals("existing",output.readText());assertEquals("new",temporary.readText())
        } finally { dir.deleteRecursively() }
    }
}
