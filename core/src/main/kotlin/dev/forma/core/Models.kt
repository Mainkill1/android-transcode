package dev.forma.core

enum class Container(val extension: String, val muxer: String, val mime: String) {
    MP4("mp4", "mp4", "video/mp4"), MKV("mkv", "matroska", "video/x-matroska"),
    WEBM("webm", "webm", "video/webm"), M4A("m4a", "ipod", "audio/mp4")
}
enum class VideoEncoder(val ffmpeg: String, val label: String, val hardware: Boolean = false) {
    X264("libx264", "H.264 · software"), X265("libx265", "H.265 · software"),
    VP9("libvpx-vp9", "VP9 · software"), AV1("libsvtav1", "AV1 · software"),
    H264_HW("h264_mediacodec", "H.264 · device", true),
    H265_HW("hevc_mediacodec", "H.265 · device", true)
}
enum class AudioEncoder(val ffmpeg: String) { AAC("aac"), OPUS("libopus"), FLAC("flac"), NONE("") }
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
    val effects: ClipEffects = ClipEffects()
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
    val bytes: Long = -1
)
data class Trim(val startMs: Long = 0, val endMs: Long? = null)
data class SourceEdit(val source: Source, val trim: Trim = Trim(), val effects: ClipEffects = ClipEffects()) {
    fun snapshot(settings: Settings): Settings = settings.copy(effects = effects)
}
data class Editor(
    val settings: Settings = Settings(),
    val advanced: Boolean = false,
    val custom: Boolean = false,
    val goal: Goal = Goal.SHARE,
    val quality: Quality = Quality.BALANCED,
    val targetBytes: Long? = 10_000_000
)
data class JobSpec(val id: String, val source: Source, val trim: Trim, val settings: Settings,
    val sequence: SequenceSpec? = null, val targetBytes: Long? = null)
enum class JobState { QUEUED, PREPARING, RUNNING, VERIFYING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }
data class QueueEntry(val spec: JobSpec, val state: JobState = JobState.QUEUED, val message: String = "")
data class Capabilities(
    val available: Boolean = false,
    val reason: String = "Native FFmpeg is not included in this build.",
    val encoders: Set<String> = emptySet(),
    val muxers: Set<String> = emptySet(),
    val filters: Set<String> = emptySet(),
    val build: String = "Not loaded"
)
data class Progress(val processedMs: Long, val speed: Double? = null, val attempt: Int = 1, val attempts: Int = 1) {
    fun fraction(durationMs: Long): Float? = if (durationMs <= 0) null else
        (processedMs.toDouble() / durationMs).coerceIn(0.0, 0.99).toFloat()
}
