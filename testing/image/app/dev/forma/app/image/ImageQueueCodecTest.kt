package dev.forma.app.image
import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
class ImageQueueCodecTest {
    @Test fun missingSavedAudioParameterDoesNotChangeQueuedAvIntent() {
        val entry=QueueEntry(JobSpec("3d4754f2-f642-4f9c-9cf4-c6db11a77dca",Source("content://one","audio.m4a",10000),Trim(0,5000),Settings()))
        val root=org.json.JSONObject(JobCodec.encode(listOf(entry)))
        root.getJSONArray("jobs").getJSONObject(0).getJSONObject("settings").getJSONObject("audioEdit").put("nodes",org.json.JSONArray("[{\"id\":\"gain-1\",\"type\":\"gain\",\"version\":1,\"enabled\":true,\"parameters\":{\"gainDb\":6.0}}]"))
        try{JobCodec.decode(root.toString());fail("Missing mute intent silently defaulted")}catch(_:IllegalArgumentException){}
    }
    @Test fun missingSavedTrimEndpointIsRejectedForEveryMigratedSchema() {
        val entry=QueueEntry(JobSpec("3d4754f2-f642-4f9c-9cf4-c6db11a77dca",Source("content://one","audio.m4a",10000),Trim(0,5000),Settings()))
        for(schema in 1..4){val root=org.json.JSONObject(JobCodec.encode(listOf(entry)));root.put("schema",schema)
            val job=root.getJSONArray("jobs").getJSONObject(0)
            if(schema<3)job.remove("kind")
            if(schema<4){job.remove("preferences");job.remove("completedAtMs");job.remove("targetBytes");job.remove("sequence");job.getJSONObject("settings").remove("effects")}
            if(schema==1)job.getJSONObject("settings").remove("audioEdit")
            assertEquals(1,JobCodec.decode(root.toString()).size)
            job.getJSONObject("trim").remove("endMs")
            try{JobCodec.decode(root.toString());fail("Missing endpoint extended schema $schema trim")}catch(_:IllegalArgumentException){}
        }
    }
    @Test fun taggedImageSnapshotRoundTripsWithoutAvDuration() {
        val info=ImageInfo(101,77,ImageFormat.PNG,alpha=ImageAlpha.PRESENT,hash="a".repeat(64),bytes=10)
        val image=ImageJobSpec(UUID.randomUUID().toString(),ImageEditDocument(source=ImageSource("content://one","logo.png",info.hash,10)),info)
        val entry=QueueEntry(QueueJobSpec.Image(image))
        val loaded=JobCodec.decode(JobCodec.encode(listOf(entry))).single()
        assertEquals(image,(loaded.spec as QueueJobSpec.Image).job);assertEquals(0L,loaded.spec.source.durationMs)
    }
    @Test fun wrongNumericTypesAndUnknownOperationsAreRejected() {
        val d=ImageEditDocument(source=ImageSource("content://one","logo.png","a".repeat(64),10))
        val json=ImageDocumentCodec.encode(d)
        for(text in listOf(json.toString().replace("\"quarterTurns\":0","\"quarterTurns\":\"0\""),json.toString().replace("\"schema\":1","\"schema\":99"),json.toString().replace("\"name\":\"logo.png\"","\"name\":123"),json.toString().replace("\"quarterTurns\":0","\"quarterTurns\":0,\"unknownActiveFilter\":true"))) {
            try { ImageDocumentCodec.decode(org.json.JSONObject(text));fail() } catch(e:IllegalArgumentException) { }
        }
    }
    @Test fun missingNullableIntentIsNotTreatedAsExplicitNull() {
        val d=ImageEditDocument(source=ImageSource("content://one","logo.png","a".repeat(64),10),cropAspectRatio=2.0,output=ImageOutputPolicy(canvasWidth=400,canvasHeight=300))
        for(field in listOf("targetBytes","canvasWidth","canvasHeight","flatten","width","height")) {
            val json=ImageDocumentCodec.encode(d);json.getJSONObject("output").remove(field)
            try{ImageDocumentCodec.decode(json);fail("Missing $field silently altered intent")}catch(_:IllegalArgumentException){}
        }
        val json=ImageDocumentCodec.encode(d);json.remove("cropAspectRatio")
        try{ImageDocumentCodec.decode(json);fail("Missing ratio silently unlocked crop")}catch(_:IllegalArgumentException){}
        assertEquals(d,ImageDocumentCodec.decode(ImageDocumentCodec.encode(d)))
    }
}
