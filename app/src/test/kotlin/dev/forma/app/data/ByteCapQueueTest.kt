package dev.forma.app.data

import dev.forma.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ByteCapQueueTest {
    private fun entry(target: Long?) = QueueEntry(JobSpec("3d4754f2-f642-4f9c-9cf4-c6db11a77dca",
        Source("content://original", "original", 3000, 320, 240, 1, 1), Trim(),
        Settings(video=VideoEncoder.H264_AUTO,rateControl=RateControl.BITRATE,fps=30), target))
    @Test fun capRoundTripsAsImmutableJobIntent() {
        val job=entry(10000000)
        assertEquals(listOf(job),JobCodec.decode(JobCodec.encode(listOf(job))))
    }
    @Test fun legacyQueueRemainsManualAndStrictIntegerCapRejectsCoercion() {
        val old=JSONObject(JobCodec.encode(listOf(entry(null)))).put("schema",1)
        old.getJSONArray("jobs").getJSONObject(0).remove("targetBytes")
        assertNull(JobCodec.decode(old.toString()).single().spec.targetBytes)
        for (bad in listOf<Any>(true,"10000000",10.5,-1L,0L,2000000001L)) {
            val json=JSONObject(JobCodec.encode(listOf(entry(10000000))))
            json.getJSONArray("jobs").getJSONObject(0).put("targetBytes",bad)
            assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(json.toString()) }
        }
    }
}
