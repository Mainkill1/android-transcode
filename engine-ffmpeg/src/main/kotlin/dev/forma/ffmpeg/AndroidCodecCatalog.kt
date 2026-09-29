package dev.forma.ffmpeg

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import dev.forma.core.*

/** Call off the UI thread. Advertised capability is not successful device qualification. */
class AndroidCodecCatalog {
    data class Entry(val name: String, val mime: String, val encoder: Boolean,
                     val hardware: Support, val softwareOnly: Support)

    fun inventory(): List<Entry> = codecs().flatMap { info ->
        info.supportedTypes.filter { mime -> VideoFormat.values().any { it.mime == mime } }.map { mime ->
            Entry(info.name, mime, info.isEncoder, hardware(info), software(info))
        }
    }.sortedWith(compareBy<Entry> { it.mime }.thenBy { it.encoder }.thenBy { it.name })

    fun candidates(request: EncodeRequest): List<CodecCandidate> = codecs().filter {
        it.supportedTypes.any { mime -> mime == request.format.mime }
    }.mapIndexed { rank, info ->
        try {
            val caps = info.getCapabilitiesForType(request.format.mime)
            val formats = caps.colorFormats.toSet()
            // Flexible/surface support alone is not evidence for FFmpeg's planar buffer route.
            val buffer = when {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in formats -> BufferFormat.YUV420P
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats -> BufferFormat.NV12
                else -> null
            }
            val vc = caps.videoCapabilities
            val ec = if (info.isEncoder) caps.encoderCapabilities else null
            val requestedFormat = MediaFormat.createVideoFormat(request.format.mime, request.width, request.height).apply {
                setFloat(MediaFormat.KEY_FRAME_RATE, request.fps.toFloat())
                setInteger(MediaFormat.KEY_BIT_RATE, request.bitrate)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                if (buffer != null) setInteger(MediaFormat.KEY_COLOR_FORMAT, when (buffer) {
                    BufferFormat.YUV420P -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                    BufferFormat.NV12 -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                })
            }
            val supported = info.isEncoder && buffer != null && vc != null && ec != null &&
                !caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback) &&
                !caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback) &&
                request.width % vc.widthAlignment == 0 && request.height % vc.heightAlignment == 0 &&
                vc.areSizeAndRateSupported(request.width, request.height, request.fps) &&
                vc.bitrateRange.contains(request.bitrate) &&
                ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR) &&
                caps.isFormatSupported(requestedFormat)
            CodecCandidate(info.name, request.format, info.isEncoder, hardware(info),
                if (supported) Support.YES else Support.NO, buffer,
                if (supported) "Exact buffer/VBR configuration advertised; not device-qualified."
                else "Unsupported buffer format, alignment, size/rate, bitrate or codec feature.", request,
                if (supported) performanceHint(vc, request) else null, rank)
        } catch (error: RuntimeException) {
            CodecCandidate(info.name, request.format, info.isEncoder, hardware(info), Support.UNKNOWN, null,
                "Capability query failed: ${error.javaClass.simpleName}", request)
        }
    }

    /** A broken/missing optional OEM estimate must not invalidate an otherwise supported request. */
    private fun performanceHint(video: MediaCodecInfo.VideoCapabilities?, request: EncodeRequest): CodecPerformanceHint? =
        try {
            video?.getAchievableFrameRatesFor(request.width, request.height)?.let {
                CodecPerformanceHint.from(it.lower, it.upper)
            }
        } catch (_: RuntimeException) { null }

    private fun codecs(): List<MediaCodecInfo> = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            Build.VERSION.SDK_INT < 29 || !it.isAlias
        }
    } catch (_: RuntimeException) { emptyList() }

    private fun hardware(info: MediaCodecInfo): Support = if (Build.VERSION.SDK_INT >= 29)
        when { info.isSoftwareOnly -> Support.NO; info.isHardwareAccelerated -> Support.YES; else -> Support.UNKNOWN }
    else Support.UNKNOWN // Never guess hardware from a vendor component's name.

    private fun software(info: MediaCodecInfo): Support = if (Build.VERSION.SDK_INT >= 29)
        if (info.isSoftwareOnly) Support.YES else Support.NO
    else Support.UNKNOWN
}
