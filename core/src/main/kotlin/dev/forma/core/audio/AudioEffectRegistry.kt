package dev.forma.core.audio

import dev.forma.core.Capabilities

data class AudioParameterDescriptor(val id: String, val label: String, val unit: String,
    val minimum: Double? = null, val maximum: Double? = null)
data class AudioEffectDescriptor(val id: String, val name: String,
    val parameters: List<AudioParameterDescriptor>, val requiredFilters: Set<String>,
    val previewSupport: String = "Rendered PCM", val requiredAssets: Set<String> = emptySet(),
    val automationWhitelist: Set<String> = emptySet(), val hasLookAhead: Boolean = false)

/** Shared by editor controls, presets, graph planning and restored-job validation. */
object AudioEffectRegistry {
    private fun parameter(id: String, label: String, unit: String, low: Double, high: Double) =
        AudioParameterDescriptor(id, label, unit, low, high)
    val descriptors = listOf(
        AudioEffectDescriptor("gain", "Volume", listOf(parameter("gainDb", "Volume", "dB", -60.0, 24.0)), setOf("volume"), automationWhitelist = setOf("gainDb")),
        AudioEffectDescriptor("fades", "Fades", listOf(AudioParameterDescriptor("fadeInUs", "Fade in", "µs", 0.0), AudioParameterDescriptor("fadeOutUs", "Fade out", "µs", 0.0)), setOf("afade")),
        AudioEffectDescriptor("eq", "Equalizer", listOf(parameter("frequencyHz", "Frequency", "Hz", 20.0, 20000.0), parameter("gainDb", "Gain", "dB", -24.0, 24.0), parameter("q", "Q", "", 0.1, 18.0)), emptySet()),
        AudioEffectDescriptor("compressor", "Compressor", listOf(parameter("thresholdDb", "Threshold", "dB", -60.0, 0.0), parameter("ratio", "Ratio", ":1", 1.0, 20.0), parameter("kneeDb", "Knee", "dB", 0.0, 18.0), parameter("attackMs", "Attack", "ms", 0.01, 2000.0), parameter("releaseMs", "Release", "ms", 0.01, 9000.0), parameter("makeupDb", "Makeup gain", "dB", 0.0, 24.0)), setOf("acompressor")),
        AudioEffectDescriptor("limiter", "Peak limiter", listOf(parameter("ceilingDb", "Ceiling", "dB", -24.0, 0.0), parameter("attackMs", "Attack", "ms", 0.1, 80.0), parameter("releaseMs", "Release", "ms", 1.0, 8000.0)), setOf("alimiter"), hasLookAhead = true)
    )
    fun descriptor(type: String) = descriptors.firstOrNull { it.id == type }
    fun eqFilter(type: EqType): String = when (type) {
        EqType.BELL -> "equalizer"; EqType.LOW_SHELF -> "bass"; EqType.HIGH_SHELF -> "treble"
        EqType.HIGH_PASS -> "highpass"; EqType.LOW_PASS -> "lowpass"; EqType.BAND_PASS -> "bandpass"; EqType.NOTCH -> "bandreject"
    }
    fun requiredFilters(node: AudioEffectNode): Set<String> = if (node.parameters is EqParameters)
        node.parameters.bands.filter { it.enabled }.map { eqFilter(it.type) }.toSet()
        else descriptor(node.type)?.requiredFilters.orEmpty()

    fun validate(edit: AudioEdit, source: SourceAudioFacts, capabilities: Capabilities? = null): List<AudioProblem> = buildList {
        if (edit.schemaVersion != 1 || edit.preservedJson != null) {
            add(AudioProblem(message = "Audio edit version ${edit.schemaVersion} is unsupported. Reset the edits explicitly to continue."))
            return@buildList
        }
        fun problem(node: AudioEffectNode?, parameter: String?, message: String) { add(AudioProblem(node?.id, parameter, message)) }
        fun finite(node: AudioEffectNode?, id: String, value: Double, low: Double, high: Double) {
            if (!value.isFinite() || value < low || value > high) problem(node, id, "$id must be a finite value from $low to $high.")
        }
        fun requireFilters(node: AudioEffectNode?, name: String, filters: Set<String>) {
            if (capabilities?.available == true) for (filter in filters - capabilities.filters)
                problem(node, null, "$name requires the unavailable $filter filter.")
        }
        val ids = mutableSetOf<String>()
        if (edit.nodes.size > 32) problem(null, null, "Use at most 32 audio effects.")
        for (node in edit.nodes) {
            if (!node.id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) problem(node, null, "Invalid effect identifier.")
            if (!ids.add(node.id)) problem(node, null, "Duplicate effect identifier ${node.id}.")
            if (!node.enabled) continue
            val descriptor = descriptor(node.type)
            if (descriptor == null || node.version != 1 || node.parameters is UnsupportedParameters || node.preservedJson != null) {
                problem(node, null, "${node.type} version ${node.version} is unsupported. Bypass or remove this effect to continue.")
                continue
            }
            val expectedType = when (node.type) {
                "gain" -> node.parameters is GainParameters; "fades" -> node.parameters is FadeParameters
                "eq" -> node.parameters is EqParameters; "compressor" -> node.parameters is CompressorParameters
                "limiter" -> node.parameters is LimiterParameters; else -> false
            }
            if (!expectedType) { problem(node, null, "${descriptor.name} has incompatible parameters."); continue }
            requireFilters(node, descriptor.name, requiredFilters(node))
            when (val p = node.parameters) {
                is GainParameters -> finite(node, "gainDb", p.gainDb, -60.0, 24.0)
                is FadeParameters -> {
                    if (p.fadeInUs !in 0..source.durationUs) problem(node, "fadeInUs", "Fade in exceeds the selected audio duration.")
                    if (p.fadeOutUs !in 0..source.durationUs) problem(node, "fadeOutUs", "Fade out exceeds the selected audio duration.")
                }
                is EqParameters -> {
                    if (p.bands.size > 8) problem(node, null, "Use at most eight EQ bands.")
                    val bandIds = mutableSetOf<String>()
                    for (band in p.bands) {
                        if (!band.id.matches(Regex("[A-Za-z0-9._-]{1,64}"))) problem(node, null, "Invalid EQ band identifier.")
                        if (!bandIds.add(band.id)) problem(node, null, "Duplicate EQ band identifier ${band.id}.")
                        if (!band.enabled) continue
                        finite(node, "frequencyHz", band.frequencyHz, 20.0, 20000.0)
                        if (source.sampleRateHz == null || band.frequencyHz >= source.sampleRateHz / 2.0)
                            problem(node, "frequencyHz", "EQ frequency must be below the source Nyquist frequency; the sample rate must be known.")
                        finite(node, "gainDb", band.gainDb, -24.0, 24.0)
                        finite(node, "q", band.q, 0.1, 18.0)
                        if (band.type in setOf(EqType.HIGH_PASS, EqType.LOW_PASS) && band.slopeDbPerOctave !in setOf(12, 24))
                            problem(node, "slopeDbPerOctave", "Supported pass-filter slopes are 12 or 24 dB/octave.")
                    }
                }
                is CompressorParameters -> {
                    finite(node, "thresholdDb", p.thresholdDb, -60.0, 0.0); finite(node, "ratio", p.ratio, 1.0, 20.0)
                    finite(node, "kneeDb", p.kneeDb, 0.0, 18.0); finite(node, "attackMs", p.attackMs, 0.01, 2000.0)
                    finite(node, "releaseMs", p.releaseMs, 0.01, 9000.0); finite(node, "makeupDb", p.makeupDb, 0.0, 24.0)
                }
                is LimiterParameters -> {
                    finite(node, "ceilingDb", p.ceilingDb, -24.0, 0.0); finite(node, "attackMs", p.attackMs, 0.1, 80.0)
                    finite(node, "releaseMs", p.releaseMs, 1.0, 8000.0)
                }
                is UnsupportedParameters -> Unit
            }
        }
        val output = edit.output
        if (output.sampleRateHz != null && output.sampleRateHz !in 8000..192000) problem(null, "sampleRateHz", "Sample rate must be between 8000 and 192000 Hz.")
        if (output.sampleRateHz != null && output.sampleRateHz != source.sampleRateHz) requireFilters(null, "Resampling", setOf("aresample"))
        if (output.channels != null && output.channels != ChannelMode.SOURCE) {
            requireFilters(null, "Channel routing", setOf("pan"))
            if (output.channels in setOf(ChannelMode.LEFT, ChannelMode.RIGHT, ChannelMode.SWAP) && source.channels != 2)
                problem(null, "channels", "Channel extraction and swap require a known stereo source.")
            if (output.channels == ChannelMode.MONO && source.channels !in setOf(1, 2))
                problem(null, "channels", "Mono downmix is qualified for mono or stereo sources.")
        }
        val normalization = output.normalization
        if (normalization.mode == NormalizationMode.PEAK) {
            finite(null, "peakDb", normalization.peakDb, -12.0, 0.0)
            requireFilters(null, "Peak normalization", setOf("astats", "volume"))
        }
        if (normalization.mode == NormalizationMode.LOUDNESS) {
            finite(null, "integratedLufs", normalization.integratedLufs, -35.0, -5.0)
            finite(null, "truePeakDb", normalization.truePeakDb, -9.0, 0.0)
            requireFilters(null, "Loudness normalization", setOf("loudnorm"))
        }
    }
}
