package dev.forma.accelerationlab

import dev.forma.core.*
import kotlin.math.abs

/** Test APK/host tests only. Never install these experiments as a production fallback. */
enum class LabMode { JAVA, NDK, NDK_ASYNC, DECODE_BUFFER, SURFACE }
data class LabFixture(val width: Int, val height: Int, val fps: Int, val seconds: Int) {
    init {
        require((width to height) in setOf(640 to 360, 1280 to 720, 1920 to 1080, 3840 to 2160))
        require(fps in setOf(24, 30, 60, 120) && seconds in 2..30)
    }
    val frames: Int get() = fps * seconds
}

object LabCommands {
    fun optionNames(help: String): Set<String> =
        Regex("(?m)^\\s*-([a-z_][a-z_0-9]*)\\s").findAll(help).map { it.groupValues[1] }.toSet()

    fun variant(prepared: List<String>, mode: LabMode, request: EncodeRequest, fixture: LabFixture,
                encoderOptions: Set<String>, operatingRate: Int = 0): List<String> {
        require(operatingRate in 0..1000)
        require(request.width == fixture.width && request.height == fixture.height && request.fps == fixture.fps.toDouble())
        require(!request.hdr && request.bitDepth == 8 && !request.constantQuality && request.rotationDegrees % 360 == 0)
        require(prepared.count { it == "-i" } == 1 && prepared.count { it == "-c:v" } == 1)
        require(prepared.none { it.substringBefore(':') in setOf("-ndk_codec", "-ndk_async", "-hwaccel", "-init_hw_device", "-bsf") })
        require("ndk_codec" in encoderOptions) { "The loaded FFmpeg wrapper cannot explicitly select Java/NDK." }
        val args = prepared.toMutableList()
        fun value(key: String): String {
            require(args.count { it == key } == 1) { "Expected exactly one $key option." }
            val index = args.indexOf(key)
            require(index + 1 < args.lastIndex)
            return args[index + 1]
        }
        fun set(key: String, newValue: String) { value(key); args[args.indexOf(key) + 1] = newValue }
        fun remove(key: String) { value(key); val index = args.indexOf(key); args.removeAt(index + 1); args.removeAt(index) }
        fun outputOptions(vararg options: String) { args.addAll(args.lastIndex, options.toList()) }
        require(value("-c:v") == request.format.device)
        require(value("-b:v") == request.bitrate.toString())
        require(value("-codec_name:v").isNotBlank())
        require(value("-bitrate_mode:v") in CodecBitrateMode.values().map { it.ffmpeg })
        require(value("-bf:v") == "0")
        require(value("-r") == fixture.fps.toString() && value("-fps_mode") == "cfr")
        require(value("-t").toDouble() == fixture.seconds.toDouble())
        // Identity-only test corpus: do not discard a user filter/trim to make surfaces work.
        if (mode == LabMode.SURFACE) {
            require("-ss" !in args && "-filter_complex" !in args)
            require(value("-vf") == "scale=-2:'trunc(min(ih,${fixture.height})/2)*2',format=yuv420p,fps=${fixture.fps}") {
                "Surface experiment only accepts the generated identity fixture, not edits."
            }
            remove("-vf"); remove("-r")
            set("-fps_mode", "passthrough"); set("-pix_fmt", "mediacodec")
            args.addAll(0, listOf("-init_hw_device", "mediacodec=mc,create_window=1"))
            args.addAll(args.indexOf("-i"), listOf("-hwaccel", "mediacodec", "-hwaccel_device", "mc",
                "-hwaccel_output_format", "mediacodec"))
        }
        if (mode == LabMode.DECODE_BUFFER || mode == LabMode.SURFACE) {
            // Input is generated H.264. FFmpeg's decoder has no encoder-style codec_name option.
            args.addAll(args.indexOf("-i"), listOf("-c:v", "h264_mediacodec", "-ndk_codec:v", "1"))
        }
        val ndk = mode in setOf(LabMode.NDK, LabMode.NDK_ASYNC, LabMode.SURFACE)
        outputOptions("-ndk_codec:v", if (ndk) "1" else "0")
        if (mode == LabMode.NDK_ASYNC) {
            require("ndk_async" in encoderOptions) { "NDK async is not compiled in the loaded wrapper." }
            require(request.format in setOf(VideoFormat.H264, VideoFormat.HEVC)) { "Async extradata qualification currently covers H.264/HEVC only." }
            outputOptions("-ndk_async:v", "1", "-bsf:v", "extract_extradata")
        } else if ("ndk_async" in encoderOptions) outputOptions("-ndk_async:v", "0")
        if (operatingRate > 0) {
            require("operating_rate" in encoderOptions) { "No operating-rate option in this wrapper." }
            outputOptions("-operating_rate:v", operatingRate.toString())
        }
        // Debug logs expose the decoder selected internally by FFmpeg. Timings include logging.
        set("-loglevel", "debug")
        return args
    }
}

object FrameSequence {
    /** Raw decoded PTS from FFprobe, not timestamps repaired by a second transcode. */
    fun verify(pts: List<Double>, fixture: LabFixture) {
        require(pts.size == fixture.frames) { "Expected ${fixture.frames} decoded frames; got ${pts.size}." }
        require(pts.all { it.isFinite() }) { "Missing or non-finite frame timestamps." }
        require(abs(pts.first()) <= 1.0 / fixture.fps + 0.010) { "Unexpected first-frame offset." }
        require(pts.zipWithNext().all { (a, b) -> b > a }) { "Duplicate or reversed presentation timestamps." }
        pts.forEachIndexed { index, time ->
            require(abs((time - pts.first()) - index.toDouble() / fixture.fps) <= 0.002) {
                "Frame $index has an unexpected timestamp; do not hide frame drops or speed changes."
            }
        }
    }
}
