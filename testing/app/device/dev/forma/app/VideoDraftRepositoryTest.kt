package dev.forma.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.video.*
import dev.forma.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VideoDraftRepositoryTest {
    @Test fun committedDraftSurvivesRepositoryRecreation() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"video-drafts-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(app) {override fun getFilesDir():File=root}
        val source=Source("content://media/video/1","sample.mp4",10_000,640,360,1)
        val edit=SourceEdit(source,Trim(500,8000),ClipEffects(crop=CropRect(20,20,200,200),rotation=QuarterTurn.CLOCKWISE))
        try {
            val repo=VideoDraftRepository(context)
            repo.save(VideoDraft(edit,"Crop",3))
            assertEquals(VideoDraft(edit,"Crop",3),
                (VideoDraftRepository(context).load(source.uri) as VideoDraftLoad.Valid).draft)
            assertEquals(setOf(source.uri),repo.references().uris)
        } finally {root.deleteRecursively()}
    }

    @Test fun invalidSavedDraftIsPreservedAndCannotReplaceVerifiedIntent() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"video-draft-corrupt-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(app) {override fun getFilesDir():File=root}
        val source=Source("content://media/video/2","sample.mp4",10_000,640,360,1)
        try {
            val repo=VideoDraftRepository(context)
            repo.save(VideoDraft(SourceEdit(source),"Trim",1))
            val file=File(root,"video-drafts/${repo.key(source.uri)}.json")
            file.writeText("{broken")
            assertTrue(repo.load(source.uri) is VideoDraftLoad.Corrupt)
            assertTrue(repo.references().preserveImports)
            assertThrows(IllegalStateException::class.java) {
                runBlocking {repo.save(VideoDraft(SourceEdit(source),"Crop",2))}
            }
            assertEquals("{broken",file.readText())
        } finally {root.deleteRecursively()}
    }

    @Test fun interruptedAtomicWriteRestoresCommittedDraft() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"video-draft-backup-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(app) {override fun getFilesDir():File=root}
        val source=Source("content://media/video/backup","sample.mp4",10_000,640,360,1)
        try {
            val repo=VideoDraftRepository(context)
            val original=VideoDraft(SourceEdit(source,Trim(100,9000)),"Trim",1)
            repo.save(original)
            val base=File(root,"video-drafts/${repo.key(source.uri)}.json")
            assertTrue(base.renameTo(File("${base.path}.bak")))
            base.writeText("{unfinished")
            assertEquals(original,(repo.load(source.uri) as VideoDraftLoad.Valid).draft)
            assertTrue(source.uri in repo.references().uris)
            repo.discard(source.uri)
            assertTrue(repo.load(source.uri) is VideoDraftLoad.Missing)
        } finally {root.deleteRecursively()}
    }
}
