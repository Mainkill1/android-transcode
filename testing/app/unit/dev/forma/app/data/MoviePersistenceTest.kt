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
        assertEquals(4, JSONObject(JobCodec.encode(listOf(entry))).getInt("schema"))
    }
    @Test fun legacySchemaTwoRemainsSingleClipWithManualBudget() {
        val root = JSONObject(JobCodec.encode(listOf(entry))).put("schema", 2)
        val job = root.getJSONArray("jobs").getJSONObject(0)
        job.remove("sequence"); job.remove("targetBytes");job.remove("kind");job.remove("preferences");job.remove("completedAtMs")
        job.getJSONObject("source").remove("audioStreams");job.getJSONObject("settings").remove("audioEdit")
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
        for(kind in listOf("trim","effects","audioEdit","audioStreams","gainMute")) {
            val root=JSONObject(JobCodec.encode(listOf(entry)))
            val clip=root.getJSONArray("jobs").getJSONObject(0).getJSONObject("sequence").getJSONArray("clips").getJSONObject(0)
            if(kind=="trim") clip.getJSONObject("trim").remove("endMs")
            else if(kind=="effects") clip.getJSONObject("settings").getJSONObject("effects").remove("volumePercent")
            else if(kind=="audioEdit") clip.getJSONObject("settings").remove("audioEdit")
            else if(kind=="audioStreams") clip.getJSONObject("source").remove("audioStreams")
            else {
                val edit=AudioEditCodec.encode(dev.forma.core.audio.AudioEdit(nodes=listOf(dev.forma.core.audio.AudioEffectNode("gain","gain",parameters=dev.forma.core.audio.GainParameters(-6.0)))))
                edit.getJSONArray("nodes").getJSONObject(0).getJSONObject("parameters").remove("muted")
                clip.getJSONObject("settings").put("audioEdit",edit)
            }
            assertThrows("Missing clip $kind must preserve the original queue",Exception::class.java) { JobCodec.decode(root.toString()) }
        }
    }

    private fun movieLegacy(schema: Int): JSONObject {
        val root=JSONObject(JobCodec.encode(listOf(entry))).put("schema",schema)
        val job=root.getJSONArray("jobs").getJSONObject(0)
        job.remove("kind");job.remove("preferences");job.remove("completedAtMs")
        fun legacyClip(record:JSONObject) { record.getJSONObject("source").remove("audioStreams");record.getJSONObject("settings").remove("audioEdit") }
        legacyClip(job)
        val clips=job.getJSONObject("sequence").getJSONArray("clips")
        for(i in 0 until clips.length()) legacyClip(clips.getJSONObject(i))
        return root
    }
    @Test fun schemaThreeMovieRetainsCanvasOrderingTransitionAndCap() {
        val restored=JobCodec.decode(movieLegacy(3).toString()).single()
        assertEquals(entry.spec.sequence,restored.spec.sequence)
        assertEquals(entry.spec.targetBytes,restored.spec.targetBytes)
        assertTrue(restored.spec.preferences.legacySnapshot)
        assertEquals(listOf(restored),JobCodec.decode(JobCodec.encode(listOf(restored))))
    }
    @Test fun movieLegacyFamiliesCannotMixSettingsAudioAndRuntimeIntent() {
        for(schema in listOf(2,3)) {
            val hybrid=movieLegacy(schema)
            val job=hybrid.getJSONArray("jobs").getJSONObject(0)
            if(schema==2) { job.remove("sequence");job.remove("targetBytes") }
            job.getJSONObject("settings").put("audioEdit",AudioEditCodec.encode(dev.forma.core.audio.AudioEdit()))
            assertTrue(runCatching { JobCodec.decode(hybrid.toString()) }.isFailure)
        }
        val mixed=movieLegacy(3)
        val modern=JSONObject(JobCodec.encode(listOf(entry))).getJSONArray("jobs").getJSONObject(0)
        modern.remove("kind");modern.remove("sequence");modern.remove("targetBytes");modern.getJSONObject("settings").remove("effects")
        modern.put("id","00000000-0000-0000-0000-000000000002")
        mixed.getJSONArray("jobs").put(modern)
        assertTrue(runCatching { JobCodec.decode(mixed.toString()) }.isFailure)
    }
    @Test fun nestedAudioEditsStreamFactsAndPreferencesRemainImmutableOnRestart() {
        val facts=dev.forma.core.audio.SourceAudioFacts(streamIndex=1,sampleRateHz=48000,channels=2,durationUs=6000000,timelineOffsetUs=125000,totalSamples=288000)
        val edit=dev.forma.core.audio.AudioEdit(nodes=listOf(dev.forma.core.audio.AudioEffectNode("gain","gain",enabled=false,parameters=dev.forma.core.audio.GainParameters(-6.0))))
        val changed=clips.map { it.copy(source=it.source.copy(audioStreams=listOf(facts)),settings=it.settings.copy(audioEdit=edit)) }
        val movie=entry.copy(spec=entry.spec.copy(sequence=entry.spec.sequence!!.copy(timeline=EditTimeline(changed))),completedAtMs=1700000000000)
        assertEquals(listOf(movie),JobCodec.decode(JobCodec.encode(listOf(movie))))
        val queued=JobCodec.decode(JobCodec.encode(listOf(movie))).single()
        assertEquals(edit,queued.spec.sequence!!.timeline.clips.first().settings.audioEdit)
        assertEquals(facts,queued.spec.sequence!!.timeline.clips.first().source.audioStreams.single())
        assertEquals(movie.spec.preferences,queued.spec.preferences)
        assertEquals(movie.completedAtMs,queued.completedAtMs)
    }

    @Test fun mixedMovieAvAndImagePreserveSeparateFrozenPoliciesAndTimestamps() {
        val prefs=dev.forma.core.settings.MediaPreferences.fromDefaults(dev.forma.core.settings.SettingsDocument(revision=9))
        val movie=entry.copy(spec=entry.spec.copy(preferences=prefs),state=JobState.COMPLETED,completedAtMs=1700000000000)
        val av=QueueEntry(JobSpec("00000000-0000-0000-0000-000000000002",source,Trim(100,4500),Settings(),preferences=prefs,targetBytes=250000))
        val image=QueueEntry(dev.forma.core.image.QueueJobSpec.Image(dev.forma.core.image.ImageJobSpec(
            "00000000-0000-0000-0000-000000000003",dev.forma.core.image.ImageEditDocument(source=dev.forma.core.image.ImageSource("content://image","still.png","a".repeat(64),100)),preferences=prefs)))
        val entries=listOf(movie,av,image)
        assertEquals(entries,JobCodec.decode(JobCodec.encode(entries)))
        assertEquals(movie.spec.sequence,movie.spec.copy(id="00000000-0000-0000-0000-000000000004").sequence)
    }
    @Test fun futureNestedAudioGraphIsPreservedAndCannotExecuteAsNeutral() {
        val root=JSONObject(JobCodec.encode(listOf(entry)))
        val clip=root.getJSONArray("jobs").getJSONObject(0).getJSONObject("sequence").getJSONArray("clips").getJSONObject(0)
        clip.getJSONObject("settings").put("audioEdit",JSONObject("""{"schema":99,"future":{"keep":17}}"""))
        val loaded=JobCodec.decode(root.toString()).single()
        assertTrue(JobPlans.validate((loaded.spec as dev.forma.core.image.QueueJobSpec.Av).job).isNotEmpty())
        val saved=JSONObject(JobCodec.encode(listOf(loaded))).getJSONArray("jobs").getJSONObject(0).getJSONObject("sequence")
            .getJSONArray("clips").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit")
        assertEquals(17,saved.getJSONObject("future").getInt("keep"))
    }
}
