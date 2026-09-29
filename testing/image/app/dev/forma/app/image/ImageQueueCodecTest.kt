package dev.forma.app.image
import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.app.data.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
class ImageQueueCodecTest {
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
}
