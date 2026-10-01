package dev.forma.app.data

import dev.forma.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JobCodecTest {
    private val entry = QueueEntry(JobSpec("3d4754f2-f642-4f9c-9cf4-c6db11a77dca",
        Source("content://documents/a", "A ' name.mp4", 10_000, 1920, 1080, 1, 2, false, 123456),
        Trim(500, 9500), Settings(video = VideoEncoder.X265, crf = 19, stereo = false, keepMetadata = true)),
        JobState.QUEUED, "Saved")
    @Test fun roundTripAllSettings() { assertEquals(listOf(entry), JobCodec.decode(JobCodec.encode(listOf(entry)))) }
    @Test fun displayRotationAndUnknownMatrixSurviveQueueSnapshot() {
        for(degrees in listOf(90,180,270,null)) {
            val rotated=entry.copy(spec=entry.spec.copy(source=entry.spec.source.copy(displayRotationDegrees=degrees)))
            assertEquals(rotated,JobCodec.decode(JobCodec.encode(listOf(rotated))).single())
        }
    }
    @Test fun nullableEndTime() { val e = entry.copy(spec = entry.spec.copy(trim = Trim())); assertEquals(listOf(e), JobCodec.decode(JobCodec.encode(listOf(e)))) }
    @Test fun rejectsFutureSchema() { assertThrows(IllegalArgumentException::class.java) { JobCodec.decode("{\"schema\":6,\"jobs\":[]}") } }
    @Test fun rejectsDuplicateIds() { assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(JobCodec.encode(listOf(entry, entry))) } }

    @Test fun newJobsKeepTheirFrozenDestinationAndDeliveryReceipt() {
        val destination = SaveDestination.DocumentTree("content://documents/tree/folder", "Exports")
        val receipts = listOf(
            DeliveryReceipt.Waiting,
            DeliveryReceipt.Copying("A_forma_3d4754f2.mp4", null),
            DeliveryReceipt.Failed("Storage full", "content://documents/document/partial"),
            DeliveryReceipt.Saved("content://documents/document/final", "A_forma_3d4754f2.mp4", 12,
                "a".repeat(64))
        )
        for (receipt in receipts) {
            val queued = entry.copy(delivery = Delivery(destination, receipt))
            assertEquals(queued, JobCodec.decode(JobCodec.encode(listOf(queued))).single())
        }
    }

    @Test fun oldCompletedJobStaysPrivateAfterMigration() {
        val legacy = JSONObject(JobCodec.encode(listOf(entry.copy(state = JobState.COMPLETED))))
        legacy.put("schema", 4)
        legacy.getJSONArray("jobs").getJSONObject(0).remove("delivery")
        val restored = JobCodec.decode(legacy.toString()).single()
        assertEquals(Delivery(null, DeliveryReceipt.PrivateLegacy), restored.delivery)
        assertEquals(restored, JobCodec.decode(JobCodec.encode(listOf(restored))).single())
    }

    @Test fun malformedDestinationNeverSilentlyChangesQueue() {
        val root = JSONObject(JobCodec.encode(listOf(entry.copy(delivery = Delivery(
            SaveDestination.DocumentTree("content://documents/tree/folder", "Exports"), DeliveryReceipt.Waiting)))))
        val row = root.getJSONArray("jobs").getJSONObject(0)
        row.getJSONObject("delivery").getJSONObject("destination").put("uri", "file:///private/path")
        assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(root.toString()) }
        row.getJSONObject("delivery").getJSONObject("destination").put("uri", "content://documents/tree/folder")
        row.getJSONObject("delivery").put("unknown", 1)
        assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(root.toString()) }
    }
}
