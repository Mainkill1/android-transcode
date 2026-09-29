package dev.forma.core.settings

import dev.forma.core.AudioEncoder
import dev.forma.core.Container
import dev.forma.core.RateControl
import dev.forma.core.Settings
import dev.forma.core.VideoEncoder
import dev.forma.core.audio.ChannelMode

/** Bridges only implemented settings to the existing immutable job model. No queue migration or encoder execution. */
object NativePreferences {
    val boundIds: Set<String> = setOf("video.codec", "video.rate_control", "video.quality", "video.bitrate_kbps",
        "video.max_height", "video.frame_rate", "video.deinterlace", "audio.codec", "audio.bitrate_kbps",
        "audio.channels", "engine.encode_backend", "engine.decode_backend", "engine.filter_backend", "export.container")
    private fun c(value: String) = SettingValue.Choice(value)
    private fun i(value: Int) = SettingValue.Integer(value.toLong())

    /** The legacy editor has no provenance: capture its values explicitly rather than guessing inheritance. */
    fun capture(s: Settings): PreferenceValues = PreferenceValues.of(snapshotEntries(s))
    fun captureLegacy(s: Settings): PreferenceValues = PreferenceValues.legacyMedia(snapshotEntries(s))
    private fun snapshotEntries(s: Settings): Map<String,SettingValue> = mapOf(
        "video.codec" to c(when (s.video) {
            VideoEncoder.X264, VideoEncoder.H264_HW, VideoEncoder.H264_AUTO -> "h264"
            VideoEncoder.X265, VideoEncoder.H265_HW, VideoEncoder.H265_AUTO -> "hevc"
            VideoEncoder.VP9, VideoEncoder.VP9_HW -> "vp9"; VideoEncoder.AV1, VideoEncoder.AV1_HW -> "av1"
        }),
        "video.rate_control" to c(if (s.rateControl == RateControl.QUALITY) "quality" else "bitrate"),
        "video.quality" to i(s.crf), "video.bitrate_kbps" to i(s.videoKbps), "video.max_height" to i(s.maxHeight),
        "video.frame_rate" to c(if (s.fps == 0) "source" else s.fps.toString()),
        "video.deinterlace" to c(if (s.deinterlace) "always" else "off"),
        "audio.codec" to c(s.audio.name.lowercase(java.util.Locale.ROOT)), "audio.bitrate_kbps" to i(s.audioKbps),
        "audio.channels" to c(channel(s)),
        "engine.encode_backend" to c(when { s.video.automatic -> "auto"; s.video.hardware -> "hardware"; else -> "software" }),
        "engine.decode_backend" to c("software"), "engine.filter_backend" to c("cpu"),
        "export.container" to c(s.container.name.lowercase(java.util.Locale.ROOT))
    )
    private fun channel(s: Settings) = s.audioEdit.output.channels?.name?.lowercase(java.util.Locale.ROOT)
        ?: if (s.stereo) "stereo" else "source"
    fun cpuOnly(values: PreferenceValues): PreferenceValues = values
        .with("engine.encode_backend", c("software")).with("engine.decode_backend", c("software")).with("engine.filter_backend", c("cpu"))

    fun apply(base: Settings, resolved: Map<String, ResolvedSetting>): Settings {
        fun choice(id: String) = (resolved.getValue(id).value as SettingValue.Choice).value
        fun integer(id: String) = (resolved.getValue(id).value as SettingValue.Integer).value.toInt()
        val requested = PreferenceValues.of(resolved.filter { (id, value) ->
            SettingCatalog[id].scope == SettingScope.JOB && (id in boundIds || value.origin != ValueOrigin.FACTORY)
        }.mapValues { it.value.value })
        val errors = SettingsRules.editErrors(requested)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val codec = choice("video.codec")
        val channel = choice("audio.channels")
        val changedChannel = channel != channel(base)
        val backend = choice("engine.encode_backend")
        val hardware = backend == "hardware"
        // Automatic stays explicit; the runtime executor owns actual device trials and same-codec fallback.
        val encoder = when (codec) {
            "h264" -> when(backend) { "auto" -> VideoEncoder.H264_AUTO; "hardware" -> VideoEncoder.H264_HW; else -> VideoEncoder.X264 }
            "hevc" -> when(backend) { "auto" -> VideoEncoder.H265_AUTO; "hardware" -> VideoEncoder.H265_HW; else -> VideoEncoder.X265 }
            "vp9" -> { require(backend != "auto") { "VP9 Automatic is unavailable. Choose Software or Hardware required." }; if(hardware) VideoEncoder.VP9_HW else VideoEncoder.VP9 }
            "av1" -> { require(backend != "auto") { "AV1 Automatic is unavailable. Choose Software or Hardware required." }; if(hardware) VideoEncoder.AV1_HW else VideoEncoder.AV1 }
            else -> throw IllegalArgumentException("Unsupported video codec")
        }
        val result = base.copy(
            container = Container.valueOf(choice("export.container").uppercase(java.util.Locale.ROOT)),
            video = encoder, rateControl = if (choice("video.rate_control") == "quality") RateControl.QUALITY else RateControl.BITRATE,
            crf = integer("video.quality"), videoKbps = integer("video.bitrate_kbps"), maxHeight = integer("video.max_height"),
            fps = choice("video.frame_rate").let { if (it == "source") 0 else it.toInt() },
            deinterlace = choice("video.deinterlace") == "always",
            audio = AudioEncoder.valueOf(choice("audio.codec").uppercase(java.util.Locale.ROOT)),
            audioKbps = integer("audio.bitrate_kbps"),
            audioEdit = if (changedChannel) base.audioEdit.copy(output = base.audioEdit.output.copy(
                channels = ChannelMode.valueOf(channel.uppercase(java.util.Locale.ROOT)))) else base.audioEdit
        )
        require(result.crf <= if (codec in setOf("vp9", "av1")) 63 else 51) { "This codec's maximum constant-quality value is 51." }
        if (hardware && !result.container.audioOnly) {
            require(result.rateControl == RateControl.BITRATE) { "Hardware required needs Average bitrate; no automatic CRF conversion." }
            require(result.fps > 0) { "Hardware required needs an explicit frame rate in this native path." }
        }
        when (result.container) {
            Container.MP4 -> require(result.audio in setOf(AudioEncoder.AAC, AudioEncoder.NONE)) { "MP4 requires AAC or no audio." }
            Container.M4A -> require(result.audio == AudioEncoder.AAC) { "M4A is audio-only and requires AAC." }
            Container.WEBM -> require(result.video.format in setOf(dev.forma.core.VideoFormat.VP9, dev.forma.core.VideoFormat.AV1) && result.audio in setOf(AudioEncoder.OPUS, AudioEncoder.NONE)) { "WebM requires VP9/AV1 and Opus or no audio." }
            Container.MKV -> Unit
            Container.WAV -> require(result.audio in setOf(AudioEncoder.PCM_S16LE,AudioEncoder.PCM_F32LE)) { "WAV requires PCM audio." }
            Container.FLAC -> require(result.audio == AudioEncoder.FLAC) { "FLAC requires FLAC audio." }
        }
        return result
    }
}
