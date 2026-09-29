package dev.forma.core.audio

import dev.forma.core.*
import org.junit.Assert.*
import org.junit.Test

class AudioArtifactVerificationTest {
    private val input = Source("input","input.wav",1000,audioTracks=1,audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=1,durationUs=1000000,totalSamples=48000)))
    private val settings = Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,stereo=false)
    @Test fun rejectsTruncatedPcmEvenWhenContainerHasPlausibleDuration() {
        val output = input.copy(audioStreams=listOf(input.audioStreams.single().copy(totalSamples=47999)))
        assertTrue(AudioArtifactVerification.problems(input,Trim(),settings,output,192044).any { "sample" in it })
    }
    @Test fun rejectsWrongClockAndChannels() {
        val output = input.copy(audioStreams=listOf(input.audioStreams.single().copy(sampleRateHz=44100,channels=2)))
        assertEquals(2,AudioArtifactVerification.problems(input,Trim(),settings,output,192044).size)
    }
    @Test fun verifiesPostRateDurationInsteadOfSourceDuration() {
        val edit = settings.copy(audioEdit=AudioEdit(rate=AudioRate(2,1)))
        val output = input.copy(durationMs=500,audioStreams=listOf(input.audioStreams.single().copy(durationUs=500000,totalSamples=24000)))
        assertTrue(AudioArtifactVerification.problems(input,Trim(),edit,output,96044).isEmpty())
    }
    @Test fun validExactPcmPasses() { assertTrue(AudioArtifactVerification.problems(input,Trim(),settings,input,192044).isEmpty()) }

    @Test fun finalBytesMustBeStrictlyBelowDecimalCap() {
        val capped=settings.copy(audioEdit=AudioEdit(output=AudioOutputPolicy(maxBytes=192044)))
        assertTrue(AudioArtifactVerification.problems(input,Trim(),capped,input,192044).any { "limit" in it })
        assertTrue(AudioArtifactVerification.problems(input,Trim(),capped,input,192043).isEmpty())
    }
}
