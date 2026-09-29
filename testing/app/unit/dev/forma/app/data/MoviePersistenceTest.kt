package dev.forma.app.data

import dev.forma.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MoviePersistenceTest {
    private val source = Source("content://movie/a", "a.mp4", 6000, 640, 360, 1, 1)
    private val clips = listOf(TimelineClip("a", source, Trim(100, 5000), Settings(effects = ClipEffects(volumePercent = 75))),
        TimelineClip("b", source.copy(uri = "content://movie/b"), Trim(1000, 5000), Settings(audio = AudioEncoder.NONE)))
    private val entry = QueueEntry(MovieProject("Movie", SequenceSpec(EditTimeline(clips), CanvasSpec(360, 640, 25, CanvasFit.FILL, "123456"), 400),
        targetBytes = 25_000_000).toJob("00000000-0000-0000-0000-000000000001"))
    @Test fun movieAndEveryClipSettingSurviveRestart() {
        assertEquals(listOf(entry), JobCodec.decode(JobCodec.encode(listOf(entry))))
        assertEquals(3, JSONObject(JobCodec.encode(listOf(entry))).getInt("schema"))
    }
    @Test fun legacySchemaTwoRemainsSingleClipWithManualBudget() {
        val root = JSONObject(JobCodec.encode(listOf(entry))).put("schema", 2)
        val job = root.getJSONArray("jobs").getJSONObject(0)
        job.remove("sequence"); job.remove("targetBytes")
        val saved = JobCodec.decode(root.toString()).single().spec
        assertNull(saved.sequence); assertNull(saved.targetBytes)
    }
    @Test fun malformedMovieNeverFallsBackToItsFirstSource() {
        val root = JSONObject(JobCodec.encode(listOf(entry)))
        root.getJSONArray("jobs").getJSONObject(0).getJSONObject("sequence").put("transitionMs", "400")
        assertThrows(Exception::class.java) { JobCodec.decode(root.toString()) }
    }
    @Test fun equalityByteCapAndUnknownMovieFieldsAreRejected() {
        val root = JSONObject(JobCodec.encode(listOf(entry)))
        val job = root.getJSONArray("jobs").getJSONObject(0)
        job.put("targetBytes", 1)
        assertThrows(Exception::class.java) { JobCodec.decode(root.toString()) }
        job.put("targetBytes", 25_000_000)
        job.getJSONObject("sequence").put("rawFilter", "unsafe")
        assertThrows(Exception::class.java) { JobCodec.decode(root.toString()) }
    }
    @Test fun incompleteSequenceClipIntentNeverBecomesAnotherMovie() {
        for(kind in listOf("trim","effects")) {
            val root=JSONObject(JobCodec.encode(listOf(entry)))
            val clip=root.getJSONArray("jobs").getJSONObject(0).getJSONObject("sequence").getJSONArray("clips").getJSONObject(0)
            if(kind=="trim") clip.getJSONObject("trim").remove("endMs")
            else clip.getJSONObject("settings").getJSONObject("effects").remove("volumePercent")
            assertThrows("Missing clip $kind must preserve the original queue",Exception::class.java) { JobCodec.decode(root.toString()) }
        }
    }
}
