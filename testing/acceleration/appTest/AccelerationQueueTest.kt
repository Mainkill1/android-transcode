package dev.forma.app.data

import dev.forma.core.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class AccelerationQueueTest {
    @Test fun encoderNamesRoundTripWithoutChangingSnapshots() {
        for (video in VideoEncoder.values()) {
            val job = QueueEntry(JobSpec(UUID.randomUUID().toString(),
                Source("content://sample", "sample", 30_000, 640, 360, 1, 1), Trim(1000, 25000),
                Settings(video = video, rateControl = RateControl.BITRATE, fps = 30)))
            assertEquals(listOf(job), JobCodec.decode(JobCodec.encode(listOf(job))))
        }
    }
    @Test fun legacyNamesKeepTheirExplicitMeaning() {
        assertEquals(AccelerationMode.SOFTWARE_ONLY, VideoEncoder.valueOf("X264").accelerationMode)
        assertEquals(AccelerationMode.HARDWARE_REQUIRED, VideoEncoder.valueOf("H264_HW").accelerationMode)
        assertEquals(VideoEncoder.X264, Settings().video)
    }
}
