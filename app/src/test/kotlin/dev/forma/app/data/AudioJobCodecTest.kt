package dev.forma.app.data

import dev.forma.core.*
import dev.forma.core.audio.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AudioJobCodecTest {
    private val oldEntry = QueueEntry(JobSpec("a66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4",
        Source("content://files/a", "Old.wav", 30000, audioTracks = 1), Trim(5000, 15000),
        Settings(container = Container.M4A, audioTrack = 0, stereo = false)))

    private fun legacyRoot(schema:Int):JSONObject=JSONObject(JobCodec.encode(listOf(oldEntry))).put("schema",schema).also {
        val job=it.getJSONArray("jobs").getJSONObject(0)
        job.remove("preferences");job.remove("completedAtMs")
    }
    @Test fun legacyJobsUpgradeToAudioSchemaWithoutChangingTheirSettings() {
        val root = legacyRoot(1)
        root.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").remove("audioEdit")
        val legacy = root.toString()
        assertEquals(listOf(oldEntry), JobCodec.decode(legacy))
        val upgraded = JSONObject(JobCodec.encode(JobCodec.decode(legacy)))
        assertEquals(3, upgraded.getInt("schema"))
        assertTrue(upgraded.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").has("audioEdit"))
    }
    @Test fun orderedEditsAndOutputPolicySurviveQueueRoundTrip() {
        val edit = AudioEdit(nodes = listOf(
            AudioEffectNode("gain", "gain", parameters = GainParameters(-6.0)),
            AudioEffectNode("eq", "eq", parameters = EqParameters(listOf(EqBand("bass", EqType.LOW_SHELF, 150.0, 3.0)))),
            AudioEffectNode("voice", "compressor", enabled = false, parameters = CompressorParameters(ratio = 3.0))),
            output = AudioOutputPolicy(ChannelMode.MONO, 44100, NormalizationPolicy(NormalizationMode.LOUDNESS, integratedLufs = -18.0)))
        val queued = oldEntry.copy(spec = oldEntry.spec.copy(settings = oldEntry.spec.settings.copy(audioEdit = edit)))
        assertEquals(listOf(queued), JobCodec.decode(JobCodec.encode(listOf(queued))))
        val changed = edit.copy(nodes = edit.nodes.filterNot { it.id == "gain" })
        assertEquals(3, queued.spec.settings.audioEdit.nodes.size)
        assertEquals(2, changed.nodes.size)
    }
    @Test fun unknownEffectAndItsFutureFieldsRemainAfterSaveAndExplicitBypass() {
        val root = legacyRoot(2)
        val unknown = JSONObject("""{"id":"future","type":"new-filter","version":7,"enabled":true,"parameters":{"amount":3},"futureField":{"keep":42}}""")
        val audio = JSONObject().put("schema", 1).put("nodes", JSONArray().put(unknown)).put("output", JSONObject())
        root.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").put("audioEdit", audio)
        val loaded = JobCodec.decode(root.toString()).single()
        assertEquals("new-filter", loaded.spec.settings.audioEdit.nodes.single().type)
        val edit = loaded.spec.settings.audioEdit
        assertTrue(AudioEffectRegistry.validate(edit, SourceAudioFacts(durationUs = 30000000)).isNotEmpty())
        val bypassed = edit.copy(nodes = edit.nodes.map { it.copy(enabled = false) })
        val saved = JSONObject(JobCodec.encode(listOf(loaded.copy(spec = loaded.spec.copy(settings = loaded.spec.settings.copy(audioEdit = bypassed))))))
        val node = saved.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").getJSONArray("nodes").getJSONObject(0)
        assertEquals(42, node.getJSONObject("futureField").getInt("keep"))
        assertFalse(node.getBoolean("enabled"))
    }
    @Test fun futureAudioSchemaIsRetainedButCannotBecomeAnExecutableNeutralEdit() {
        val root = legacyRoot(2)
        root.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").put("audioEdit", JSONObject("""{"schema":99,"future":{"keep":17}}"""))
        val loaded = JobCodec.decode(root.toString()).single()
        assertFalse(AudioEffectRegistry.validate(loaded.spec.settings.audioEdit, SourceAudioFacts()).isEmpty())
        val saved = JSONObject(JobCodec.encode(listOf(loaded))).getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit")
        assertEquals(17, saved.getJSONObject("future").getInt("keep"))
    }
    @Test fun selectedStreamFactsAndRationalTimingSurviveRestart() {
        val source = oldEntry.spec.source.copy(audioStreams = listOf(SourceAudioFacts(streamIndex = 1, sampleRateHz = 44100,
            channels = 2, channelLayout = "stereo", sampleFormat = "s16", durationUs = 30000000,
            totalSamples = 1323000, codec = "pcm_s16le", language = "eng", title = "Main sound")))
        val entry = oldEntry.copy(spec = oldEntry.spec.copy(source = source,
            settings = oldEntry.spec.settings.copy(audioEdit = AudioEdit(rate = AudioRate(3, 2)))))
        assertEquals(listOf(entry), JobCodec.decode(JobCodec.encode(listOf(entry))))
    }

    @Test fun malformedPresentParametersRemainOpaqueAndBlocked() {
        val raw=JSONObject().put("schema",1).put("nodes",org.json.JSONArray().put(JSONObject().put("id","g").put("type","gain").put("version",1).put("enabled",true).put("parameters",JSONObject().put("gainDb","invalid"))))
        val restored=AudioEditCodec.decode(raw)
        assertTrue(restored.nodes.single().parameters is UnsupportedParameters)
        assertEquals("invalid",AudioEditCodec.encode(restored).getJSONArray("nodes").getJSONObject(0).getJSONObject("parameters").getString("gainDb"))
        assertTrue(AudioEffectRegistry.validate(restored,SourceAudioFacts()).isNotEmpty())
    }
}
