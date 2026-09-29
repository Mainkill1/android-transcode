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
