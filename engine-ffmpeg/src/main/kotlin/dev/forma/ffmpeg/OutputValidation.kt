package dev.forma.ffmpeg

import dev.forma.core.*
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException

/** Decode before publication. A successful native return alone is not a usable output. */
suspend fun verifyEncodedOutput(bridge: FfmpegBridge, source: Source, trim: Trim, settings: Settings,
                               output: File, attempt: PreparedAttempt) {
    val decoded = bridge.execute(listOf("-hide_banner", "-nostdin", "-v", "error", "-xerror",
        "-i", output.absolutePath, "-map", "0:v:0?", "-map", "0:a:0?", "-f", "null", "-")) {}
    if (decoded.exitCode != 0) {
        when (decoded.failure) {
            FailureKind.CANCELLED -> throw CancellationException("Output verification cancelled.")
            FailureKind.IO -> throw IOException("Output verification failed because of a storage/access error.")
            FailureKind.INVALID_INPUT -> throw EncodedOutputRejected("The encoded output could not be fully decoded by this native build.")
            else -> error("Output verification failed (${decoded.failure}). ${decoded.diagnostics}")
        }
    }
    val actual = bridge.probe(output.absolutePath)
    val video = if (settings.container == Container.M4A) 0 else 1
    val audio = if (source.audioTracks > 0 && settings.audio != AudioEncoder.NONE) 1 else 0
    fun expect(ok: Boolean, reason: String) { if (!ok) throw EncodedOutputRejected(reason) }
    expect(actual.videoTracks == video && actual.audioTracks == audio, "Output tracks do not match the job.")
    val tolerance = if (settings.fps > 0) maxOf(250L, ceil(2000.0 / settings.fps).toLong()) else 250L
    expect(actual.durationMs > 0 && abs(actual.durationMs - Planner.duration(source, trim)) <= tolerance,
        "Output duration does not match the selected range.")
    if (video > 0) {
        expect(actual.width > 0 && actual.height > 0 && actual.width % 2 == 0 && actual.height % 2 == 0,
            "Output dimensions are invalid.")
        expect(settings.maxHeight == 0 || actual.height <= settings.maxHeight, "Output exceeds the requested height.")
        attempt.decision?.configuration?.takeIf { attempt.decision.backend == EncodeBackend.MEDIACODEC }?.let { request ->
            expect(actual.width == request.width && actual.height == request.height,
                "Device output dimensions do not match the exact requested dimensions.")
        }
        expect(!actual.hdr, "Unexpected output color precision/transfer; this route is SDR-only.")
    }
}
