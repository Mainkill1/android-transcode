package dev.forma.app.image
import dev.forma.core.image.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.io.File
class ImageDraftTest {
    @Test fun corruptDataAndFutureSchemaArePreserved()=runBlocking {
        val directory=File(System.getProperty("java.io.tmpdir"),"image-draft-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        try {
            val repository=ImageDraftRepository(directory);val hash="a".repeat(64);val raw=File(directory,"$hash.json");raw.writeText("broken")
            assertTrue(repository.load(hash) is ImageDraftLoadResult.Corrupt)
            val doc=ImageEditDocument(source=ImageSource("content://one","one",hash,10))
            assertTrue(repository.save(doc,0) is ImageDraftSaveResult.Preserved)
            assertEquals("broken",raw.readText())
        }finally{directory.deleteRecursively()}
    }
    @Test fun staleSaveCannotReplaceNewerRevision()=runBlocking {
        val directory=File(System.getProperty("java.io.tmpdir"),"image-draft-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        try {
            val repository=ImageDraftRepository(directory);val hash="a".repeat(64);val doc=ImageEditDocument(source=ImageSource("content://one","one",hash,10),revision=2)
            assertTrue(repository.save(doc,2) is ImageDraftSaveResult.Saved)
            assertTrue(repository.save(doc.copy(revision=1),1) is ImageDraftSaveResult.Conflict)
            assertEquals(2L,(repository.load(hash) as ImageDraftLoadResult.Valid).document.revision)
        }finally{directory.deleteRecursively()}
    }
}
