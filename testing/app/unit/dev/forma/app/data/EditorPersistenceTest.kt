package dev.forma.app.data

import dev.forma.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditorPersistenceTest {
    private val effects = ClipEffects(CropRect(2, 4, 320, 180), QuarterTurn.CLOCKWISE, true, true,
        125, 10, 120, 80, 110, 2, true, 100, 200, 75, 300, 400, true)
    private val job = QueueEntry(JobSpec("00000000-0000-0000-0000-000000000001",
        Source("content://fixture", "test", 10_000, 640, 360, 1, 1), Trim(100, 9000), Settings(effects = effects)))

    private fun legacyRoot(schema:Int):JSONObject=JSONObject(JobCodec.encode(listOf(job))).put("schema",schema).also {
        val row=it.getJSONArray("jobs").getJSONObject(0)
        row.remove("kind");row.remove("preferences");row.remove("completedAtMs")
        row.getJSONObject("source").remove("audioStreams");row.getJSONObject("settings").remove("audioEdit")
        if(schema<3) { row.remove("sequence");row.remove("targetBytes") }
        if(schema==1) row.getJSONObject("settings").remove("effects")
    }
    @Test fun everyEditSurvivesQueueRoundTrip() {
        assertEquals(listOf(job), JobCodec.decode(JobCodec.encode(listOf(job))))
        assertEquals(4, JSONObject(JobCodec.encode(listOf(job))).getInt("schema"))
    }
    @Test fun legacyJobsGetNeutralEdits() {
        val root=legacyRoot(1)
        assertEquals(ClipEffects(), JobCodec.decode(root.toString()).single().spec.settings.effects)
    }
    @Test fun legacySchemaCannotSilentlyDiscardNewerRenderFields() {
        for(schema in listOf(1,2)) {
            val root=JSONObject(JobCodec.encode(listOf(job))).put("schema",schema)
            val old=root.getJSONArray("jobs").getJSONObject(0)
            assertThrows(Exception::class.java) { JobCodec.decode(root.toString()) }
            old.remove("sequence");old.remove("targetBytes")
            if(schema==1) assertThrows(Exception::class.java) { JobCodec.decode(root.toString()) }
        }
    }
    @Test fun futureSchemaIsNotSilentlyLoaded() {
        assertThrows(IllegalArgumentException::class.java) { JobCodec.decode("{\"schema\":999}") }
    }
    @Test fun schemaCoercionIsRejected() {
        listOf("2.5", "\"2\"", "true", "null").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { JobCodec.decode("{\"schema\":$value,\"jobs\":[]}") }
        }
    }
    @Test fun invalidSavedTimingFailsAtLoadRatherThanInTheUi() {
        val corrupt = job.copy(spec = job.spec.copy(trim = Trim(9000, 1000)))
        assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(JobCodec.encode(listOf(corrupt))) }
    }
    @Test fun missingNewSchemaEffectsFailRatherThanResetEdits() {
        val root = JSONObject(JobCodec.encode(listOf(job)))
        root.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").remove("effects")
        assertThrows(Exception::class.java) { JobCodec.decode(root.toString()) }
    }
    @Test fun unknownFieldsAndCoercionsAreRejected() {
        listOf("{\"customFilter\":\"crop=1:1\"}", "{\"speedPercent\":100.5}",
            "{\"speedPercent\":\"100\"}", "{\"sharpen\":\"true\"}", "{\"volumePercent\":null}",
            "{\"crop\":{\"x\":0,\"y\":0,\"width\":4294967296,\"height\":10}}").forEach {
            assertThrows(IllegalArgumentException::class.java) { ClipEffectsCodec.decode(JSONObject(it)) }
        }
    }
    @Test fun emptyRecipeIsNeutralAndEveryFieldRoundTrips() {
        assertEquals(ClipEffects(), ClipEffectsCodec.decode(JSONObject()))
        assertEquals(effects, ClipEffectsCodec.decode(ClipEffectsCodec.encode(effects)))
    }
    @Test fun missingSavedTrimEndNeverExpandsTheRange() {
        for(schema in listOf(1,2,3,4)) {
            val root=if(schema<4) legacyRoot(schema) else JSONObject(JobCodec.encode(listOf(job)))
            val row=root.getJSONArray("jobs").getJSONObject(0)
            assertEquals(1,JobCodec.decode(root.toString()).size)
            row.getJSONObject("trim").remove("endMs")
            assertThrows("Schema $schema must preserve explicit trim intent",Exception::class.java) { JobCodec.decode(root.toString()) }
        }
    }
    @Test fun missingSavedEffectFieldsCannotResetEdits() {
        val required=ClipEffectsCodec.encode(effects).keys().asSequence().toList()
        for(schema in listOf(2,3,4)) for(field in required) {
            val root=if(schema<4) legacyRoot(schema) else JSONObject(JobCodec.encode(listOf(job)))
            val row=root.getJSONArray("jobs").getJSONObject(0)
            assertEquals(1,JobCodec.decode(root.toString()).size)
            row.getJSONObject("settings").getJSONObject("effects").remove(field)
            assertThrows("Schema $schema missing $field must not reset an edit",Exception::class.java) { JobCodec.decode(root.toString()) }
        }
    }
}
