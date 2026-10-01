package dev.forma.app

import android.net.Uri
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.audio.writeFloatWav
import dev.forma.app.service.DeliveryService
import dev.forma.core.*
import dev.forma.core.image.QueueJobSpec
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DeliveryServiceTest {
    @Test fun retryReconcilesAlreadyPublishedJournaledCopy() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT>=29)
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FormaApplication
        val graph=app.graph
        graph.initialize()
        val id=UUID.randomUUID().toString()
        val name="reconciled_forma_${id.take(8)}.wav"
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","reconciled.wav",4800,audioTracks=1),Trim(),
            Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE)))
        val destination=SaveDestination.FormaLibrary(MediaCategory.AUDIO)
        val privateFile=graph.files.output(spec)
        var publicUri:Uri?=null
        try {
            graph.queue.addTagged(listOf(spec),mapOf(id to Delivery(destination,DeliveryReceipt.Waiting)))
            for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED))
                graph.queue.transition(id,state)
            writeFloatWav(privateFile,FloatArray(4800),48_000)
            publicUri=app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                put(MediaStore.MediaColumns.MIME_TYPE,"audio/wav")
                put(MediaStore.MediaColumns.RELATIVE_PATH,"Music/Forma/")
                put(MediaStore.MediaColumns.IS_PENDING,1)
            })!!
            app.contentResolver.openOutputStream(publicUri)!!.use { it.write(privateFile.readBytes()) }
            assertEquals(1,app.contentResolver.update(publicUri,ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING,0)
            },null,null))
            assertTrue(graph.queue.updateDelivery(id,DeliveryReceipt.Waiting,
                Delivery(destination,DeliveryReceipt.Copying(name,publicUri.toString()))))
            DeliveryService.start(app,id)
            val receipt=withTimeout<DeliveryReceipt.Saved>(15_000) {
                while(true) {
                    val current=graph.queue.entries.value.first { it.spec.id==id }.delivery.receipt
                    if(current is DeliveryReceipt.Saved) return@withTimeout current
                    delay(50)
                }
                error("Unreachable")
            }
            assertEquals(publicUri.toString(),receipt.uri)
            assertArrayEquals(privateFile.readBytes(),app.contentResolver.openInputStream(publicUri)!!.use {it.readBytes()})
        } finally {
            publicUri?.let { app.contentResolver.delete(it,null,null) }
            privateFile.delete()
            graph.queue.pruneCompleted(1,System.currentTimeMillis()+3L*86_400_000)
        }
    }

    @Test fun twoRapidRetriesContinueAfterFirstJobFails() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FormaApplication
        val graph=app.graph
        graph.initialize()
        val id=UUID.randomUUID().toString()
        val missingId=UUID.randomUUID().toString()
        val spec=QueueJobSpec.Av(JobSpec(id,Source("content://source","service.wav",4800,audioTracks=1),Trim(),
            Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE)))
        val missing=spec.copy(job=spec.job.copy(id=missingId))
        val destination=SaveDestination.FormaLibrary(MediaCategory.AUDIO)
        var saved:Uri?=null
        val privateFile=graph.files.output(spec)
        try {
            graph.queue.addTagged(listOf(missing,spec),mapOf(
                missingId to Delivery(destination,DeliveryReceipt.Waiting),
                id to Delivery(destination,DeliveryReceipt.Waiting)))
            for(jobId in listOf(missingId,id))
                for(state in listOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING,JobState.COMPLETED))
                    graph.queue.transition(jobId,state)
            writeFloatWav(privateFile,FloatArray(4800),48_000)
            assertTrue(graph.queue.updateDelivery(missingId,DeliveryReceipt.Waiting,
                Delivery(destination,DeliveryReceipt.Failed("Save interrupted",null))))
            assertTrue(graph.queue.updateDelivery(id,DeliveryReceipt.Waiting,
                Delivery(destination,DeliveryReceipt.Failed("Save interrupted",null))))
            DeliveryService.start(app,missingId)
            DeliveryService.start(app,id)
            val receipt=withTimeout<DeliveryReceipt.Saved>(15_000) {
                while(true) {
                    val current=graph.queue.entries.value.first { it.spec.id==id }.delivery.receipt
                    if(current is DeliveryReceipt.Saved) return@withTimeout current
                    delay(50)
                }
                error("Unreachable")
            }
            saved=Uri.parse(receipt.uri)
            assertArrayEquals(privateFile.readBytes(),app.contentResolver.openInputStream(saved)!!.use {it.readBytes()})
            assertTrue(graph.queue.entries.value.first { it.spec.id==missingId }.delivery.receipt is DeliveryReceipt.Failed)
        } finally {
            saved?.let {app.contentResolver.delete(it,null,null)}
            privateFile.delete()
            graph.queue.pruneCompleted(1,System.currentTimeMillis()+3L*86_400_000)
        }
    }
}
