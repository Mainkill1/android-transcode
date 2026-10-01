package dev.forma.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.*
import dev.forma.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QueuePreservationTest {
    @Test fun deliveryUpdateIsDurableAndRejectsStaleReceipt() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(app.cacheDir,"delivery-cas-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=directory }
        try {
            val spec=JobSpec(UUID.randomUUID().toString(),Source("content://original","clip.mp4",3000),Trim(),Settings())
            val queue=QueueRepository(context);queue.load()
            queue.addTagged(listOf(dev.forma.core.image.QueueJobSpec.Av(spec)), mapOf(spec.id to
                Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO),DeliveryReceipt.Waiting)))
            val copying=Delivery(SaveDestination.FormaLibrary(MediaCategory.VIDEO),
                DeliveryReceipt.Copying("clip_forma_12345678.mp4",null))
            assertTrue(queue.updateDelivery(spec.id,DeliveryReceipt.Waiting,copying))
            assertFalse(queue.updateDelivery(spec.id,DeliveryReceipt.Waiting,
                copying.copy(receipt=DeliveryReceipt.Failed("stale",null))))
            val reopened=QueueRepository(context);reopened.load()
            assertEquals(copying,reopened.entries.value.single().delivery)
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedLoadPreservesUnsupportedOrFractionalOriginalBytes() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"queue-preservation-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated=object:ContextWrapper(context) { override fun getFilesDir():File=directory }
        val entry=QueueEntry(JobSpec(UUID.randomUUID().toString(),Source("content://original","original",3000,320,240,1,1),Trim(),Settings(),100000))
        try {
            for(kind in listOf("oldCap","trimIntent","fractional","rootIntent","missingCap","missingTrimEnd")) {
                val root=JSONObject(JobCodec.encode(listOf(entry)));val row=root.getJSONArray("jobs").getJSONObject(0)
                when(kind) {
                    "oldCap" -> root.put("schema",1)
                    "missingCap" -> row.remove("targetBytes")
                    "missingTrimEnd" -> row.getJSONObject("trim").remove("endMs")
                    "trimIntent" -> row.getJSONObject("trim").put("speedPercent",200)
                    "fractional" -> row.getJSONObject("settings").put("fps",30.9)
                    else -> root.put("futureIntent",true)
                }
                val bytes=root.toString().toByteArray();val file=File(directory,"queue-v1.json").apply { writeBytes(bytes) }
                assertTrue(runCatching { QueueRepository(isolated).load() }.isFailure)
                assertArrayEquals(bytes,file.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }
}
