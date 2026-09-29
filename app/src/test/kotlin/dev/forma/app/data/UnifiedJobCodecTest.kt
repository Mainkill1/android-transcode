package dev.forma.app.data

import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.core.settings.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnifiedJobCodecTest {
    private val preferences = MediaPreferences(
        app = SettingsDocument(7, PreferenceValues.EMPTY.with("video.quality", SettingValue.Integer(23))),
        preset = PreferenceValues.EMPTY.with("video.quality", SettingValue.Integer(23)),
        overrides = PreferenceValues.EMPTY.with("video.quality", SettingValue.Integer(23)),
        presetName = "Balanced")
    private val av = QueueEntry(JobSpec("a66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4",
        Source("content://video", "clip.mp4", 5000, 320, 240, 1, 1), Trim(100, 4500),
        NativePreferences.apply(Settings(), preferences.resolve()), preferences),
        JobState.COMPLETED, "Finished", 1700000000000)
    private val imageInfo = ImageInfo(101, 77, ImageFormat.PNG, alpha = ImageAlpha.PRESENT,
        hash = "a".repeat(64), bytes = 100)
    private val image = QueueEntry(QueueJobSpec.Image(ImageJobSpec(
        "b66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4",
        ImageEditDocument(source = ImageSource("content://private/image", "logo.png", imageInfo.hash, 100,
            "content://original/image"), output = ImageOutputPolicy(format = ImageFormat.PNG, targetBytes = null)),
        imageInfo, ImageFormat.PNG, preferences)), JobState.QUEUED)

    private fun root() = JSONObject(JobCodec.encode(listOf(av, image)))
    private fun reject(change: (JSONObject) -> Unit) {
        val changed = root().also(change)
        val original = changed.toString()
        assertTrue("Unsupported saved intent was accepted: $original",
            runCatching { JobCodec.decode(original) }.isFailure)
    }

    @Test fun mixedKindsRetainFrozenLayersCompletionAndImagePolicy() {
        val saved = root()
        assertEquals(4, saved.getInt("schema"))
        val restored = JobCodec.decode(saved.toString())
        assertEquals(listOf(av, image), restored)
        for (entry in restored) {
            assertEquals(preferences, entry.spec.preferences)
            assertEquals(7L, entry.spec.preferences.app.revision)
            assertEquals(ValueOrigin.JOB, entry.spec.preferences.resolve().getValue("video.quality").origin)
        }
        assertEquals(1700000000000L, restored.first().completedAtMs)
        assertNull(restored.last().completedAtMs)
        val job = (restored.last().spec as QueueJobSpec.Image).job
        assertNull(job.document.output.targetBytes)
        assertEquals("content://original/image", job.document.source.originalUri)
        assertEquals(preferences, job.copy(id = "c66d2dd7-8f84-40bb-a96f-aa93ac6bcbd4").preferences)
    }

    @Test fun imageSnapshotCannotBeRebasedByMutatingItsDefaultsInput() {
        val values = linkedMapOf<String, SettingValue>("audio.bitrate_kbps" to SettingValue.Integer(160))
        val captured = MediaPreferences.fromDefaults(SettingsDocument(9, PreferenceValues.of(values)))
        val queued = ImageJobSpec(image.spec.id, (image.spec as QueueJobSpec.Image).job.document,
            imageInfo, ImageFormat.PNG, captured)
        values["audio.bitrate_kbps"] = SettingValue.Integer(320)
        val restored = (JobCodec.decode(JobCodec.encode(listOf(QueueEntry(QueueJobSpec.Image(queued)))))
            .single().spec as QueueJobSpec.Image).job
        assertEquals(SettingValue.Integer(160), restored.preferences.app.values["audio.bitrate_kbps"])
        assertEquals(9L, restored.preferences.app.revision)
    }

    @Test fun taggedSchemaThreeMigratesOnlyItsKnownAvAndImageRecords() {
        val legacy = root().put("schema", 3)
        for (index in 0 until legacy.getJSONArray("jobs").length()) {
            legacy.getJSONArray("jobs").getJSONObject(index).apply {
                remove("preferences"); remove("completedAtMs"); remove("targetBytes")
            }
        }
        val restored = JobCodec.decode(legacy.toString())
        assertEquals(av.spec.settings, restored.first().spec.settings)
        assertEquals(MediaPreferences.legacy(av.spec.settings), restored.first().spec.preferences)
        assertEquals(MediaPreferences.legacy(Settings()), restored.last().spec.preferences)
        assertTrue(restored.all { it.completedAtMs == null })
        assertEquals((image.spec as QueueJobSpec.Image).job.document,
            (restored.last().spec as QueueJobSpec.Image).job.document)
        assertEquals(restored, JobCodec.decode(JobCodec.encode(restored)))
    }

    @Test fun untaggedSettingsSchemaThreeRetainsItsRequiredProvenanceAndTimestamp() {
        val legacy = JSONObject(JobCodec.encode(listOf(av))).put("schema", 3)
        legacy.getJSONArray("jobs").getJSONObject(0).remove("kind")
        legacy.getJSONArray("jobs").getJSONObject(0).remove("targetBytes")
        assertEquals(listOf(av), JobCodec.decode(legacy.toString()))
        legacy.getJSONArray("jobs").getJSONObject(0).remove("preferences")
        assertTrue(runCatching { JobCodec.decode(legacy.toString()) }.isFailure)
    }

    @Test fun currentNullableFieldsCannotBeOmittedOrCoerced() {
        for (index in 0..1) for (field in listOf("kind", "preferences", "completedAtMs"))
            reject { it.getJSONArray("jobs").getJSONObject(index).remove(field) }
        for (field in listOf("info", "resolvedFormat"))
            reject { it.getJSONArray("jobs").getJSONObject(1).remove(field) }
        reject { it.getJSONArray("jobs").getJSONObject(0).getJSONObject("trim").remove("endMs") }
        reject { it.getJSONArray("jobs").getJSONObject(0).remove("targetBytes") }
        reject { it.getJSONArray("jobs").getJSONObject(1).getJSONObject("preferences").remove("presetName") }
        for (value in listOf<Any>(1.5, "1700000000000", -1L))
            reject { it.getJSONArray("jobs").getJSONObject(0).put("completedAtMs", value) }
        reject { it.getJSONArray("jobs").getJSONObject(1).put("kind", true) }
    }

    @Test fun futureAndHybridEnvelopesRejectWithoutDroppingAnyRecords() {
        reject { it.put("schema", 5) }
        reject { it.getJSONArray("jobs").getJSONObject(1).put("kind", "future-image") }
        reject { it.getJSONArray("jobs").getJSONObject(1).put("futureField", "keep") }
        reject { it.put("schema", 3) } // Tagged schema 3 did not store preference/timestamp layers.
        reject { it.getJSONArray("jobs").getJSONObject(0).put("sequence", JSONObject()) }
        reject { it.getJSONArray("jobs").getJSONObject(1).put("id", av.spec.id) }
        val mixedLegacy = root().put("schema", 3)
        mixedLegacy.getJSONArray("jobs").getJSONObject(0).remove("kind")
        mixedLegacy.getJSONArray("jobs").getJSONObject(0).remove("targetBytes")
        mixedLegacy.getJSONArray("jobs").getJSONObject(1).apply { remove("preferences"); remove("completedAtMs") }
        assertTrue("Different schema-3 envelopes must not be combined by guessing",
            runCatching { JobCodec.decode(mixedLegacy.toString()) }.isFailure)
    }

    @Test fun avRuntimeCapAndImagePolicyRemainSeparateWithTheirPreferences() {
        val capped=av.copy(spec=av.spec.copy(targetBytes=250000))
        val imageJob=(image.spec as QueueJobSpec.Image).job
        val cappedImage=image.copy(spec=QueueJobSpec.Image(imageJob.copy(document=imageJob.document.copy(
            output=imageJob.document.output.copy(targetBytes=900000)))))
        val wire=JSONObject(JobCodec.encode(listOf(capped,cappedImage)))
        assertEquals(250000L,wire.getJSONArray("jobs").getJSONObject(0).getLong("targetBytes"))
        assertFalse(wire.getJSONArray("jobs").getJSONObject(1).has("targetBytes"))
        val restored=JobCodec.decode(wire.toString())
        assertEquals(listOf(capped,cappedImage),restored)
        assertEquals(900000L,restored.last().spec.targetBytes)
        assertEquals(preferences,restored.first().spec.preferences)
        assertEquals(preferences,restored.last().spec.preferences)
    }
}
