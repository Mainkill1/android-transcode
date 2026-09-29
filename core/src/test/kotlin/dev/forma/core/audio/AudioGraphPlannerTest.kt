package dev.forma.core.audio

import dev.forma.core.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AudioGraphPlannerTest {
    private val source = Source("content://files/audio", "A.wav", 30000, audioTracks = 1)
    @Test fun gainAndFadeExecuteInCreativeChainOrderAfterTheSourceTrim() {
        val edit = AudioEdit(nodes = listOf(AudioEffectNode("gain", "gain", parameters = GainParameters(-6.0)),
            AudioEffectNode("fades", "fades", parameters = FadeParameters(1000000, 2000000))))
        val arguments = Planner.arguments(source, Trim(5000, 15000), Settings(container = Container.M4A, audioEdit = edit), "in.wav", "out.m4a")
        assertTrue("Audio edits must reach native arguments", "-af" in arguments)
        val graph = arguments[arguments.indexOf("-af") + 1]
        assertTrue(graph.contains("atrim"))
        assertTrue(graph.indexOf("volume=-6dB") < graph.indexOf("afade=t=in"))
        assertTrue(graph.contains("afade=t=out:st=8:d=2"))
        assertFalse("Do not trim twice through global output seeking", "-ss" in arguments)
    }
    @Test fun emptyChainPreservesLegacyArgumentsWithoutAnExtraDspGraph() {
        val arguments = Planner.arguments(source, Trim(), Settings(container = Container.M4A), "in.wav", "out.m4a")
        assertFalse("-af" in arguments)
        assertEquals("0:a:0", arguments[arguments.indexOf("-map") + 1])
    }
    @Test fun audioWavOutputNeverRequiresOrMapsAVideoEncoder() {
        val wav = Container.valueOf("WAV")
        val pcm = AudioEncoder.valueOf("PCM_S16LE")
        val settings = Settings(container = wav, audio = pcm, stereo = false)
        val arguments = Planner.arguments(source, Trim(), settings, "in.wav", "out.wav")
        assertTrue("-vn" in arguments)
        assertFalse("-c:v" in arguments)
        assertEquals("pcm_s16le", arguments[arguments.indexOf("-c:a") + 1])
        assertFalse("-b:a" in arguments)
    }
    @Test fun decimalFormattingDoesNotDependOnThePhoneLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val edit = AudioEdit(nodes = listOf(AudioEffectNode("gain", "gain", parameters = GainParameters(-6.5))))
            val arguments = Planner.arguments(source, Trim(), Settings(container = Container.M4A, audioEdit = edit), "in.wav", "out.m4a")
            assertTrue("-af" in arguments)
            assertTrue(arguments[arguments.indexOf("-af") + 1].contains("volume=-6.5dB"))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun trimThenDoubleSpeedBudgetsFiveSecondsAndExactPcmFrames() {
        val measured = source.copy(audioStreams = listOf(SourceAudioFacts(sampleRateHz = 48000, channels = 1, durationUs = 30000000, totalSamples = 1440000)))
        val settings = Settings(container = Container.WAV, audio = AudioEncoder.PCM_F32LE, stereo = false,
            audioEdit = AudioEdit(rate = AudioRate(2, 1)))
        val plan = AudioGraphPlanner.plan(measured, Trim(5000, 15000), settings)
        assertEquals(5000000, plan.outputDurationUs)
        assertEquals(240000L, plan.outputFrames)
        assertTrue(plan.filters.contains("atempo=2"))
    }
    @Test fun linkedVideoCannotBeShortenedByAnAudioOnlySpeedChange() {
        val video = source.copy(videoTracks = 1, width = 640, height = 360)
        val settings = Settings(audioEdit = AudioEdit(rate = AudioRate(2, 1)))
        assertTrue(Planner.validate(video, Trim(), settings).any { "linked" in it })
    }
    @Test fun neutralPcmPreservesTheFractionalFinalSamplesOfTheWholeSource() {
        val measured = source.copy(durationMs = 1000, audioStreams = listOf(SourceAudioFacts(sampleRateHz = 44100,
            channels = 1, durationUs = 1000023, totalSamples = 44101)))
        val plan = AudioGraphPlanner.plan(measured, Trim(), Settings(container = Container.WAV, audio = AudioEncoder.PCM_F32LE, stereo = false))
        assertEquals(44101L, plan.outputFrames)
        assertTrue(plan.filters.contains("atrim=start_sample=0:end_sample=44101"))
    }
    @Test fun stereoRoutingUsesExplicitChannelMatrices() {
        val measured = source.copy(audioStreams = listOf(SourceAudioFacts(sampleRateHz = 48000, channels = 2, durationUs = 30000000)))
        fun graph(mode: ChannelMode) = AudioGraphPlanner.plan(measured, Trim(), Settings(container = Container.M4A,
            audioEdit = AudioEdit(output = AudioOutputPolicy(channels = mode)))).filters
        assertTrue(graph(ChannelMode.MONO).contains("pan=mono|c0=0.5*c0+0.5*c1"))
        assertTrue(graph(ChannelMode.SWAP).contains("pan=stereo|c0=c1|c1=c0"))
    }
}
