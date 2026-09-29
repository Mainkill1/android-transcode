package dev.forma.app.data

import dev.forma.core.*
import dev.forma.core.settings.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsJobCodecTest {
    private fun canonical(value:Any?):Any? = when(value) {
        is JSONObject -> value.keys().asSequence().sorted().associateWith { canonical(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
        is Number -> value.toDouble()
        else -> value
    }
    @Test fun queueWireVersionIncludesPreferenceProvenance() {
        val entry=QueueEntry(JobSpec("a66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4", Source("content://a/b", "clip", 1000), Trim(), Settings()))
        val root=JSONObject(JobCodec.encode(listOf(entry)))
        assertEquals(4,root.getInt("schema"))
        assertTrue(root.getJSONArray("jobs").getJSONObject(0).has("preferences"))
    }

    @Test fun frozenLayersRoundTripEvenWhenAnOverrideEqualsItsParent() {
        val app=SettingsDocument(7,PreferenceValues.EMPTY.with("video.quality",SettingValue.Integer(23)))
        val preferences=MediaPreferences(app,preset=PreferenceValues.EMPTY.with("video.quality",SettingValue.Integer(23)),
            overrides=PreferenceValues.EMPTY.with("video.quality",SettingValue.Integer(23)),presetName="Balanced")
        val entry=QueueEntry(JobSpec("a66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4",Source("content://a/b","a",1000),Trim(),
            NativePreferences.apply(Settings(),preferences.resolve()),preferences),JobState.COMPLETED,completedAtMs=12345)
        val restored=JobCodec.decode(JobCodec.encode(listOf(entry))).single()
        assertEquals(entry,restored)
        assertEquals(ValueOrigin.JOB,restored.spec.preferences.resolve().getValue("video.quality").origin)
        assertEquals(ValueOrigin.PRESET,restored.spec.preferences.copy(overrides=PreferenceValues.EMPTY).resolve().getValue("video.quality").origin)
        assertEquals(7L,restored.spec.preferences.app.revision)
    }
    @Test fun fixedLegacyFixturesPreserveConcreteSourceGraphAndOriginalPlannerRanges() {
        for(schema in 1..2) {
            val text=javaClass.getResourceAsStream("/queue-schema$schema.json")!!.bufferedReader().use { it.readText() }
            val original=JSONObject(text).getJSONArray("jobs").getJSONObject(0)
            val loaded=JobCodec.decode(text).single()
            val restored=JobCodec.decode(JobCodec.encode(listOf(loaded))).single()
            assertEquals(loaded,restored)
            assertEquals(33,restored.spec.settings.fps)
            assertEquals(384,restored.spec.settings.audioKbps)
            assertEquals(1000,restored.spec.settings.maxHeight)
            assertEquals(VideoEncoder.H265_HW,restored.spec.settings.video)
            assertTrue(restored.spec.settings.keepMetadata)
            assertEquals(SettingValue.Choice("hardware"),restored.spec.preferences.overrides["engine.encode_backend"])
            assertEquals(14,restored.spec.preferences.overrideCount)
            val saved=JSONObject(JobCodec.encode(listOf(restored))).getJSONArray("jobs").getJSONObject(0)
            assertTrue(canonical(original.getJSONObject("source"))==canonical(saved.getJSONObject("source")) || schema==1)
            if(schema==2) assertTrue(canonical(original.getJSONObject("settings").getJSONObject("audioEdit"))==canonical(saved.getJSONObject("settings").getJSONObject("audioEdit")))
            assertEquals("../clip.mp4",restored.spec.source.name)
            assertNull(restored.completedAtMs)
        }
    }
    @Test fun queueLegacyReaderDoesNotWeakenAppPreferenceValidation() {
        val invalid="FORMA_SETTINGS\t1\t0\nvideo.frame_rate\tc:33\n"
        assertThrows(IllegalArgumentException::class.java) { SettingsCodec.decode(invalid) }
        assertEquals(SettingValue.Choice("33"),SettingsCodec.decodeLegacyMediaSnapshot(invalid).values["video.frame_rate"])
    }
    private fun queueRoot():JSONObject=JSONObject(JobCodec.encode(listOf(QueueEntry(JobSpec(
        "a66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4",Source("content://a/b","a",5000,320,240,1,1,
            audioStreams=listOf(dev.forma.core.audio.SourceAudioFacts(1,48000,2,durationUs=5000000))),Trim(),Settings(fps=30))))))
    private fun rejects(change:(JSONObject)->Unit) {
        val root=queueRoot();change(root)
        assertTrue("Unsafe saved queue was accepted: $root",runCatching { JobCodec.decode(root.toString()) }.isFailure)
    }
    @Test fun malformedIntegersCannotChangeQueuedMediaIntent() {
        for(value in listOf<Any>(4294967326L,30.5,"30")) rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").put("fps",value) }
        rejects { it.put("schema",3.5) }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("source").getJSONArray("audioStreams").getJSONObject(0).put("sampleRateHz",4295015296L) }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").getJSONObject("output").put("maxBytes",1000000.5) }
    }
    @Test fun unknownQueueFieldsArePreservedByRejectingRatherThanRewriting() {
        rejects { it.put("future","retained") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).put("future","retained") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("source").put("future","retained") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("trim").put("future","retained") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").put("targetBytes",1000000) }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("preferences").put("future","retained") }
    }
    @Test fun requiredNullableWriterFieldsCannotDisappearOrLoseType() {
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("trim").remove("endMs") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).remove("completedAtMs") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("preferences").remove("presetName") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").getJSONObject("output").remove("maxBytes") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("source").put("hdr","false") }
        rejects { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").getJSONObject("output").getJSONObject("normalization").remove("mode") }
        assertEquals(null,JobCodec.decode(queueRoot().toString()).single().spec.trim.endMs)
    }
    @Test fun knownSavedNodeParametersCannotDisappearIntoRecipeDefaults() {
        val parameters=listOf<dev.forma.core.audio.AudioParameters>(
            dev.forma.core.audio.GainParameters(-6.0,true),dev.forma.core.audio.FadeParameters(100000,500000),
            dev.forma.core.audio.CompressorParameters(ratio=4.0),dev.forma.core.audio.LimiterParameters(ceilingDb=-3.0),
            dev.forma.core.audio.EqParameters(listOf(dev.forma.core.audio.EqBand("band",frequencyHz=250.0,gainDb=3.0,q=1.2,slopeDbPerOctave=24,enabled=false))))
        for((type,parameter) in listOf("gain","fades","compressor","limiter","eq").zip(parameters)) {
            val node=AudioEditCodec.encode(dev.forma.core.audio.AudioEdit(nodes=listOf(dev.forma.core.audio.AudioEffectNode("saved",type,parameters=parameter)))).getJSONArray("nodes").getJSONObject(0)
            val keys=node.getJSONObject("parameters").keys().asSequence().toList()
            for(key in keys) rejects { root ->
                val changed=JSONObject(node.toString());changed.getJSONObject("parameters").remove(key)
                root.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").put("nodes",org.json.JSONArray().put(changed))
            }
            if(type=="eq") for(key in node.getJSONObject("parameters").getJSONArray("bands").getJSONObject(0).keys().asSequence().toList()) rejects { root ->
                val changed=JSONObject(node.toString());changed.getJSONObject("parameters").getJSONArray("bands").getJSONObject(0).remove(key)
                root.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").put("nodes",org.json.JSONArray().put(changed))
            }
        }
    }
    @Test fun sparseRecipesStillUseDefaultsAndUnknownSavedParametersRemainOpaque() {
        val recipe=JSONObject("""{"schema":1,"nodes":[{"id":"fade","type":"fades","version":1,"enabled":true,"parameters":{"fadeInUs":100000}}]}""")
        val decoded=AudioEditCodec.decode(recipe)
        assertEquals(0L,(decoded.nodes.single().parameters as dev.forma.core.audio.FadeParameters).fadeOutUs)
        val root=queueRoot()
        val node=AudioEditCodec.encode(dev.forma.core.audio.AudioEdit(nodes=listOf(dev.forma.core.audio.AudioEffectNode("saved","gain",parameters=dev.forma.core.audio.GainParameters(-6.0))))).getJSONArray("nodes").getJSONObject(0)
        node.getJSONObject("parameters").put("futureParameter",17).remove("gainDb")
        root.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").put("nodes",org.json.JSONArray().put(node))
        val entry=JobCodec.decode(root.toString()).single()
        assertTrue(entry.spec.settings.audioEdit.nodes.single().parameters is dev.forma.core.audio.UnsupportedParameters)
        val saved=JSONObject(JobCodec.encode(listOf(entry))).getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").getJSONArray("nodes").getJSONObject(0).getJSONObject("parameters")
        assertEquals(17,saved.getInt("futureParameter"));assertFalse(saved.has("gainDb"))
    }
}
