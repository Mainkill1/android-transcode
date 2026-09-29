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
        assertEquals(3,root.getInt("schema"))
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
}
