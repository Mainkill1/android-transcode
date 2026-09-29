package dev.forma.core.audio

import dev.forma.core.Capabilities
import dev.forma.core.*
import org.junit.Assert.*
import org.junit.Test

class AudioEditTest {
    private val facts = SourceAudioFacts(sampleRateHz = 48000, channels = 2, durationUs = 30000000)
    private val caps = Capabilities(available = true, filters = setOf("volume", "afade", "equalizer", "bass", "treble", "highpass", "lowpass", "bandpass", "bandreject", "acompressor", "alimiter", "pan", "aresample", "loudnorm", "astats"))
    private fun gain(value: Double, id: String = "gain-1") = AudioEffectNode(id, "gain", parameters = GainParameters(value))

    @Test fun neutralDefaultsDoNotRequestAnyProcessing() {
        val edit = AudioEdit()
        assertTrue(edit.nodes.isEmpty())
        assertEquals(NormalizationMode.OFF, edit.output.normalization.mode)
        assertNull(edit.output.sampleRateHz)
        assertTrue(AudioEffectRegistry.validate(edit, facts, caps).isEmpty())
    }
    @Test fun queuedSnapshotCannotBeMutatedThroughCallerOwnedLists() {
        val bands = mutableListOf(EqBand("band-1", frequencyHz = 1000.0, gainDb = 3.0))
        val nodes = mutableListOf(AudioEffectNode("eq-1", "eq", parameters = EqParameters(bands)))
        val queued = AudioEdit(nodes = nodes)
        nodes.clear(); bands.clear()
        assertEquals(1, queued.nodes.size)
        assertEquals(3.0, (queued.nodes[0].parameters as EqParameters).bands.single().gainDb, 0.0)
    }
    @Test fun duplicateEffectAndBandIdsAreRejected() {
        assertTrue(AudioEffectRegistry.validate(AudioEdit(nodes = listOf(gain(0.0), gain(-6.0))), facts, caps).any { "Duplicate" in it.message })
        val eq = EqParameters(listOf(EqBand("same"), EqBand("same")))
        assertTrue(AudioEffectRegistry.validate(AudioEdit(nodes = listOf(AudioEffectNode("eq-1", "eq", parameters = eq))), facts, caps).any { "Duplicate" in it.message })
    }
    @Test fun nonFiniteAndOutOfRangeGainCannotReachTheCompiler() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -61.0, 25.0)) {
            assertTrue("Invalid gain $value", AudioEffectRegistry.validate(AudioEdit(nodes = listOf(gain(value))), facts, caps).any { it.parameterId == "gainDb" })
        }
        assertTrue(AudioEffectRegistry.validate(AudioEdit(nodes = listOf(gain(-6.0))), facts, caps).isEmpty())
    }
    @Test fun eqFrequencyMustBeStrictlyBelowTheSourceNyquist() {
        val edit = AudioEdit(nodes = listOf(AudioEffectNode("eq-1", "eq", parameters = EqParameters(listOf(EqBand("band-1", frequencyHz = 24000.0))))))
        assertTrue(AudioEffectRegistry.validate(edit, facts, caps).any { it.parameterId == "frequencyHz" })
        val atLowRate = edit.copy(nodes = listOf(AudioEffectNode("eq-1", "eq", parameters = EqParameters(listOf(EqBand("band-1", frequencyHz = 22050.0))))))
        assertTrue(AudioEffectRegistry.validate(atLowRate, facts.copy(sampleRateHz = 44100), caps).any { it.parameterId == "frequencyHz" })
    }
    @Test fun unavailableEnabledEffectsBlockWithTheirNameButBypassAllowsExport() {
        val node = AudioEffectNode("voice", "compressor", parameters = CompressorParameters())
        val missing = caps.copy(filters = caps.filters - "acompressor")
        assertTrue(AudioEffectRegistry.validate(AudioEdit(nodes = listOf(node)), facts, missing).any { it.message.contains("Compressor") && it.message.contains("acompressor") })
        assertTrue(AudioEffectRegistry.validate(AudioEdit(nodes = listOf(node.copy(enabled = false))), facts, missing).isEmpty())
    }
    @Test fun unknownEffectIsPreservedAndCannotRunUntilExplicitlyBypassed() {
        val node = AudioEffectNode("future", "future-effect", parameters = UnsupportedParameters("{\"amount\":7}"))
        assertTrue(AudioEffectRegistry.validate(AudioEdit(nodes = listOf(node)), facts, caps).any { it.message.contains("future-effect") })
        assertTrue(AudioEffectRegistry.validate(AudioEdit(nodes = listOf(node.copy(enabled = false))), facts, caps).isEmpty())
    }
    @Test fun fadeCannotExceedSelectedProgramAndRoutingRequiresKnownStereo() {
        val fade = AudioEffectNode("fade", "fades", parameters = FadeParameters(5000001, 0))
        assertTrue(AudioEffectRegistry.validate(AudioEdit(nodes = listOf(fade)), facts.copy(durationUs = 5000000), caps).any { it.parameterId == "fadeInUs" })
        val swap = AudioEdit(output = AudioOutputPolicy(channels = ChannelMode.SWAP))
        assertTrue(AudioEffectRegistry.validate(swap, facts.copy(channels = 1), caps).any { it.parameterId == "channels" })
    }
    @Test fun futureEditVersionCannotBeSilentlyInterpretedAsNeutral() {
        assertTrue(AudioEffectRegistry.validate(AudioEdit(schemaVersion = 9, preservedJson = "{\"schema\":9,\"future\":true}"), facts, caps).isNotEmpty())
    }
    @Test fun productionExportValidationBlocksAnUnsupportedRestoredEffect() {
        val source = Source("content://files/a", "A.wav", 30000, audioTracks = 1)
        val edit = AudioEdit(nodes = listOf(AudioEffectNode("saved", "future-effect", parameters = UnsupportedParameters("{}"))))
        val problems = Planner.validate(source, Trim(), Settings(container = Container.M4A, audioEdit = edit))
        assertTrue(problems.any { "future-effect" in it })
    }
}
