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
    fun expect(ok: Boolean, reason: String) { if (!ok) throw EncodedOutputRejected(reason) }
    val inputs=attempt.arguments.indices.filter { attempt.arguments[it]=="-i" }.map { attempt.arguments.getOrNull(it+1) }
    require(inputs.size==1 && inputs.single()!=null) { "Verify against the single original staged input." }
    val original=bridge.inspectStreams(requireNotNull(inputs.single()))
    val origin=requireNotNull(original.originUs) { "The original stream clock origin is unknown." }
    val actual=bridge.inspectStreams(output.absolutePath,countFrames=true)
    val videos=actual.streams.filter { it.kind==StreamKind.VIDEO }
    val audios=actual.streams.filter { it.kind==StreamKind.AUDIO }
    val video=settings.container!=Container.M4A
    val audio=source.audioTracks>0 && settings.audio!=AudioEncoder.NONE
    expect(videos.size==(if(video) 1 else 0) && audios.size==(if(audio) 1 else 0), "Output tracks do not match the job.")
    val from=Math.multiplyExact(trim.startMs,1000)
    val to=Math.multiplyExact(trim.endMs ?: source.durationMs,1000)
    require(to>from) { "The retained stream window is empty." }
    data class Window(val start: Long,val end: Long)
    fun window(stream: StreamFacts): Window {
        val start=Math.subtractExact(requireNotNull(stream.startUs) { "An original stream start is unknown." },origin)
        val end=Math.addExact(start,requireNotNull(stream.durationUs) { "An original stream duration is unknown." })
        val keptStart=maxOf(from,start);val keptEnd=minOf(to,end)
        require(keptEnd>keptStart) { "An included original stream has no data in the selected range." }
        return Window(keptStart-from,keptEnd-from)
    }
    fun timing(stream: StreamFacts, expected: Window,toleranceUs: Long,label: String) {
        val start=stream.startUs
        val duration=stream.durationUs
        expect(start!=null && duration!=null && duration>0,"$label stream timing is unknown or empty.")
        val end=runCatching { Math.addExact(requireNotNull(start),requireNotNull(duration)) }.getOrNull()
        expect(end!=null && abs(start!!.toDouble()-expected.start)<=toleranceUs &&
            abs(end!!.toDouble()-expected.end)<=toleranceUs,"$label stream start/end does not match the retained original range.")
    }
    if(video) {
        val picture=videos.single()
        val expected=window(original.streams.firstOrNull { it.kind==StreamKind.VIDEO }
            ?: error("The original video stream is missing."))
        val frameUs=if(settings.fps>0) ceil(1_000_000.0/settings.fps).toLong() else 50_000L
        timing(picture,expected,frameUs+1000,"Video")
        expect(picture.width>0 && picture.height>0 && picture.width%2==0 && picture.height%2==0,"Output dimensions are invalid.")
        expect(settings.maxHeight==0 || picture.height<=settings.maxHeight,"Output exceeds the requested height.")
        attempt.decision?.configuration?.takeIf { attempt.decision.backend==EncodeBackend.MEDIACODEC }?.let { request ->
            expect(picture.width==request.width && picture.height==request.height,"Device output dimensions do not match the exact requested dimensions.")
        }
        expect(!picture.hdr,"Unexpected output color precision/transfer; this route is SDR-only.")
        val frames=picture.decodedFrames
        expect(frames!=null && frames>0,"Decoded video frame count is unknown or empty.")
        if(settings.fps>0) {
            val count=(expected.end-expected.start).toDouble()*settings.fps/1_000_000.0
            // Only an intrinsically fractional frame window may choose its floor or ceiling.
            val lower=kotlin.math.floor(count+0.000001).toLong()
            val upper=kotlin.math.ceil(count-0.000001).toLong()
            expect(frames!! in lower..upper,"Decoded video frame count $frames differs from the retained CFR window ($lower–$upper).")
        }
    }
    if(audio) {
        val sound=audios.single()
        val expected=window(original.streams.filter { it.kind==StreamKind.AUDIO }.getOrNull(settings.audioTrack)
            ?: error("The selected original audio stream is missing."))
        val rate=sound.sampleRate
        expect(rate!=null && rate>0,"Output audio sample rate is unknown.")
        // AAC priming/trailing packets and container tick rounding can span two 1024-sample packets.
        val tolerance=maxOf(50_000L,ceil(2_048_000_000.0/requireNotNull(rate)).toLong())
        timing(sound,expected,tolerance,"Audio")
    }
}
