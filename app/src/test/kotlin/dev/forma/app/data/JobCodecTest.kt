package dev.forma.app.data

import dev.forma.core.*
import org.junit.Assert.*
import org.junit.Test

class JobCodecTest {
    private val entry = QueueEntry(JobSpec("3d4754f2-f642-4f9c-9cf4-c6db11a77dca",
        Source("content://documents/a", "A ' name.mp4", 10_000, 1920, 1080, 1, 2, false, 123456),
        Trim(500, 9500), Settings(video = VideoEncoder.X265, crf = 19, stereo = false, keepMetadata = true)),
        JobState.QUEUED, "Saved")
    @Test fun roundTripAllSettings() { assertEquals(listOf(entry), JobCodec.decode(JobCodec.encode(listOf(entry)))) }
    @Test fun nullableEndTime() { val e = entry.copy(spec = entry.spec.copy(trim = Trim())); assertEquals(listOf(e), JobCodec.decode(JobCodec.encode(listOf(e)))) }
    @Test fun rejectsFutureSchema() { assertThrows(IllegalArgumentException::class.java) { JobCodec.decode("{\"schema\":4,\"jobs\":[]}") } }
    @Test fun rejectsDuplicateIds() { assertThrows(IllegalArgumentException::class.java) { JobCodec.decode(JobCodec.encode(listOf(entry, entry))) } }
}
