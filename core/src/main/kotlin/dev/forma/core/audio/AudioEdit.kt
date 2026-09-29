package dev.forma.core.audio

import java.util.Collections

/** Times refer to the post-trim audio program, in integer microseconds. */
data class SourceAudioFacts(val streamIndex: Int = 0, val sampleRateHz: Int? = null,
    val channels: Int? = null, val channelLayout: String? = null, val sampleFormat: String? = null,
    val durationUs: Long = 0, val encoderDelaySamples: Long? = null, val paddingSamples: Long? = null)

enum class EqType { BELL, LOW_SHELF, HIGH_SHELF, HIGH_PASS, LOW_PASS, BAND_PASS, NOTCH }
enum class ChannelMode { SOURCE, MONO, STEREO, LEFT, RIGHT, SWAP }
enum class NormalizationMode { OFF, PEAK, LOUDNESS }
sealed interface AudioParameters
data class GainParameters(val gainDb: Double = 0.0, val muted: Boolean = false) : AudioParameters
data class FadeParameters(val fadeInUs: Long = 0, val fadeOutUs: Long = 0) : AudioParameters
data class EqBand(val id: String, val type: EqType = EqType.BELL, val frequencyHz: Double = 1000.0,
    val gainDb: Double = 0.0, val q: Double = 0.707, val slopeDbPerOctave: Int = 12, val enabled: Boolean = true)
data class EqParameters(val bands: List<EqBand> = emptyList()) : AudioParameters
data class CompressorParameters(val thresholdDb: Double = -18.0, val ratio: Double = 2.0,
    val kneeDb: Double = 6.0, val attackMs: Double = 20.0, val releaseMs: Double = 250.0,
    val makeupDb: Double = 0.0) : AudioParameters
data class LimiterParameters(val ceilingDb: Double = -1.0, val attackMs: Double = 5.0,
    val releaseMs: Double = 50.0) : AudioParameters
/** Future parameters are retained without turning them into executable filter strings. */
data class UnsupportedParameters(val json: String) : AudioParameters
data class AudioEffectNode(val id: String, val type: String, val version: Int = 1,
    val enabled: Boolean = true, val parameters: AudioParameters, val preservedJson: String? = null)
data class NormalizationPolicy(val mode: NormalizationMode = NormalizationMode.OFF,
    val peakDb: Double = -1.0, val integratedLufs: Double = -16.0, val truePeakDb: Double = -1.5,
    val preserveDynamics: Boolean = true)
/** Null channel/rate retains the legacy encoding policy until explicitly edited. */
data class AudioOutputPolicy(val channels: ChannelMode? = null, val sampleRateHz: Int? = null,
    val normalization: NormalizationPolicy = NormalizationPolicy())
/** Defensive snapshots protect queued jobs even when a caller supplied mutable lists. */
class AudioEdit(val schemaVersion: Int = 1, nodes: List<AudioEffectNode> = emptyList(),
    val output: AudioOutputPolicy = AudioOutputPolicy(), val preservedJson: String? = null) {
    val nodes: List<AudioEffectNode> = Collections.unmodifiableList(nodes.map { node ->
        val parameters = node.parameters
        if (parameters is EqParameters) node.copy(parameters = parameters.copy(bands =
            Collections.unmodifiableList(ArrayList(parameters.bands)))) else node
    })
    fun copy(schemaVersion: Int = this.schemaVersion, nodes: List<AudioEffectNode> = this.nodes,
        output: AudioOutputPolicy = this.output, preservedJson: String? = this.preservedJson) =
        AudioEdit(schemaVersion, nodes, output, preservedJson)
    override fun equals(other: Any?) = other is AudioEdit && schemaVersion == other.schemaVersion &&
        nodes == other.nodes && output == other.output && preservedJson == other.preservedJson
    override fun hashCode() = 31 * (31 * (31 * schemaVersion + nodes.hashCode()) + output.hashCode()) + (preservedJson?.hashCode() ?: 0)
    override fun toString() = "AudioEdit(schemaVersion=$schemaVersion, nodes=$nodes, output=$output)"
}
data class AudioProblem(val nodeId: String? = null, val parameterId: String? = null, val message: String)
