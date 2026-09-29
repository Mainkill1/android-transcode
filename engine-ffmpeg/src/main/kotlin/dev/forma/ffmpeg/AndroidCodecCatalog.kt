package dev.forma.ffmpeg

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import dev.forma.core.*

/** Call off the UI thread. Advertised capability is not successful device qualification. */
class AndroidCodecCatalog {
    data class Entry(val name: String, val mime: String, val encoder: Boolean,
                     val hardware: Support, val softwareOnly: Support,
                     val priority: Int = 0, val colorFormats: List<Int> = emptyList(),
                     val bitrateModes: Set<CodecBitrateMode> = emptySet(),
                     val encoderSurfaceInput: Support = Support.UNKNOWN,
                     val maxInstances: Int? = null, val queryError: String? = null)

    fun inventory(): List<Entry> = codecs().flatMapIndexed { rank, info ->
        types(info).filter { mime -> VideoFormat.values().any { it.mime == mime } }.map { mime ->
            try {
                val caps = info.getCapabilitiesForType(mime)
                Entry(info.name, mime, info.isEncoder, hardware(info), software(info), rank,
                    caps.colorFormats.toList(), if (info.isEncoder) modes(caps.encoderCapabilities) else emptySet(),
                    if (!info.isEncoder) Support.UNKNOWN else if (
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats
                    ) Support.YES else Support.NO, caps.maxSupportedInstances)
            } catch (error: RuntimeException) {
                Entry(info.name, mime, info.isEncoder, hardware(info), software(info), rank,
                    queryError = error.javaClass.simpleName)
            }
        }
    }.sortedWith(compareBy<Entry> { it.mime }.thenBy { it.encoder }.thenBy { it.priority })

    /** Preserve the OEM's preferred component order. Do not sort names or rank by advertised max fps. */
    fun candidates(request: EncodeRequest): List<CodecCandidate> = codecs().filter {
        it.isEncoder && request.format.mime in types(it)
    }.map { info ->
        try {
            val caps = info.getCapabilitiesForType(request.format.mime)
            val vc = caps.videoCapabilities
            val ec = caps.encoderCapabilities
            val formats = BufferFormat.values().filter { color(it) in caps.colorFormats }.toSet()
            var queryFailed = false
            val safe = vc != null && ec != null && !request.hdr && request.bitDepth == 8 &&
                !request.constantQuality && request.rotationDegrees % 360 == 0 &&
                !caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) &&
                !caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) &&
                request.width % vc.widthAlignment == 0 && request.height % vc.heightAlignment == 0 &&
                vc.areSizeAndRateSupported(request.width, request.height, request.fps) &&
                vc.bitrateRange.contains(request.bitrate)
            val configuration = if (!safe) null else EncoderConfigurations.firstSupported(formats, modes(ec)) { option ->
                val format = MediaFormat.createVideoFormat(request.format.mime, request.width, request.height).apply {
                    setFloat(MediaFormat.KEY_FRAME_RATE, request.fps.toFloat())
                    setInteger(MediaFormat.KEY_BIT_RATE, request.bitrate)
                    setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode(option.bitrateMode))
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, color(option.format))
                }
                try { caps.isFormatSupported(format) }
                catch (_: RuntimeException) { queryFailed = true; false }
            }
            CodecCandidate(info.name, request.format, true, hardware(info),
                when { configuration != null -> Support.YES; queryFailed -> Support.UNKNOWN; else -> Support.NO },
                configuration?.format,
                when {
                    configuration != null -> "Exact ${configuration.format}/${configuration.bitrateMode} configuration advertised; not device-qualified."
                    queryFailed -> "Exact configuration query failed; no configuration is assumed."
                    else -> "Unsupported raw layout, non-dropping bitrate mode, geometry, color or codec feature."
                }, request, configuration?.bitrateMode ?: CodecBitrateMode.VBR)
        } catch (error: RuntimeException) {
            CodecCandidate(info.name, request.format, true, hardware(info), Support.UNKNOWN, null,
                "Capability query failed: ${error.javaClass.simpleName}", request)
        }
    }

    private fun modes(ec: MediaCodecInfo.EncoderCapabilities?): Set<CodecBitrateMode> =
        if (ec == null) emptySet() else CodecBitrateMode.values().filter {
            ec.isBitrateModeSupported(bitrateMode(it))
        }.toSet()

    private fun bitrateMode(mode: CodecBitrateMode): Int = when (mode) {
        CodecBitrateMode.VBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
        CodecBitrateMode.CBR -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
    }

    private fun color(format: BufferFormat): Int = when (format) {
        BufferFormat.YUV420P -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        BufferFormat.NV12 -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    private fun types(info: MediaCodecInfo): List<String> = try { info.supportedTypes.toList() }
        catch (_: RuntimeException) { emptyList() }

    private fun codecs(): List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            Build.VERSION.SDK_INT < 29 || !it.isAlias
        }
    } catch (_: RuntimeException) { emptyList() }

    private fun hardware(info: MediaCodecInfo): Support = try {
        if (Build.VERSION.SDK_INT >= 29)
            if (info.isHardwareAccelerated && !info.isSoftwareOnly) Support.YES else Support.NO
        else Support.UNKNOWN // Never guess hardware from a vendor component's name.
    } catch (_: RuntimeException) { Support.UNKNOWN }

    private fun software(info: MediaCodecInfo): Support = try {
        if (Build.VERSION.SDK_INT >= 29)
            if (info.isSoftwareOnly) Support.YES else Support.NO
        else Support.UNKNOWN
    } catch (_: RuntimeException) { Support.UNKNOWN }
}
