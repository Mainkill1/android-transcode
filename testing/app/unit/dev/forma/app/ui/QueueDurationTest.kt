package dev.forma.app.ui
import dev.forma.core.*
import dev.forma.core.audio.*
import org.junit.Assert.*
import org.junit.Test
class QueueDurationTest {
    private val source=Source("content://fixture","audio.wav",4000,audioTracks=1,
        audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=1,durationUs=4000000)))
    @Test fun futureGraphCannotCrashQueueDescription() {
        val job=JobSpec("future",source,Trim(),Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,
            audioEdit=AudioEdit(schemaVersion=99)))
        assertEquals("Duration unavailable",queueDurationLabel(job))
    }
    @Test fun supportedEditedAudioUsesItsOutputDuration() {
        val job=JobSpec("edited",source,Trim(1000,3000),Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,
            effects=ClipEffects(speedPercent=200)))
        assertEquals(mediaTime(1000),queueDurationLabel(job))
    }
}
