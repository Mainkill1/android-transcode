package dev.forma.core

import java.util.Locale
import dev.forma.core.audio.*

/** A single SDR canvas. FIT keeps the whole picture; FILL crops; STRETCH distorts explicitly. */
enum class CanvasFit { FIT, FILL, STRETCH }
data class CanvasSpec(
    val width: Int = 1280,
    val height: Int = 720,
    val fps: Int = 30,
    val fit: CanvasFit = CanvasFit.FIT,
    val backgroundRgb: String = "000000"
) {
    init {
        require(width in 4..4320 && height in 4..4320 && width % 2 == 0 && height % 2 == 0) { "Canvas dimensions must be even, from 4 to 4320." }
        require(width.toLong() * height <= 3840L * 2160) { "Canvas exceeds the supported 4K pixel budget." }
        require(fps in 1..120) { "Canvas frame rate must be 1–120." }
        require(backgroundRgb.matches(Regex("[0-9a-fA-F]{6}"))) { "Background must be six hexadecimal RGB digits." }
    }
}

data class SequenceSpec(val timeline: EditTimeline, val canvas: CanvasSpec = CanvasSpec(), val transitionMs: Long = 0) {
    init { require(transitionMs in 0..10_000) { "Transition must be 0–10000 milliseconds." } }
}

/** One filter graph, one final encode: not a queue of separately encoded movies. */
object SequencePlanner {
    const val MAX_RENDER_CLIPS = WorkPolicy.MAX_COMPOSITION_INPUTS
    private fun seconds(frames: Long, fps: Int) = String.format(Locale.ROOT, "%.6f", frames.toDouble() / fps)
    fun frames(sequence: SequenceSpec): List<Long> = sequence.timeline.clips.map {
        val ms = Planner.outputDuration(it.source,it.trim,if(it.source.videoTracks==0)it.settings.copy(container=Container.M4A)else it.settings)
        require(ms <= 86_400_000) { "A rendered clip cannot exceed 24 hours." }
        (ms * sequence.canvas.fps + 999) / 1000
    }
    fun overlapFrames(sequence: SequenceSpec): Long = (sequence.transitionMs * sequence.canvas.fps + 500) / 1000
    fun duration(sequence: SequenceSpec): Long {
        if (sequence.timeline.clips.isEmpty()) return 0
        val total = frames(sequence).sum() - overlapFrames(sequence) * (sequence.timeline.clips.size - 1)
        require(total > 0) { "Transitions consume the whole movie." }
        return total * 1000 / sequence.canvas.fps
    }
    fun clipSettings(clip: TimelineClip, output: Settings, canvas: CanvasSpec): Settings = output.copy(
        effects = clip.settings.effects, audioTrack = clip.settings.audioTrack, audioEdit=clip.settings.audioEdit,
        audio = if (clip.settings.audio == AudioEncoder.NONE) AudioEncoder.NONE else output.audio,
        denoise = clip.settings.denoise, deinterlace = clip.settings.deinterlace,
        maxHeight = canvas.height, fps = canvas.fps, stereo = true
    )
    fun outputDuration(sequence:SequenceSpec,output:Settings):Long =
        if(output.container.audioOnly && hasAudio(sequence,output))
            (AudioGraphPlanner.plan(mixedSource(sequence),Trim(),output.copy(audioTrack=0,effects=ClipEffects())).outputDurationUs+999)/1000
        else duration(sequence)
    fun mixedSource(sequence:SequenceSpec):Source {
        val count=frames(sequence).sum()-overlapFrames(sequence)*(sequence.timeline.clips.size-1)
        val us=AudioGraphPlanner.roundRatio(count,1000000,sequence.canvas.fps.toLong())
        return Source("movie-mix","movie-mix",us/1000,audioTracks=1,videoTracks=0,
            audioStreams=listOf(SourceAudioFacts(0,48000,2,"stereo","fltp",us,totalSamples=AudioGraphPlanner.roundRatio(count,48000,sequence.canvas.fps.toLong()))))
    }
    fun hasAudio(sequence: SequenceSpec, output: Settings): Boolean = output.audio != AudioEncoder.NONE &&
        sequence.timeline.clips.any { it.source.audioTracks > 0 && it.settings.audio != AudioEncoder.NONE }

    fun validate(sequence: SequenceSpec, output: Settings, caps: Capabilities? = null): List<String> = buildList {
        val clips = sequence.timeline.clips
        if (clips.isEmpty() || clips.size > MAX_RENDER_CLIPS) add("Movie export needs 1–$MAX_RENDER_CLIPS clips; longer timelines need a separately qualified batching renderer.")
        if (!output.effects.isNeutral) add("Movie-wide clip effects are not supported; apply effects to individual clips.")
        if (!output.container.audioOnly && output.video.hardware) add("Movie composition currently requires a software video encoder.")
        if(hasAudio(sequence,output)) {
            val mixed=runCatching {mixedSource(sequence)}.getOrNull()
            if(mixed!=null) {
                val graphSettings=output.copy(audioTrack=0,effects=ClipEffects())
                val facts=AudioGraphPlanner.sourceFacts(mixed,Trim(),graphSettings)
                val issues=AudioEffectRegistry.validate(output.audioEdit,facts,caps)
                addAll(issues.map {it.message})
                if(issues.isEmpty() && caps?.available==true)
                    AudioGraphPlanner.plan(mixed,Trim(),graphSettings).requiredFilters.filterNot {it in caps.filters}.forEach {add("Required movie audio filter $it is unavailable.")}
            }
            if(!output.container.audioOnly && output.audioEdit.rate.value!=1.0)add("Movie audio speed changes are unavailable while linked to video.")
        }
        if (!output.stereo) add("Movie audio currently uses a common stereo mix. Select stereo explicitly.")
        if (clips.groupBy { it.source.uri }.any { (_, group) -> group.map { it.source }.distinct().size > 1 })
            add("The same source URI has conflicting inspection facts. Reselect that source.")
        if(clips.any {it.settings.audioEdit.output.maxBytes!=null})
            add("Per-clip audio byte limits describe standalone files, not a compound movie. Set the movie byte limit or explicitly clear each clip limit before exporting.")
        val normalizedClips=clips.any {it.settings.audioEdit.output.normalization.mode!=NormalizationMode.OFF}
        if(normalizedClips && (sequence.transitionMs>0 || output.audioEdit!=AudioEdit()))
            add("Per-clip measured normalization cannot be verified after a transition or movie-wide audio processing. Use final movie normalization or remove those later audio transformations.")
        clips.forEach { clip ->
            addAll(Planner.validate(clip.source, clip.trim, clipSettings(clip, output, sequence.canvas), caps, allowSilentAudioSegment = true).map { "${clip.id}: $it" })
        }
        val counts = runCatching { frames(sequence) }.getOrElse { add(it.message ?: "Invalid movie timing."); emptyList() }
        val overlap = overlapFrames(sequence)
        if (sequence.transitionMs > 0 && overlap == 0L) add("The transition is shorter than one output frame.")
        counts.forEachIndexed { index, count ->
            val edges = (if (index > 0) 1 else 0) + (if (index < counts.lastIndex) 1 else 0)
            if (count <= overlap * edges) add("Clip ${clips[index].id} must be longer than its incoming and outgoing transitions combined.")
        }
        if (output.container.audioOnly && !hasAudio(sequence, output)) add("Audio movie output needs at least one included audio track.")
        if (caps?.available == true) {
            val needed = mutableSetOf("concat", "atrim", "asetpts", "aresample", "aformat", "apad")
            if (!output.container.audioOnly) needed += setOf("trim", "setpts", "scale", "setsar", "fps", "tpad", "format", "settb", "color", "overlay")
            if (!output.container.audioOnly && sequence.canvas.fit == CanvasFit.FIT) needed += "pad"
            if (!output.container.audioOnly && sequence.canvas.fit == CanvasFit.FILL) needed += "crop"
            if (hasAudio(sequence, output) && clips.any { it.source.audioTracks == 0 || it.settings.audio == AudioEncoder.NONE }) needed += "anullsrc"
            if (overlap > 0 && clips.size > 1) {
                if (!output.container.audioOnly) needed += "xfade"
                if (hasAudio(sequence, output)) needed += "acrossfade"
            }
            if (!hasAudio(sequence, output)) needed -= setOf("atrim", "asetpts", "aresample", "aformat", "apad")
            needed.filterNot { it in caps.filters }.sorted().forEach { add("Required movie filter $it is unavailable.") }
        }
    }

    fun arguments(sequence: SequenceSpec, output: Settings, inputs: List<String>, destination: String, audioTransport:Boolean=false): List<String> {
        require(validate(sequence, output).isEmpty()) { validate(sequence, output).joinToString("\n") }
        require(inputs.size == sequence.timeline.clips.size) { "Supply one staged source reference per clip." }
        require(inputs.all { it.isNotBlank() && '\u0000' !in it && it != destination } && destination.isNotBlank() && '\u0000' !in destination) { "Separate, nonempty source and output paths are required." }
        val c = sequence.canvas
        val video = !audioTransport && !output.container.audioOnly
        val audio = hasAudio(sequence, output)
        val counts = frames(sequence)
        val graph = mutableListOf<String>()
        sequence.timeline.clips.forEachIndexed { index, clip ->
            val s = clipSettings(clip, output, c)
            val length = seconds(counts[index], c.fps)
            if (video) {
                val filters = buildList {
                    if (s.deinterlace) add("yadif")
                    if (s.denoise) add("hqdn3d")
                    addAll(EditPipeline.videoFilters(clip.source, clip.trim, s, forceTrim = true))
                    // Resolve non-square source pixels before fitting the square-pixel canvas.
                    add("scale=w='trunc(iw*sar/2)*2':h=ih")
                    add("setsar=1")
                    when (c.fit) {
                        CanvasFit.FIT -> {
                            add("scale=${c.width}:${c.height}:force_original_aspect_ratio=decrease:force_divisible_by=2")
                            add("pad=${c.width}:${c.height}:(ow-iw)/2:(oh-ih)/2:color=0x${c.backgroundRgb}")
                        }
                        CanvasFit.FILL -> {
                            add("scale=${c.width}:${c.height}:force_original_aspect_ratio=increase:force_divisible_by=2")
                            add("crop=${c.width}:${c.height}")
                        }
                        CanvasFit.STRETCH -> add("scale=${c.width}:${c.height}")
                    }
                    add("setsar=1")
                    // Keep the common trim origin, including a video-leading gap.
                    add("fps=${c.fps}")
                    add("tpad=stop_mode=clone:stop_duration=$length")
                    add("trim=duration=$length")
                    add("settb=AVTB")
                    add("format=yuv420p")
                }
                graph += "[$index:v:0]${filters.joinToString(",")}[picture$index]"
                graph += "color=c=0x${c.backgroundRgb}:s=${c.width}x${c.height}:r=${c.fps}:d=$length[background$index]"
                // Overlay follows timestamps. Before the first picture, only the canvas is visible.
                graph += "[background$index][picture$index]overlay=eof_action=repeat:shortest=0:format=yuv420," +
                    "trim=duration=$length,setpts=N/(${c.fps}*TB),fps=${c.fps}:start_time=0,settb=AVTB,format=yuv420p[v$index]"
            }
            if (audio) {
                val included = clip.source.audioTracks > 0 && s.audio != AudioEncoder.NONE
                val prefix = if (included) "[$index:a:${s.audioTrack}]" else ""
                val filters = buildList {
                    if (included) {
                        val edit=s.audioEdit
                        if(edit.nodes.any {it.enabled} || edit.output!=AudioOutputPolicy() || edit.rate.value!=1.0 || s.container in setOf(Container.WAV,Container.FLAC))
                            addAll(AudioGraphPlanner.plan(clip.source,clip.trim,s,forceProcessed=true,preserveTimeline=true).filters)
                        else addAll(EditPipeline.audioFilters(clip.source,clip.trim,s,forceTrim=true))
                        // Materialize intentional leading silence before resetting sample timestamps.
                        add("aresample=48000:async=1:first_pts=0")
                        add("aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo")
                        add("apad=whole_dur=$length")
                    } else add("anullsrc=r=48000:cl=stereo")
                    add("atrim=duration=$length")
                    add("asetpts=N/SR/TB")
                }
                graph += "$prefix${filters.joinToString(",")}[a$index]"
            }
        }
        var videoMap = "vout"
        var audioMap = "aout"
        val overlap = overlapFrames(sequence)
        if (overlap == 0L || counts.size == 1) {
            val streams = counts.indices.joinToString("") { (if (video) "[v$it]" else "") + (if (audio) "[a$it]" else "") }
            graph += streams + "concat=n=${counts.size}:v=${if (video) 1 else 0}:a=${if (audio) 1 else 0}" + (if (video) "[vout]" else "") + (if (audio) "[aout]" else "")
        } else {
            var elapsed = counts[0]
            videoMap = "v0"; audioMap = "a0"
            for (index in 1 until counts.size) {
                val d = seconds(overlap, c.fps)
                if (video) {
                    graph += "[$videoMap][v$index]xfade=transition=fade:duration=$d:offset=${seconds(elapsed - overlap, c.fps)}[vx$index]"
                    videoMap = "vx$index"
                }
                if (audio) {
                    graph += "[$audioMap][a$index]acrossfade=d=$d:c1=tri:c2=tri[ax$index]"
                    audioMap = "ax$index"
                }
                elapsed += counts[index] - overlap
            }
        }
        // The common mix is a new zero-origin program. Apply movie-wide AudioEdit once after joins.
        if(audio) {
            val mixed=mixedSource(sequence)
            val finalGraph=AudioGraphPlanner.plan(mixed,Trim(),output.copy(audioTrack=0,effects=ClipEffects()))
            if(finalGraph.processed) {
                graph+="[$audioMap]${finalGraph.filters.joinToString(",")}[audioFinal]"
                audioMap="audioFinal"
            }
        }
        val common = output.copy(maxHeight = c.height, fps = c.fps, effects = ClipEffects())
        val encoded=if(audioTransport)common.copy(container=Container.WAV,audio=AudioEncoder.PCM_F32LE)else common
        return buildList {
            addAll(listOf("-hide_banner", "-loglevel", "warning", "-nostdin", "-n"))
            inputs.forEach { addAll(listOf("-i", it)) }
            addAll(listOf("-filter_complex", graph.joinToString(";"), "-t", if(output.container.audioOnly || audioTransport)String.format(Locale.ROOT,"%.6f",outputDuration(sequence,output)/1000.0)else seconds(counts.sum()-overlap*(counts.size-1),c.fps)))
            addAll(Planner.outputArguments(encoded, if (video) "[$videoMap]" else null, if (audio) "[$audioMap]" else null))
            add(destination)
        }
    }
}
