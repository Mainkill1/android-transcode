package dev.forma.core

import java.util.Locale
import dev.forma.core.audio.AudioEffectRegistry
import dev.forma.core.audio.SourceAudioFacts
import dev.forma.core.audio.AudioGraphPlanner

/** The only place where UI intent becomes FFmpeg arguments. No shell is involved. */
object Planner {
    fun duration(source: Source, trim: Trim): Long = (trim.endMs ?: source.durationMs) - trim.startMs

    /** Budget the actual audio sample graph, rather than the unedited input window. */
    fun outputDuration(source: Source, trim: Trim, settings: Settings): Long =
        if(settings.container.audioOnly && source.audioTracks>0 && settings.audio!=AudioEncoder.NONE)
            (AudioGraphPlanner.plan(source,trim,settings).outputDurationUs+999)/1000
        else duration(source,trim)

    fun preset(goal: Goal, quality: Quality): Settings {
        val crf = when (quality) { Quality.SMALL -> 28; Quality.BALANCED -> 23; Quality.CLEAR -> 18 }
        return when (goal) {
            Goal.SMALLER -> Settings(crf = crf + 2, maxHeight = 720, audioKbps = 128)
            Goal.SHARE -> Settings(crf = crf)
            Goal.DETAIL -> Settings(crf = (crf - 3).coerceAtLeast(15), maxHeight = 0, audioKbps = 192)
            Goal.AUDIO -> Settings(container = Container.M4A, audioKbps = when (quality) {
                Quality.SMALL -> 96; Quality.BALANCED -> 160; Quality.CLEAR -> 256
            })
        }
    }

    fun validate(source: Source, trim: Trim, settings: Settings, caps: Capabilities? = null): List<String> = buildList {
        val video = !settings.container.audioOnly
        if (source.durationMs <= 0) add("The source has no readable duration.")
        if (trim.startMs < 0 || trim.startMs >= source.durationMs ||
            (trim.endMs != null && (trim.endMs <= trim.startMs || trim.endMs > source.durationMs)))
            add("Choose a start and end inside the source duration.")
        if (video && source.videoTracks == 0) add("Choose Just the audio for an audio-only source.")
        if (video && source.hdr) add("HDR/high-bit-depth or an unqualified pixel format needs a tested color pipeline; not enabled in this foundation.")
        if (!video && (source.audioTracks == 0 || settings.audio == AudioEncoder.NONE)) add("Audio output needs an audio track.")
        if (settings.audio != AudioEncoder.NONE && source.audioTracks > 0 && settings.audioTrack !in 0 until source.audioTracks)
            add("The selected audio track does not exist in this source.")
        if (settings.maxHeight < 0 || settings.maxHeight > 4320 || settings.maxHeight % 2 != 0 || settings.maxHeight == 2)
            add("Height must be 0 (source) or an even value from 4 to 4320.")
        if (settings.fps !in 0..120) add("Frame rate must be 0 (source) or 1–120.")
        if (settings.videoKbps !in 100..200_000) add("Video bitrate must be 100–200000 kb/s.")
        if (settings.audioKbps !in 32..512) add("Audio bitrate must be 32–512 kb/s.")
        val maxCrf = if (settings.video in setOf(VideoEncoder.VP9, VideoEncoder.AV1)) 63 else 51
        if (settings.crf !in 0..maxCrf) add("The quality value is outside this encoder's range.")
        if (video && settings.video.hardware && settings.rateControl == RateControl.QUALITY)
            add("Device encoders require bitrate mode; CRF is not a device quality scale.")
        if (settings.container == Container.WEBM && (settings.video.softwareVariant() !in setOf(VideoEncoder.VP9, VideoEncoder.AV1) ||
                    settings.audio !in setOf(AudioEncoder.OPUS, AudioEncoder.NONE)))
            add("WebM needs VP9/AV1 video and Opus audio (or no audio).")
        if (settings.container == Container.MP4 && settings.audio !in setOf(AudioEncoder.AAC, AudioEncoder.NONE))
            add("This MP4 profile supports AAC audio or no audio.")
        if (settings.container == Container.M4A && settings.audio != AudioEncoder.AAC) add("This M4A profile needs AAC.")
        if (settings.container == Container.WAV && settings.audio !in setOf(AudioEncoder.PCM_S16LE, AudioEncoder.PCM_F32LE))
            add("WAV output needs PCM 16-bit or float audio.")
        if (settings.container == Container.FLAC && settings.audio != AudioEncoder.FLAC) add("FLAC output needs the FLAC encoder.")
        if (video && settings.audioEdit.rate.value != 1.0) add("Audio speed changes are unavailable while linked to video.")
        val audioFacts = AudioGraphPlanner.sourceFacts(source, trim, settings)
        addAll(AudioEffectRegistry.validate(settings.audioEdit, audioFacts, caps).map { it.message })
        if (video && source.audioStreams.getOrNull(settings.audioTrack)?.timelineOffsetUs?.let { it != 0L } == true &&
            (settings.audioEdit.nodes.any { it.enabled } || settings.audioEdit.output != dev.forma.core.audio.AudioOutputPolicy()))
            add("Audio with a timeline offset is not yet qualified for linked processing. Export just the audio or bypass its edits.")
        val audioRate = settings.audioEdit.output.sampleRateHz ?: source.audioStreams.getOrNull(settings.audioTrack)?.sampleRateHz
        if (settings.audio == AudioEncoder.AAC && audioRate != null && audioRate !in setOf(7350,8000,11025,12000,16000,22050,24000,32000,44100,48000,64000,88200,96000))
            add("AAC does not support that sample rate. Choose 44.1 or 48 kHz.")
        if (settings.audio == AudioEncoder.OPUS && settings.audioEdit.output.sampleRateHz?.let { it != 48000 } == true)
            add("This Opus profile requires a 48 kHz output rate.")
        if (caps != null) {
            if (!caps.available) add(caps.reason)
            else {
                if (video) {
                    if (!settings.video.isCompiled(caps.encoders))
                        add("Encoder ${settings.video.ffmpeg} is not enabled in this build/profile.")
                    else if (settings.video.automatic &&
                        (settings.rateControl == RateControl.QUALITY || settings.fps == 0) &&
                        settings.video.format.software !in caps.encoders)
                        add("Automatic constant-quality or source-rate output requires software encoder ${settings.video.format.software} in this build.")
                }
                if (settings.audio != AudioEncoder.NONE && source.audioTracks > 0 && settings.audio.ffmpeg !in caps.encoders)
                    add("Encoder ${settings.audio.ffmpeg} is not included in this FFmpeg build.")
                if (settings.container.muxer !in caps.muxers) add("Output format ${settings.container.muxer} is unavailable.")
                if (source.audioTracks > 0 && settings.audio != AudioEncoder.NONE &&
                    AudioEffectRegistry.validate(settings.audioEdit, audioFacts).isEmpty()) {
                    for (filter in AudioGraphPlanner.plan(source, trim, settings).requiredFilters - caps.filters)
                        add("The audio $filter filter is unavailable.")
                }
                if (video) {
                    if ("scale" !in caps.filters) add("The scale filter is unavailable.")
                    if (settings.denoise && "hqdn3d" !in caps.filters) add("The denoise filter is unavailable.")
                    if (settings.deinterlace && "yadif" !in caps.filters) add("The deinterlace filter is unavailable.")
                }
            }
        }
    }

    fun audioArguments(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> =
        arguments(source,trim,settings,input,output,audioTransport=true)

    fun arguments(source: Source, trim: Trim, settings: Settings, input: String, output: String, audioTransport: Boolean = false): List<String> {
        require(input.isNotBlank() && output.isNotBlank() && input != output) { "Separate input and output paths are required." }
        require('\u0000' !in input && '\u0000' !in output) { "Paths cannot contain a NUL character." }
        val problems = validate(source, trim, settings)
        require(problems.isEmpty()) { problems.joinToString("\n") }
        fun seconds(ms: Long) = String.format(Locale.ROOT, "%.3f", ms / 1000.0)
        val audio = if (source.audioTracks > 0 && settings.audio != AudioEncoder.NONE) AudioGraphPlanner.plan(source, trim, settings, forceProcessed=audioTransport) else null
        val filtered = audio?.processed == true
        val durationUs = if (settings.container.audioOnly) audio?.outputDurationUs else null
        val durationText = durationUs?.let { if (it % 1000 == 0L) seconds(it / 1000) else String.format(Locale.ROOT, "%.6f", it / 1000000.0) }
            ?: seconds(duration(source, trim))
        return buildList {
            addAll(listOf("-hide_banner", "-loglevel", "warning", "-nostdin", "-n", "-i", input))
            if (trim.startMs > 0 && !filtered) addAll(listOf("-ss", seconds(trim.startMs)))
            if (!audioTransport && (!filtered || !settings.container.audioOnly)) addAll(listOf("-t", durationText))
            if (audioTransport || settings.container.audioOnly) add("-vn") else {
                addAll(listOf("-map", "0:v:0", "-c:v", settings.video.ffmpeg))
                if (settings.rateControl == RateControl.QUALITY) {
                    addAll(listOf("-crf", settings.crf.toString()))
                    if (settings.video == VideoEncoder.VP9) addAll(listOf("-b:v", "0"))
                } else addAll(listOf("-b:v", "${settings.videoKbps}k"))
                when (settings.video) {
                    VideoEncoder.X264, VideoEncoder.X265 -> addAll(listOf("-preset", "medium"))
                    VideoEncoder.VP9 -> addAll(listOf("-deadline", "good", "-cpu-used", "4"))
                    VideoEncoder.AV1 -> addAll(listOf("-preset", "8"))
                    else -> Unit
                }
                val filters = buildList {
                    if (filtered) {
                        add("trim=start=${AudioGraphPlanner.number(trim.startMs / 1000.0)}:end=${AudioGraphPlanner.number((trim.endMs ?: source.durationMs) / 1000.0)}")
                        add("setpts=PTS-STARTPTS")
                    }
                    if (settings.deinterlace) add("yadif")
                    if (settings.denoise) add("hqdn3d")
                    val h = if (settings.maxHeight == 0) "ih" else "min(ih,${settings.maxHeight})"
                    add("scale=-2:'trunc($h/2)*2'")
                }
                addAll(listOf("-vf", filters.joinToString(","), "-pix_fmt", "yuv420p"))
                if (settings.fps > 0) addAll(listOf("-r", settings.fps.toString(), "-fps_mode", "cfr"))
                else addAll(listOf("-fps_mode", "passthrough"))
            }
            if (source.audioTracks == 0 || settings.audio == AudioEncoder.NONE) add("-an") else {
                addAll(listOf("-map", "0:a:${settings.audioTrack}", "-c:a", if(audioTransport) AudioEncoder.PCM_F32LE.ffmpeg else settings.audio.ffmpeg))
                if (!audioTransport && settings.audio.usesBitrate) addAll(listOf("-b:a", "${settings.audioKbps}k"))
                if (filtered) addAll(listOf("-af", audio!!.filters.joinToString(",")))
                if (settings.audioEdit.output.channels == null && settings.stereo) addAll(listOf("-ac", "2"))
                settings.audioEdit.output.sampleRateHz?.let { addAll(listOf("-ar", it.toString())) }
            }
            addAll(listOf("-sn", "-dn", "-map_chapters", "-1", "-map_metadata", if (settings.keepMetadata) "0" else "-1"))
            if (!audioTransport && settings.container in setOf(Container.MP4, Container.M4A)) addAll(listOf("-movflags", "+faststart"))
            addAll(listOf("-f", if(audioTransport) Container.WAV.muxer else settings.container.muxer, output))
        }
    }
}

object QueueRules {
    private val active = setOf(JobState.PREPARING, JobState.RUNNING, JobState.VERIFYING)
    fun transition(entry: QueueEntry, state: JobState, message: String = ""): QueueEntry {
        val allowed = when (entry.state) {
            JobState.QUEUED -> setOf(JobState.PREPARING, JobState.CANCELLED)
            JobState.PREPARING -> setOf(JobState.RUNNING, JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED)
            JobState.RUNNING -> setOf(JobState.VERIFYING, JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED)
            JobState.VERIFYING -> setOf(JobState.COMPLETED, JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED)
            JobState.FAILED, JobState.CANCELLED, JobState.INTERRUPTED -> setOf(JobState.QUEUED)
            JobState.COMPLETED -> emptySet()
        }
        require(state in allowed) { "Invalid queue transition: ${entry.state} -> $state" }
        return entry.copy(state = state, message = message)
    }
    fun recover(entry: QueueEntry): QueueEntry = if (entry.state in active)
        entry.copy(state = JobState.INTERRUPTED, message = "Processing was interrupted. Restart this file explicitly.") else entry
}
