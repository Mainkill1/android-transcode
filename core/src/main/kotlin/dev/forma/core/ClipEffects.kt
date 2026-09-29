package dev.forma.core

import java.util.Locale

/** Inspected source pixel coordinates, before this clip's rotation. No arbitrary filter strings. */
data class CropRect(val x: Int, val y: Int, val width: Int, val height: Int)
enum class QuarterTurn { NONE, CLOCKWISE, HALF, COUNTERCLOCKWISE }

/** Immutable per-clip values, copied into Settings when a job is queued. Times are output milliseconds. */
data class ClipEffects(
    val crop: CropRect? = null,
    val rotation: QuarterTurn = QuarterTurn.NONE,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val speedPercent: Int = 100,
    val brightnessPercent: Int = 0,
    val contrastPercent: Int = 100,
    val saturationPercent: Int = 100,
    val gammaPercent: Int = 100,
    val blurSigma: Int = 0,
    val sharpen: Boolean = false,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val volumePercent: Int = 100,
    val audioFadeInMs: Long = 0,
    val audioFadeOutMs: Long = 0,
    val normalizeAudio: Boolean = false
) {
    init {
        require(speedPercent in 25..400) { "Speed must be 25–400 percent." }
        require(brightnessPercent in -100..100 && contrastPercent in 0..300 && saturationPercent in 0..300 && gammaPercent in 10..1000) { "Color adjustment is outside its supported range." }
        require(blurSigma in 0..20 && volumePercent in 0..400) { "Blur or volume is outside its supported range." }
        require(listOf(fadeInMs, fadeOutMs, audioFadeInMs, audioFadeOutMs).all { it >= 0 }) { "Fade durations cannot be negative." }
    }
    val hasVisual: Boolean get() = crop != null || rotation != QuarterTurn.NONE || flipHorizontal || flipVertical ||
        brightnessPercent != 0 || contrastPercent != 100 || saturationPercent != 100 || gammaPercent != 100 ||
        blurSigma != 0 || sharpen || fadeInMs != 0L || fadeOutMs != 0L
    val hasAudio: Boolean get() = volumePercent != 100 || audioFadeInMs != 0L || audioFadeOutMs != 0L || normalizeAudio
    val isNeutral: Boolean get() = !hasVisual && !hasAudio && speedPercent == 100
}

/** Pure compilation shared by UI jobs, device tests and future timeline renderers. */
object EditPipeline {
    private fun decimal(value: Double) = String.format(Locale.ROOT, "%.3f", value)
    private fun seconds(value: Long) = decimal(value / 1000.0)

    fun duration(source: Source, trim: Trim, effects: ClipEffects): Long {
        val end = trim.endMs ?: source.durationMs
        require(trim.startMs >= 0 && end <= source.durationMs && end > trim.startMs) { "Choose a valid source range." }
        val selected = end - trim.startMs
        require(selected <= Long.MAX_VALUE / 100) { "Source duration is too large." }
        return (selected * 100 / effects.speedPercent).also { require(it > 0) { "Edited duration must be at least one millisecond." } }
    }

    fun validate(source: Source, trim: Trim, settings: Settings, caps: Capabilities?): List<String> = buildList {
        val e = settings.effects
        if (e.isNeutral) return@buildList
        val video = !settings.container.audioOnly
        val audio = source.audioTracks > 0 && settings.audio != AudioEncoder.NONE
        if (!video && e.hasVisual) add("Audio-only output cannot apply visual edits. Reset picture edits explicitly first.")
        if (!audio && e.hasAudio) add("Audio edits require an included source audio track.")
        if (video && settings.video.hardware) add("Edited hardware exports are not qualified yet. Select a software encoder or reset clip edits.")
        e.crop?.let { c ->
            if (c.x < 0 || c.y < 0 || c.width < 4 || c.height < 4 ||
                c.x % 2 != 0 || c.y % 2 != 0 || c.width % 2 != 0 || c.height % 2 != 0 ||
                c.x.toLong() + c.width > source.width || c.y.toLong() + c.height > source.height)
                add("Crop must use even pixel coordinates and dimensions inside the inspected source, at least 4 × 4.")
        }
        val duration = runCatching { if(settings.container.audioOnly)dev.forma.core.audio.AudioGraphPlanner.sourceFacts(source,trim,settings).durationUs/1000 else duration(source,trim,e) }.getOrNull()
        if (duration == null) add("The edited duration is invalid.") else {
            fun checkFades(start: Long, end: Long, label: String) {
                if (start > duration || end > duration || start > duration - end)
                    add("$label fades cannot overlap or exceed the edited duration.")
            }
            checkFades(e.fadeInMs, e.fadeOutMs, "Video")
            checkFades(e.audioFadeInMs, e.audioFadeOutMs, "Audio")
        }
        if (caps?.available == true && isEmpty()) {
            val required = buildList {
                if (video) addAll(videoFilters(source, trim, settings))
                if (audio) addAll(audioFilters(source, trim, settings))
            }.map { it.substringBefore('=') }.toSet()
            required.filterNot { it in caps.filters }.sorted().forEach { add("Required edit filter $it is unavailable in this FFmpeg build.") }
        }
    }

    fun videoFilters(source: Source, trim: Trim, settings: Settings, forceTrim: Boolean = false): List<String> {
        val e = settings.effects
        if (e.isNeutral && !forceTrim) return emptyList()
        val total = duration(source, trim, e)
        return buildList {
            add("trim=start=${seconds(trim.startMs)}:end=${seconds(trim.endMs ?: source.durationMs)}")
            // Use one trim origin for both streams; resetting each STARTPTS loses intentional A/V offsets.
            add("setpts=PTS-${seconds(trim.startMs)}/TB")
            if (e.speedPercent != 100) add("setpts=PTS/${decimal(e.speedPercent / 100.0)}")
            e.crop?.let { add("crop=${it.width}:${it.height}:${it.x}:${it.y}") }
            when (e.rotation) {
                QuarterTurn.NONE -> Unit
                QuarterTurn.CLOCKWISE -> add("transpose=clock")
                QuarterTurn.HALF -> { add("hflip"); add("vflip") }
                QuarterTurn.COUNTERCLOCKWISE -> add("transpose=cclock")
            }
            if (e.flipHorizontal) add("hflip")
            if (e.flipVertical) add("vflip")
            if (e.brightnessPercent != 0 || e.contrastPercent != 100 || e.saturationPercent != 100 || e.gammaPercent != 100)
                add("eq=brightness=${decimal(e.brightnessPercent / 100.0)}:contrast=${decimal(e.contrastPercent / 100.0)}:saturation=${decimal(e.saturationPercent / 100.0)}:gamma=${decimal(e.gammaPercent / 100.0)}")
            if (e.blurSigma > 0) add("gblur=sigma=${e.blurSigma}")
            if (e.sharpen) add("unsharp=5:5:0.8:5:5:0")
            if (e.fadeInMs > 0) add("fade=t=in:st=0:d=${seconds(e.fadeInMs)}")
            if (e.fadeOutMs > 0) add("fade=t=out:st=${seconds(total - e.fadeOutMs)}:d=${seconds(e.fadeOutMs)}")
        }
    }

    fun audioFilters(source: Source, trim: Trim, settings: Settings, forceTrim: Boolean = false): List<String> {
        val e = settings.effects
        if (e.isNeutral && !forceTrim) return emptyList()
        val total = duration(source, trim, e)
        return buildList {
            add("atrim=start=${seconds(trim.startMs)}:end=${seconds(trim.endMs ?: source.durationMs)}")
            add("asetpts=PTS-${seconds(trim.startMs)}/TB")
            var tempo = e.speedPercent / 100.0
            while (tempo > 2.0) { add("atempo=2.000"); tempo /= 2.0 }
            while (tempo < 0.5) { add("atempo=0.500"); tempo /= 0.5 }
            if (tempo != 1.0) add("atempo=${decimal(tempo)}")
            // atempo retimes samples but keeps its initial PTS; scale that initial offset too.
            if (e.speedPercent != 100) add("asetpts=PTS-STARTPTS+STARTPTS/${decimal(e.speedPercent / 100.0)}")
            if (e.normalizeAudio) { add("loudnorm=I=-16:TP=-1.5:LRA=11"); add("aresample=48000") }
            if (e.volumePercent != 100) add("volume=${decimal(e.volumePercent / 100.0)}")
            if (e.audioFadeInMs > 0) add("afade=t=in:st=0:d=${seconds(e.audioFadeInMs)}")
            if (e.audioFadeOutMs > 0) add("afade=t=out:st=${seconds(total - e.audioFadeOutMs)}:d=${seconds(e.audioFadeOutMs)}")
        }
    }
}
