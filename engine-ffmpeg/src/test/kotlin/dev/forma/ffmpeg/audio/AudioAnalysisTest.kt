package dev.forma.ffmpeg.audio

import dev.forma.core.*
import dev.forma.core.audio.*
import org.junit.Assert.*
import org.junit.Test

class AudioAnalysisTest {
    private val source=Source("uri","tone",2000,audioTracks=1,audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=1,durationUs=2000000)))
    @Test fun graphSourceAndNativeBuildInvalidateAnalysis() {
        val settings=Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE)
        val base=AudioAnalysisIdentity.create("hash", "native1",source,Trim(),settings)
        assertNotEquals(base,AudioAnalysisIdentity.create("other", "native1",source,Trim(),settings))
        assertNotEquals(base,AudioAnalysisIdentity.create("hash", "native2",source,Trim(),settings))
        assertNotEquals(base,AudioAnalysisIdentity.create("hash", "native1",source,Trim(500),settings))
        assertNotEquals(base,AudioAnalysisIdentity.create("hash", "native1",source,Trim(),settings.copy(audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("eq","eq",parameters=EqParameters(listOf(EqBand("band",gainDb=3.0)))))))))
    }
    @Test fun silenceAndNonFiniteMeasurementsAreStructured() {
        val result=AudioMeasurements.parseLoudness("{\"input_i\":\"-inf\",\"input_tp\":\"-inf\",\"input_lra\":\"0\",\"input_thresh\":\"-70\",\"target_offset\":\"inf\"}")
        assertTrue(result is AudioMeasurementResult.NotMeasurable)
    }
    @Test fun validLoudnessKeepsFiniteTypedValues() {
        val result=AudioMeasurements.parseLoudness("log\n{\"input_i\":\"-21.2\",\"input_tp\":\"-6.4\",\"input_lra\":\"3.1\",\"input_thresh\":\"-31.2\",\"target_offset\":\"0.2\"}\n")
        assertEquals(-21.2,(result as AudioMeasurementResult.Measured).values.integratedLufs,.001)
    }
    @Test fun preserveDynamicsRejectsUnattainableTarget() {
        val measurements=AudioMeasurements(-24.0,-1.0,4.0,-34.0,0.0)
        assertTrue(runCatching { measurements.normalizationFilter(NormalizationPolicy(mode=NormalizationMode.LOUDNESS,integratedLufs=-16.0,truePeakDb=-1.5,preserveDynamics=true)) }.isFailure)
    }
    @Test fun peakAndLoudnessAreDifferentPolicies() {
        val measurements=AudioMeasurements(-24.0,-9.0,4.0,-34.0,0.0,samplePeakDb=-10.0)
        assertEquals("volume=9dB:precision=double",measurements.normalizationFilter(NormalizationPolicy(mode=NormalizationMode.PEAK)))
        assertTrue(measurements.normalizationFilter(NormalizationPolicy(mode=NormalizationMode.LOUDNESS,integratedLufs=-20.0)).startsWith("loudnorm="))
    }
}
