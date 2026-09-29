package dev.forma.app.data
import dev.forma.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class StrictAudioQueueTest {
    private fun queueRoot():JSONObject=JSONObject(JobCodec.encode(listOf(QueueEntry(JobSpec(
        "a66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4",Source("content://a/b","a",5000,320,240,1,1,
            audioStreams=listOf(dev.forma.core.audio.SourceAudioFacts(1,48000,2,durationUs=5000000))),Trim(),Settings(fps=30))))))
    private fun rejects(change:(JSONObject)->Unit) {
        val root=queueRoot();change(root)
        assertTrue("Unsafe saved queue was accepted: $root",runCatching { JobCodec.decode(root.toString()) }.isFailure)
    }
    @Test fun malformedIntegersCannotChangeQueuedMediaIntent() {
        for(value in listOf<Any>(4294967326L,30.5,"30")) rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").put("fps",value) }
        rejects { it.put("schema",2.5) }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("source").getJSONArray("audioStreams").getJSONObject(0).put("sampleRateHz",4295015296L) }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").getJSONObject("output").put("maxBytes",1000000.5) }
    }
    @Test fun unknownQueueFieldsArePreservedByRejectingRatherThanRewriting() {
        rejects { it.put("future","retained") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).put("future","retained") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("source").put("future","retained") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("trim").put("future","retained") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").put("targetBytes",1000000) }
    }
    @Test fun requiredNullableWriterFieldsCannotDisappearOrLoseType() {
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("trim").remove("endMs") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").getJSONObject("output").remove("maxBytes") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("source").put("hdr","false") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").getJSONObject("output").getJSONObject("normalization").remove("mode") }
        assertEquals(null,JobCodec.decode(queueRoot().toString()).single().spec.trim.endMs)
    }
}
