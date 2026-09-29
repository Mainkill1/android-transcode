package dev.forma.core

import dev.forma.core.settings.MediaPreferences
import dev.forma.core.audio.AudioEdit
import dev.forma.core.audio.SourceAudioFacts

enum class Container(val extension: String, val muxer: String, val mime: String, val audioOnly: Boolean = false) {
    MP4("mp4", "mp4", "video/mp4"), MKV("mkv", "matroska", "video/x-matroska"),
    WEBM("webm", "webm", "video/webm"), M4A("m4a", "ipod", "audio/mp4", true),
    WAV("wav", "wav", "audio/wav", true), FLAC("flac", "flac", "audio/flac", true)
}
enum class VideoEncoder(val ffmpeg: String, val label: String, val hardware: Boolean = false, val automatic: Boolean = false) {
    X264("libx264", "H.264 · software"), X265("libx265", "H.265 · software"),
    VP9("libvpx-vp9", "VP9 · software"), AV1("libsvtav1", "AV1 · software"),
    H264_HW("h264_mediacodec", "H.264 · device", true),
    H265_HW("hevc_mediacodec", "H.265 · device", true),
    VP9_HW("vp9_mediacodec", "VP9 · device", true),
    AV1_HW("av1_mediacodec", "AV1 · device", true),
    H264_AUTO("libx264", "H.264 · automatic", automatic = true),
    H265_AUTO("libx265", "H.265 · automatic", automatic = true);

    val format: VideoFormat get() = VideoFormat.values().first { ffmpeg == it.software || ffmpeg == it.device }
    val deviceRequested: Boolean get() = hardware || automatic
    val accelerationMode: AccelerationMode get() = when {
        automatic -> AccelerationMode.AUTO
        hardware -> AccelerationMode.HARDWARE_REQUIRED
        else -> AccelerationMode.SOFTWARE_ONLY
    }
    fun isCompiled(encoders: Set<String>): Boolean = if (automatic)
        format.software in encoders || format.device in encoders else ffmpeg in encoders
    fun softwareVariant(): VideoEncoder = values().first { !it.deviceRequested && it.ffmpeg == format.software }
    fun deviceVariant(): VideoEncoder = values().first { it.hardware && it.ffmpeg == format.device }
}
enum class AudioEncoder(val ffmpeg: String, val usesBitrate: Boolean = true) {
    AAC("aac"), OPUS("libopus"), FLAC("flac", false),
    PCM_S16LE("pcm_s16le", false), PCM_F32LE("pcm_f32le", false), NONE("", false)
}
enum class RateControl { QUALITY, BITRATE }
enum class Goal(val label: String, val description: String) {
    SMALLER("Make it smaller", "Save space without the guesswork"),
    SHARE("Easy to share", "A widely compatible video file"),
    DETAIL("Keep the detail", "Prioritize picture quality"),
    AUDIO("Just the audio", "Save the sound without the video")
}
enum class Quality(val label: String) { SMALL("Smaller"), BALANCED("Balanced"), CLEAR("Clearer") }

data class Settings(
    val container: Container = Container.MP4,
    val video: VideoEncoder = VideoEncoder.X264,
    val rateControl: RateControl = RateControl.QUALITY,
    val crf: Int = 23,
    val videoKbps: Int = 4000,
    val maxHeight: Int = 1080,
    val fps: Int = 0,
    val audio: AudioEncoder = AudioEncoder.AAC,
    val audioKbps: Int = 160,
    val audioTrack: Int = 0,
    val stereo: Boolean = true,
    val denoise: Boolean = false,
    val deinterlace: Boolean = false,
    val keepMetadata: Boolean = false,
    val audioEdit: AudioEdit = AudioEdit()
)

/** URI identity is never converted into an arbitrary filesystem path. */
data class Source(
    val uri: String,
    val name: String,
    val durationMs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val videoTracks: Int = 0,
    val audioTracks: Int = 0,
    val hdr: Boolean = false,
    val bytes: Long = -1,
    val audioStreams: List<SourceAudioFacts> = emptyList(),
    val imageInfo: dev.forma.core.image.ImageInfo? = null, val imageOriginalUri:String?=null
)
data class Trim(val startMs: Long = 0, val endMs: Long? = null)
data class SourceEdit(val source: Source, val trim: Trim = Trim())
data class Editor(
    val settings: Settings = Settings(),
    val advanced: Boolean = false,
    val custom: Boolean = false,
    val goal: Goal = Goal.SHARE,
    val quality: Quality = Quality.BALANCED,
    val preferences: MediaPreferences = MediaPreferences.legacy(settings)
)
data class JobSpec(val id: String, val source: Source, val trim: Trim, val settings: Settings,
    val preferences: MediaPreferences = MediaPreferences.legacy(settings), val targetBytes: Long? = null) {
    /** Compatibility for runtime-codec snapshots constructed before preference provenance. */
    constructor(id: String, source: Source, trim: Trim, settings: Settings, legacyTargetBytes: Long?) :
        this(id,source,trim,settings,MediaPreferences.legacy(settings),legacyTargetBytes)
    init { targetBytes?.let(UploadFit::validateTarget) }
}
enum class JobState { QUEUED, PREPARING, RUNNING, VERIFYING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }
data class QueueEntry(val spec: dev.forma.core.image.QueueJobSpec, val state: JobState = JobState.QUEUED,
    val message: String = "", val completedAtMs: Long? = null) {
    constructor(spec: JobSpec, state: JobState = JobState.QUEUED, message: String = "", completedAtMs: Long? = null) :
        this(dev.forma.core.image.QueueJobSpec.Av(spec), state, message, completedAtMs)
}
data class Capabilities(
    val available: Boolean = false,
    val reason: String = "Native FFmpeg is not included in this build.",
    val encoders: Set<String> = emptySet(),
    val muxers: Set<String> = emptySet(),
    val filters: Set<String> = emptySet(),
    val build: String = "Not loaded",
    val decoders: Set<String> = emptySet(),
    val demuxers: Set<String> = emptySet(),
    val pixelFormats: Set<String> = emptySet(),
    val configuration: String = ""
)
data class Progress(val processedMs: Long, val speed: Double? = null) {
    fun fraction(durationMs: Long): Float? = if (durationMs <= 0) null else
        (processedMs.toDouble() / durationMs).coerceIn(0.0, 0.99).toFloat()
}
