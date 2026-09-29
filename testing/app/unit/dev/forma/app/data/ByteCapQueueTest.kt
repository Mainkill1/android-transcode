package dev.forma.app.data

import dev.forma.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ByteCapQueueTest {
    private fun entry(target: Long?) = QueueEntry(JobSpec("3d4754f2-f642-4f9c-9cf4-c6db11a77dca",
        Source("content://original", "original", 3000, 320, 240, 1, 1), Trim(),
        Settings(video=VideoEncoder.H264_AUTO,rateControl=RateControl.BITRATE,fps=30), target))
    private fun runtimeLegacy(schema: Int, target: Long?): JSONObject =
        JSONObject(JobCodec.encode(listOf(entry(target)))).put("schema", schema).also {
            it.getJSONArray("jobs").getJSONObject(0).apply {
                remove("kind"); remove("preferences"); remove("completedAtMs"); remove("sequence")
                getJSONObject("source").remove("audioStreams")
                getJSONObject("settings").remove("audioEdit"); getJSONObject("settings").remove("effects")
                if(schema == 1) remove("targetBytes")
            }
        }
    @Test fun capRoundTripsAsImmutableJobIntent() {
        val job=entry(10000000)
        assertEquals(listOf(job),JobCodec.decode(JobCodec.encode(listOf(job))))
    }
    @Test fun legacyQueueRemainsManualAndStrictIntegerCapRejectsCoercion() {
        val old=runtimeLegacy(1,null)
        assertNull(JobCodec.decode(old.toString()).single().spec.targetBytes)
        for (bad in listOf<Any>(true,"10000000",10.5,-1L,0L,2000000001L)) {
            val json=JSONObject(JobCodec.encode(listOf(entry(10000000))))
            json.getJSONArray("jobs").getJSONObject(0).put("targetBytes",bad)
            assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(json.toString()) }
        }
    }    @Test fun schemaOneCapAndUnknownNestedIntentAreRejectedInsteadOfLost() {
        val cap=runtimeLegacy(1,null)
        cap.getJSONArray("jobs").getJSONObject(0).put("targetBytes",10000000)
        assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(cap.toString()) }
        for (path in listOf("root","source","trim")) {
            val json=JSONObject(JobCodec.encode(listOf(entry(10000000))))
            val row=json.getJSONArray("jobs").getJSONObject(0)
            val target=if(path=="root")json else row.getJSONObject(path)
            target.put("futureIntent",200)
            assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(json.toString()) }
        }
    }
    @Test fun fractionalOutOfRangeOrStringIntegersAndStringBooleansAreRejected() {
        for ((part,key) in listOf("settings" to "fps","trim" to "startMs","source" to "width")) {
            for(value in listOf<Any>(12.9,"12",true) + if(part=="trim") emptyList() else listOf(2147483648L)) {
                val json=JSONObject(JobCodec.encode(listOf(entry(10000000))))
                json.getJSONArray("jobs").getJSONObject(0).getJSONObject(part).put(key,value)
                assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(json.toString()) }
            }
        }
        val json=JSONObject(JobCodec.encode(listOf(entry(10000000))))
        json.getJSONArray("jobs").getJSONObject(0).getJSONObject("source").put("hdr","true")
        assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(json.toString()) }
    }
    @Test fun missingSchemaTwoCapIntentIsRejected() {
        val json=runtimeLegacy(2,10000000)
        json.getJSONArray("jobs").getJSONObject(0).remove("targetBytes")
        assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(json.toString()) }
    }

    @Test fun runtimeSchemaTwoPreservesAutomaticChoiceAndCapWithoutInventingAudioEffects() {
        for(target in listOf(null,10000000L)) {
            val restored=JobCodec.decode(runtimeLegacy(2,target).toString()).single()
            assertEquals(target,restored.spec.targetBytes)
            assertEquals(VideoEncoder.H264_AUTO,restored.spec.settings.video)
            assertTrue(restored.spec.preferences.legacySnapshot)
            assertEquals(dev.forma.core.audio.AudioEdit(),restored.spec.settings.audioEdit)
            assertEquals(listOf(restored),JobCodec.decode(JobCodec.encode(listOf(restored))))
        }
    }

    @Test fun currentCapIsRequiredAndCannotBeMixedIntoLegacyAudioEnvelope() {
        val modern=JSONObject(JobCodec.encode(listOf(entry(10000000))))
        modern.getJSONArray("jobs").getJSONObject(0).remove("targetBytes")
        assertTrue(runCatching { JobCodec.decode(modern.toString()) }.isFailure)
        val hybrid=runtimeLegacy(2,10000000)
        hybrid.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings")
            .put("audioEdit",AudioEditCodec.encode(dev.forma.core.audio.AudioEdit()))
        assertTrue(runCatching { JobCodec.decode(hybrid.toString()) }.isFailure)
        val mixed=runtimeLegacy(2,10000000)
        val audio=JSONObject(JobCodec.encode(listOf(entry(null)))).getJSONArray("jobs").getJSONObject(0)
        audio.put("id","4d4754f2-f642-4f9c-9cf4-c6db11a77dca")
        for(field in listOf("kind","preferences","completedAtMs","targetBytes","sequence")) audio.remove(field)
        audio.getJSONObject("settings").remove("effects")
        assertEquals(1,JobCodec.decode(JSONObject().put("schema",2).put("jobs",org.json.JSONArray().put(audio)).toString()).size)
        mixed.getJSONArray("jobs").put(audio)
        assertTrue(runCatching { JobCodec.decode(mixed.toString()) }.isFailure)
    }
    @Test fun missingTrimEndIntentIsRejected() {
        val json=JSONObject(JobCodec.encode(listOf(entry(10000000))))
        json.getJSONArray("jobs").getJSONObject(0).getJSONObject("trim").remove("endMs")
        assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(json.toString()) }
    }

}
