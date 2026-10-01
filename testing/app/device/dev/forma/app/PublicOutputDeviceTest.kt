package dev.forma.app

import android.content.ContextWrapper
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.*
import dev.forma.app.settings.TreeGrantStore
import dev.forma.core.*
import dev.forma.core.image.QueueJobSpec
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PublicOutputDeviceTest {
    @Test fun restartAdoptsItsJournaledTreeDocument() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT in 26..28)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val treeUri=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies")
        app.contentResolver.takePersistableUriPermission(treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val root=File(app.cacheDir,"tree-recovery-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","recovery.mp4",1000,videoTracks=1),Trim(),Settings()))
        val name="recovery_forma_${id.take(8)}.mp4"
        val destination=TreeGrantStore(context).choose(treeUri.toString(),"Movies")
        val parent=android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri,
            android.provider.DocumentsContract.getTreeDocumentId(treeUri))
        val existing=android.provider.DocumentsContract.createDocument(app.contentResolver,parent,"video/mp4",name)!!
        val queue=QueueRepository(context)
        try {
            app.contentResolver.openOutputStream(existing)!!.use {it.write(byteArrayOf(1))}
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(destination,DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val files=MediaFiles(context);files.output(spec).writeBytes(byteArrayOf(2,3,4))
            assertTrue(queue.updateDelivery(id,DeliveryReceipt.Waiting,Delivery(destination,DeliveryReceipt.Copying(name,null))))
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            assertEquals(existing.toString(),(queue.entries.value.single().delivery.receipt as DeliveryReceipt.Saved).uri)
            assertArrayEquals(byteArrayOf(2,3,4),app.contentResolver.openInputStream(existing)!!.use {it.readBytes()})
        } finally {runCatching {android.provider.DocumentsContract.deleteDocument(app.contentResolver,existing)};root.deleteRecursively()}
    }

    @Test fun corruptPublicReadbackIsRejectedAndRemoved() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"corrupt-copy-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","corrupt.mp4",1000,videoTracks=1),Trim(),Settings()))
        val queue=QueueRepository(context)
        var partial:Uri?=null
        try {
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO),DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val files=MediaFiles(context);files.output(spec).writeBytes(ByteArray(128) {it.toByte()})
            val worker=DeliveryWorker(queue,files,PublicOutputPublisher(context,queue) {uri,_ ->
                partial=uri
                app.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(byteArrayOf(9,8,7))}
            })
            worker.resumePending()
            assertEquals(JobState.COMPLETED,queue.entries.value.single().state)
            val failed=queue.entries.value.single().delivery.receipt as DeliveryReceipt.Failed
            assertTrue(failed.message.contains("did not match"))
            assertTrue(files.output(spec).isFile)
            val present=app.contentResolver.query(partial!!,arrayOf(MediaStore.MediaColumns._ID),null,null,null)?.use {it.moveToFirst()} ?: false
            assertFalse(present)
        } finally {partial?.let {runCatching {app.contentResolver.delete(it,null,null)}};root.deleteRecursively()}
    }

    @Test fun cancellingPublicCopyRemovesOnlyItsPendingItem() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"cancel-copy-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","cancel.mp4",1000,videoTracks=1),Trim(),Settings()))
        val queue=QueueRepository(context)
        try {
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO),DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val files=MediaFiles(context);files.output(spec).writeBytes(ByteArray(128*1024) {it.toByte()})
            val copied=CompletableDeferred<Uri>()
            val worker=DeliveryWorker(queue,files,PublicOutputPublisher(context,queue) {uri,_ ->
                copied.complete(uri);awaitCancellation()
            })
            val job=launch {worker.resumePending()}
            val partial=copied.await()
            job.cancelAndJoin()
            assertEquals(JobState.COMPLETED,queue.entries.value.single().state)
            assertTrue(queue.entries.value.single().delivery.receipt is DeliveryReceipt.Failed)
            assertTrue(files.output(spec).isFile)
            val present=app.contentResolver.query(partial,arrayOf(MediaStore.MediaColumns._ID),null,null,null)?.use {it.moveToFirst()} ?: false
            assertFalse(present)
        } finally {root.deleteRecursively()}
    }

    @Test fun lostTreeGrantLeavesVerifiedOutputAndRetryState() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"lost-tree-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","lost.mp4",1000,videoTracks=1),Trim(),Settings()))
        val destination=SaveDestination.DocumentTree("content://com.android.externalstorage.documents/tree/missing%3A$id","Lost folder")
        val queue=QueueRepository(context)
        try {
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(destination,DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val files=MediaFiles(context);files.output(spec).writeBytes(byteArrayOf(3,2,1))
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            assertEquals(JobState.COMPLETED,queue.entries.value.single().state)
            val failed=queue.entries.value.single().delivery.receipt as DeliveryReceipt.Failed
            assertTrue(failed.message.contains("Choose it again"))
            assertArrayEquals(byteArrayOf(3,2,1),files.output(spec).readBytes())
        } finally {root.deleteRecursively()}
    }

    @Test fun documentTreeCopyUsesProviderReturnedName() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT in 26..28)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val treeUri=Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMovies")
        app.contentResolver.takePersistableUriPermission(treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val root=File(app.cacheDir,"tree-output-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","tree.mp4",1000,videoTracks=1),Trim(),Settings()))
        val tree=TreeGrantStore(context).choose(treeUri.toString(),"Movies")
        val queue=QueueRepository(context)
        var saved:Uri?=null
        try {
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(tree,DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val bytes=ByteArray(512) {it.toByte()};val files=MediaFiles(context);files.output(spec).writeBytes(bytes)
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            val receipt=queue.entries.value.single().delivery.receipt
            assertTrue("receipt=$receipt",receipt is DeliveryReceipt.Saved)
            val result=receipt as DeliveryReceipt.Saved
            saved=Uri.parse(result.uri)
            assertArrayEquals(bytes,app.contentResolver.openInputStream(saved)!!.use {it.readBytes()})
            app.contentResolver.query(saved,arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)!!.use { c ->
                assertTrue(c.moveToFirst());assertEquals(c.getString(0),result.displayName)
            }
        } finally { saved?.let {android.provider.DocumentsContract.deleteDocument(app.contentResolver,it)};root.deleteRecursively() }
    }

    @Test fun android28PublicFolderRequiresPermissionAndCanRetry() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT in 26..28)
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"legacy-output-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","legacy.mp4",1000,videoTracks=1),Trim(),Settings()))
        val queue=QueueRepository(context)
        var uri:Uri?=null
        try {
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO),DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val bytes=ByteArray(512) {it.toByte()}
            val files=MediaFiles(context);files.output(spec).writeBytes(bytes)
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            assertEquals(JobState.COMPLETED,queue.entries.value.single().state)
            val receipt=queue.entries.value.single().delivery.receipt
            if(app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED ||
                app.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
                assertTrue(receipt is DeliveryReceipt.Failed)
                assertTrue((receipt as DeliveryReceipt.Failed).message.contains("storage access"))
                assertArrayEquals(bytes,files.output(spec).readBytes())
            } else {
                assertTrue("receipt=$receipt",receipt is DeliveryReceipt.Saved)
                val saved=receipt as DeliveryReceipt.Saved
                uri=Uri.parse(saved.uri)
                assertArrayEquals(bytes,app.contentResolver.openInputStream(uri)!!.use {it.readBytes()})
                assertTrue(File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
                    "Forma/${saved.displayName}").isFile)
            }
        } finally {uri?.let {app.contentResolver.delete(it,null,null)};root.deleteRecursively()}
    }

    @Test fun publishedFilenameCollisionKeepsOriginalAndUsesANewName() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"collision-output-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","collision.mp4",1000,videoTracks=1),Trim(),Settings()))
        val base="collision_forma_${id.take(8)}.mp4"
        val destination=SaveDestination.FormaLibrary(MediaCategory.VIDEO)
        val queue=QueueRepository(context)
        val existing=app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,base)
            put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/Forma/")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        var saved:Uri?=null
        try {
            app.contentResolver.openOutputStream(existing)!!.use {it.write(byteArrayOf(1,2,3))}
            app.contentResolver.update(existing,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(destination,DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val files=MediaFiles(context);files.output(spec).writeBytes(byteArrayOf(4,5,6))
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            val receipt=queue.entries.value.single().delivery.receipt as DeliveryReceipt.Saved
            saved=Uri.parse(receipt.uri)
            assertNotEquals(base,receipt.displayName)
            assertArrayEquals(byteArrayOf(1,2,3),app.contentResolver.openInputStream(existing)!!.use {it.readBytes()})
            assertArrayEquals(byteArrayOf(4,5,6),app.contentResolver.openInputStream(saved)!!.use {it.readBytes()})
        } finally { saved?.let {app.contentResolver.delete(it,null,null)};app.contentResolver.delete(existing,null,null);root.deleteRecursively() }
    }

    @Test fun missingPrivateOutputIsRetryableWithoutPublicArtifact() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"missing-output-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","missing.mp4",1000,videoTracks=1),Trim(),Settings()))
        val queue=QueueRepository(context)
        try {
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO),DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            DeliveryWorker(queue,MediaFiles(context),PublicOutputPublisher(context,queue)).resumePending()
            assertEquals(JobState.COMPLETED,queue.entries.value.single().state)
            assertTrue(queue.entries.value.single().delivery.receipt is DeliveryReceipt.Failed)
        } finally {root.deleteRecursively()}
    }

    @Test fun restartRecordsAnAlreadyPublishedMatchingCopy() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"published-output-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","published.mp4",1000,videoTracks=1),Trim(),Settings()))
        val name="published_forma_${id.take(8)}.mp4"
        val destination=SaveDestination.FormaLibrary(MediaCategory.VIDEO)
        val queue=QueueRepository(context)
        val existing=app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/Forma/")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        try {
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(destination,DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val bytes=ByteArray(1024) { it.toByte() }
            val files=MediaFiles(context);files.output(spec).writeBytes(bytes)
            app.contentResolver.openOutputStream(existing)!!.use { it.write(bytes) }
            app.contentResolver.update(existing,ContentValues().apply {put(MediaStore.MediaColumns.IS_PENDING,0)},null,null)
            assertTrue(queue.updateDelivery(id,DeliveryReceipt.Waiting,Delivery(destination,DeliveryReceipt.Copying(name,existing.toString()))))
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            assertEquals(existing.toString(),(queue.entries.value.single().delivery.receipt as DeliveryReceipt.Saved).uri)
        } finally {app.contentResolver.delete(existing,null,null);root.deleteRecursively()}
    }

    @Test fun aForeignJournalUriIsNeverModifiedOrDeleted() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"foreign-output-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","foreign.mp4",1000,videoTracks=1),Trim(),Settings()))
        val destination=SaveDestination.FormaLibrary(MediaCategory.VIDEO)
        val queue=QueueRepository(context)
        val foreign=app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,"foreign-${UUID.randomUUID()}.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/Forma/")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        try {
            app.contentResolver.openOutputStream(foreign)!!.use { it.write(byteArrayOf(1,2,3)) }
            queue.load();queue.addTagged(listOf(spec),mapOf(id to Delivery(destination,DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val files=MediaFiles(context); files.output(spec).writeBytes(byteArrayOf(4,5,6))
            assertTrue(queue.updateDelivery(id,DeliveryReceipt.Waiting,Delivery(destination,DeliveryReceipt.Copying("expected_forma_${id.take(8)}.mp4",foreign.toString()))))
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            assertArrayEquals(byteArrayOf(1,2,3),app.contentResolver.openInputStream(foreign)!!.use { it.readBytes() })
            assertTrue(queue.entries.value.single().delivery.receipt is DeliveryReceipt.Failed)
        } finally { app.contentResolver.delete(foreign,null,null);root.deleteRecursively() }
    }

    @Test fun restartAdoptsItsOwnPendingMediaInsteadOfCreatingDuplicate() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"pending-output-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","recovery.mp4",1000,videoTracks=1),Trim(),Settings()))
        val name="recovery_forma_${id.take(8)}.mp4"
        val destination=SaveDestination.FormaLibrary(MediaCategory.VIDEO)
        val queue=QueueRepository(context)
        val existing=app.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/Forma/")
            put(MediaStore.MediaColumns.IS_PENDING,1)
        })!!
        var resulting:Uri?=null
        try {
            queue.load()
            queue.addTagged(listOf(spec),mapOf(id to Delivery(destination,DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val bytes=ByteArray(512) { it.toByte() }
            val files=MediaFiles(context)
            files.output(spec).writeBytes(bytes)
            assertTrue(queue.updateDelivery(id,DeliveryReceipt.Waiting,Delivery(destination,DeliveryReceipt.Copying(name,null))))
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            resulting=Uri.parse((queue.entries.value.single().delivery.receipt as DeliveryReceipt.Saved).uri)
            assertEquals(existing,resulting)
            assertArrayEquals(bytes,app.contentResolver.openInputStream(existing)!!.use { it.readBytes() })
        } finally {
            resulting?.let { app.contentResolver.delete(it,null,null) }
            if(resulting!=existing) app.contentResolver.delete(existing,null,null)
            root.deleteRecursively()
        }
    }

    @Test fun verifiedVideoCopyAppearsInMoviesFormaAndSurvivesQueueRestart() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"public-output-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val id=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","example.mp4",1000,videoTracks=1),Trim(),Settings()))
        val queue=QueueRepository(context)
        var publicUri:Uri?=null
        try {
            queue.load()
            queue.addTagged(listOf(spec),mapOf(id to Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO),DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED)) queue.transition(id,state)
            val files=MediaFiles(context)
            val bytes=ByteArray(4096) { (it % 251).toByte() }
            files.output(spec).writeBytes(bytes)
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            val saved=queue.entries.value.single().delivery.receipt as DeliveryReceipt.Saved
            publicUri=Uri.parse(saved.uri)
            assertEquals(bytes.size.toLong(),saved.bytes)
            assertEquals("d67c656e01756650d77717b0839985a056ec28ffe174601d690fc407a2ceffca",saved.digest)
            assertArrayEquals(bytes,app.contentResolver.openInputStream(publicUri)!!.use { it.readBytes() })
            app.contentResolver.query(publicUri,arrayOf(MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.IS_PENDING),null,null,null)!!.use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("Movies/Forma/",c.getString(0))
                assertEquals(0,c.getInt(1))
            }
            val reopened=QueueRepository(context);reopened.load()
            assertEquals(saved,reopened.entries.value.single().delivery.receipt)
        } finally {
            publicUri?.let { app.contentResolver.delete(it,null,null) }
            root.deleteRecursively()
        }
    }
}
