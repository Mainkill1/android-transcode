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

    @Test fun linkedVideoKeepsItsDurationWhenAudioEndsEarly() {
        val source = Source("input", "clip.mp4", 30000, width=320, height=240, videoTracks=1, audioTracks=1,
            audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=1,durationUs=20000000,totalSamples=960000)))
        val settings = Settings(audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("gain", "gain", parameters=GainParameters(-6.0)))))
        val graph=AudioGraphPlanner.plan(source,Trim(),settings)
        assertEquals(30000000L,graph.outputDurationUs)
        assertEquals(1440000L,graph.outputFrames)
        val args=Planner.arguments(source,Trim(),settings,"input","output")
        assertEquals("30.000",args[args.indexOf("-t")+1])
    }

    @Test fun unsupportedExplicitAacClockIsRejectedBeforeNativeExecution() {
        val source=Source("in","in.wav",2000,audioTracks=1,audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=1,durationUs=2000000)))
        val settings=Settings(container=Container.M4A,audioEdit=AudioEdit(output=AudioOutputPolicy(sampleRateHz=192000)))
        assertTrue(Planner.validate(source,Trim(),settings).any { "AAC" in it && "rate" in it })
    }
    @Test fun opusDecodedClockIsPlannedAt48Khz() {
        val source=Source("in","clip",2000,videoTracks=1,audioTracks=1,audioStreams=listOf(SourceAudioFacts(sampleRateHz=44100,channels=2,durationUs=2000000)))
        assertEquals(48000,AudioGraphPlanner.plan(source,Trim(),Settings(container=Container.WEBM,video=VideoEncoder.VP9,audio=AudioEncoder.OPUS)).sampleRateHz)
    }

    @Test fun linkedOffsetIsBlockedUntilItsProcessingIsQualified() {
        val source=Source("in","clip",2000,width=320,height=240,videoTracks=1,audioTracks=1,audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=2,durationUs=1500000,timelineOffsetUs=500000)))
        val settings=Settings(audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("g","gain",parameters=GainParameters(-6.0)))))
        assertTrue(Planner.validate(source,Trim(),settings).any { "offset" in it })
    }

    @Test fun fullLengthFadeOnNonAlignedTrimCannotStartBeforeZero() {
        val source=Source("in","in.wav",2000,audioTracks=1,audioStreams=listOf(SourceAudioFacts(sampleRateHz=44100,channels=1,durationUs=2000000)))
        val settings=Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("fade","fades",parameters=FadeParameters(fadeOutUs=1001000)))))
        assertFalse(AudioGraphPlanner.plan(source,Trim(endMs=1001),settings).filters.any { "st=-" in it })
    }

    @Test fun legacyStereoRoutingOccursInsideTheAnalyzedCreativeGraph() {
        val source=Source("in","mono",2000,audioTracks=1,audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=1,durationUs=2000000)))
        val settings=Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,stereo=true,audioEdit=AudioEdit(output=AudioOutputPolicy(normalization=NormalizationPolicy(mode=NormalizationMode.LOUDNESS))))
        assertTrue(AudioGraphPlanner.plan(source,Trim(),settings).filters.contains("aformat=channel_layouts=stereo"))
    }

    @Test fun pcmTransportPreservesLinkedProgramAndFadeTiming() {
        val source=Source("in","clip.mp4",8000,width=320,height=240,videoTracks=1,audioTracks=1,
            audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=1,durationUs=6000000,totalSamples=288000)))
        val settings=Settings(audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("fade","fades",parameters=FadeParameters(fadeOutUs=2000000)))))
        val args=Planner.audioArguments(source,Trim(),settings,"in","preview.wav")
        assertFalse("-c:v" in args)
        assertTrue(args[args.indexOf("-af")+1].contains("afade=t=out:st=6:d=2"))
        assertTrue(args[args.indexOf("-af")+1].contains("apad=whole_len=384000"))
    }

    @Test fun sampleIndexedTrimAndPaddingKeepTheirClockDuringNativeRateNegotiation() {
        val source=Source("in","tone.wav",10000,audioTracks=1,audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=1,durationUs=10000000,totalSamples=480000)))
        val graph=AudioGraphPlanner.plan(source,Trim(),Settings(container=Container.M4A,audioEdit=AudioEdit(output=AudioOutputPolicy(normalization=NormalizationPolicy(mode=NormalizationMode.LOUDNESS)))))
        assertEquals("aformat=sample_rates=48000",graph.filters.first())
        val pad=graph.filters.indexOf("apad=whole_len=480000")
        assertEquals("aformat=sample_rates=48000",graph.filters[pad-1])
    }

    @Test fun higherPrecisionSourceStaysDoubleUntilTheSelectedEncodingBoundary() {
        val source=Source("in","double.wav",1000,audioTracks=1,audioStreams=listOf(SourceAudioFacts(sampleRateHz=48000,channels=1,sampleFormat="dbl",durationUs=1000000)))
        val settings=Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("g","gain",parameters=GainParameters(-6.0)))))
        val filters=AudioGraphPlanner.plan(source,Trim(),settings).filters
        assertTrue(filters.contains("aformat=sample_fmts=dblp"))
        assertTrue(filters.contains("volume=-6dB:precision=double"))
    }
}
