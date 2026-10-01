package dev.forma.core.audio

import dev.forma.core.*
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.pow

data class AudioGraphPlan(val filters: List<String> = emptyList(), val outputDurationUs: Long = 0,
    val outputFrames: Long? = null, val sampleRateHz: Int? = null, val channels: Int? = null,
    val requiredFilters: Set<String> = emptySet(), val identity: String = "", val processed: Boolean = false,
    val originNormalized: Boolean = false)

object AudioGraphPlanner {
    fun number(value: Double): String {
        require(value.isFinite()) { "Audio parameters must be finite." }
        return String.format(Locale.ROOT, "%.9f", value).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    }
    /** One rounding rule: nearest sample/time, ties upward; no accumulated floating-point times. */
    fun roundRatio(value: Long, multiplier: Long, divisor: Long): Long {
        require(value >= 0 && multiplier >= 0 && divisor > 0)
        val d = BigInteger.valueOf(divisor)
        return BigInteger.valueOf(value).multiply(BigInteger.valueOf(multiplier))
            .add(d.divide(BigInteger.valueOf(2))).divide(d).toString().toLong()
    }
    fun processingRequested(settings:Settings):Boolean = settings.audioEdit.nodes.any {it.enabled} ||
        settings.audioEdit.output!=AudioOutputPolicy() || settings.audioEdit.rate.value!=1.0 || settings.container in setOf(Container.WAV,Container.FLAC)
    private fun commonOrigin(settings:Settings):Boolean = !settings.effects.isNeutral && (!settings.container.audioOnly || !processingRequested(settings))
    fun sourceFacts(source: Source, trim: Trim, settings: Settings, preserveTimeline:Boolean=commonOrigin(settings)): SourceAudioFacts {
        val original = source.audioStreams.getOrNull(settings.audioTrack) ?: SourceAudioFacts(streamIndex = settings.audioTrack)
        val fallback = source.durationMs.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
        val end = trim.endMs?.coerceIn(0, Long.MAX_VALUE / 1000)?.times(1000) ?: original.durationUs.takeIf {it>0 && settings.container.audioOnly && !preserveTimeline} ?: fallback
        val selected = (end - trim.startMs.coerceIn(0, Long.MAX_VALUE / 1000) * 1000).coerceAtLeast(0)
        val rate = settings.audioEdit.rate
        return original.copy(durationUs = if (rate.numerator > 0 && rate.denominator > 0)
            roundRatio(selected, rate.denominator.toLong()*100, rate.numerator.toLong()*settings.effects.speedPercent) else 0)
    }

    fun plan(source: Source, trim: Trim, settings: Settings, forceProcessed: Boolean = false, preserveTimeline: Boolean = commonOrigin(settings)): AudioGraphPlan {
        val edit = settings.audioEdit
        val facts = sourceFacts(source,trim,settings,preserveTimeline)
        val problems = AudioEffectRegistry.validate(edit, facts)
        require(problems.isEmpty()) { problems.joinToString("\n") { it.message } }
        val measured = source.audioStreams.getOrNull(settings.audioTrack)
        val outputRate = if (settings.audio == AudioEncoder.OPUS) 48000 else edit.output.sampleRateHz ?: measured?.sampleRateHz
        val outputChannels = when (edit.output.channels) {
            ChannelMode.MONO, ChannelMode.LEFT, ChannelMode.RIGHT -> 1
            ChannelMode.STEREO, ChannelMode.SWAP -> 2
            ChannelMode.SOURCE -> measured?.channels
            null -> if (settings.stereo) 2 else measured?.channels
        }
        val clip=settings.effects
        val processed = !clip.isNeutral || forceProcessed || edit.nodes.any { it.enabled } || edit.output != AudioOutputPolicy() || edit.rate.value != 1.0 ||
            settings.container in setOf(Container.WAV, Container.FLAC)
        if (!processed) return AudioGraphPlan(outputDurationUs = facts.durationUs, sampleRateHz = outputRate, channels = outputChannels)
        val inputRate = measured?.sampleRateHz
        val startUs = trim.startMs * 1000
        val endUs = trim.endMs?.times(1000) ?: measured?.durationUs?.takeIf {it>0 && settings.container.audioOnly && !preserveTimeline} ?: source.durationMs * 1000
        val startSample = inputRate?.let { roundRatio(startUs, it.toLong(), 1000000) }
        val endSample = if (inputRate != null) {
            if (trim.endMs == null && measured.totalSamples != null) measured.totalSamples
            else roundRatio(endUs, inputRate.toLong(), 1000000).let { end -> measured.totalSamples?.let { end.coerceAtMost(it) } ?: end }
        } else null
        val outputFrames = if(preserveTimeline && outputRate!=null)
            roundRatio(endUs-startUs,outputRate.toLong()*edit.rate.denominator*100,1000000L*edit.rate.numerator*clip.speedPercent)
        else if (startSample != null && endSample != null && outputRate != null)
            if (!settings.container.audioOnly) roundRatio(endUs-startUs,outputRate.toLong()*100,1000000L*clip.speedPercent)
            else roundRatio(endSample - startSample, outputRate.toLong()*edit.rate.denominator*100,inputRate.toLong()*edit.rate.numerator*clip.speedPercent) else null
        val durationUs = if (outputFrames != null && outputRate != null) roundRatio(outputFrames, 1000000, outputRate.toLong()) else facts.durationUs
        val doublePrecision = measured?.sampleFormat in setOf("dbl","dblp","s32","s32p","s64","s64p")
        val precision = if (doublePrecision) "double" else "float"
        val filters = buildList {
            inputRate?.let { add("aformat=sample_rates=$it") }
            if (!preserveTimeline && startSample != null && endSample != null) add("atrim=start_sample=$startSample:end_sample=$endSample")
            else add("atrim=start=${number(startUs / 1000000.0)}:end=${number(endUs / 1000000.0)}")
            add(if(preserveTimeline) "asetpts=PTS-${number(trim.startMs/1000.0)}/TB" else "asetpts=PTS-STARTPTS")
            var tempo=edit.rate.value*clip.speedPercent/100.0
            while(tempo>2.0){add("atempo=2.000");tempo/=2.0}
            while(tempo<0.5){add("atempo=0.500");tempo/=0.5}
            if(tempo!=1.0)add("atempo=${number(tempo)}")
            val speed=edit.rate.value*clip.speedPercent/100.0
            if(preserveTimeline && speed!=1.0)add("asetpts=PTS-STARTPTS+STARTPTS/${number(speed)}")
            // Legacy clip controls precede the ordered AudioEdit nodes; both remain explicit.
            if(clip.normalizeAudio){add("loudnorm=I=-16:TP=-1.5:LRA=11");add("aresample=48000")}
            if(clip.volumePercent!=100)add("volume=${String.format(Locale.ROOT,"%.3f",clip.volumePercent/100.0)}")
            if(clip.audioFadeInMs>0)add("afade=t=in:st=0:d=${number(clip.audioFadeInMs/1000.0)}")
            if(clip.audioFadeOutMs>0)add("afade=t=out:st=${number((durationUs-clip.audioFadeOutMs*1000).coerceAtLeast(0)/1000000.0)}:d=${number(clip.audioFadeOutMs/1000.0)}")
            if (edit.nodes.any { it.enabled }) add("aformat=sample_fmts=${if(doublePrecision) "dblp" else "fltp"}")
            for (node in edit.nodes.filter { it.enabled }) when (val p = node.parameters) {
                is GainParameters -> if (p.muted || p.gainDb != 0.0) add(if (p.muted) "volume=0:precision=$precision" else "volume=${number(p.gainDb)}dB:precision=$precision")
                is FadeParameters -> {
                    if (p.fadeInUs > 0) add("afade=t=in:st=0:d=${number(p.fadeInUs.coerceAtMost(durationUs) / 1000000.0)}")
                    if (p.fadeOutUs > 0) add("afade=t=out:st=${number((durationUs - p.fadeOutUs).coerceAtLeast(0) / 1000000.0)}:d=${number(p.fadeOutUs.coerceAtMost(durationUs) / 1000000.0)}")
                }
                is EqParameters -> for (band in p.bands.filter { it.enabled }) {
                    if (band.type in setOf(EqType.BELL, EqType.LOW_SHELF, EqType.HIGH_SHELF) && band.gainDb == 0.0) continue
                    val filter = AudioEffectRegistry.eqFilter(band.type)
                    val frequency = "f=${number(band.frequencyHz)}:t=q:w=${number(band.q)}"
                    val gain = if (band.type in setOf(EqType.BELL, EqType.LOW_SHELF, EqType.HIGH_SHELF)) ":g=${number(band.gainDb)}" else ""
                    repeat(if (band.type in setOf(EqType.HIGH_PASS, EqType.LOW_PASS) && band.slopeDbPerOctave == 24) 2 else 1) {
                        add("$filter=$frequency$gain")
                    }
                }
                is CompressorParameters -> add("acompressor=threshold=${number(10.0.pow(p.thresholdDb / 20))}:ratio=${number(p.ratio)}:knee=${number(10.0.pow(p.kneeDb / 20))}:attack=${number(p.attackMs)}:release=${number(p.releaseMs)}:makeup=${number(10.0.pow(p.makeupDb / 20))}")
                is LimiterParameters -> add("alimiter=limit=${number(10.0.pow(p.ceilingDb / 20))}:attack=${number(p.attackMs)}:release=${number(p.releaseMs)}:level=0:latency=1")
                is UnsupportedParameters -> error("Unsupported audio effect reached the compiler.")
            }
            when (edit.output.channels) {
                ChannelMode.MONO -> add(if (measured?.channels == 1) "pan=mono|c0=c0" else "pan=mono|c0=0.5*c0+0.5*c1")
                ChannelMode.STEREO -> add(if (measured?.channels == 1) "pan=stereo|c0=c0|c1=c0" else "pan=stereo|c0=c0|c1=c1")
                ChannelMode.LEFT -> add("pan=mono|c0=c0")
                ChannelMode.RIGHT -> add("pan=mono|c0=c1")
                ChannelMode.SWAP -> add("pan=stereo|c0=c1|c1=c0")
                else -> Unit
            }
            if (edit.output.channels == null && settings.stereo) add("aformat=channel_layouts=stereo")
            if (outputRate != null && outputRate != inputRate) add("aresample=$outputRate")
            if (outputFrames != null && (!preserveTimeline || measured?.timelineOffsetUs==null || measured.timelineOffsetUs==0L)) {
                add("aformat=sample_rates=$outputRate")
                add("apad=whole_len=$outputFrames")
                add("atrim=end_sample=$outputFrames")
            }
        }
        val identityText = listOf(filters.joinToString(","), outputRate, outputChannels, durationUs, edit.output.normalization).joinToString("|")
        val identity = MessageDigest.getInstance("SHA-256").digest(identityText.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return AudioGraphPlan(filters, durationUs, outputFrames, outputRate, outputChannels,
            filters.map { it.substringBefore('=') }.toSet(), identity, true,!preserveTimeline)
    }
}
