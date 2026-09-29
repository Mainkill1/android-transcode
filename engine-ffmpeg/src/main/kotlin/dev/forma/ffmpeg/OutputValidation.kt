package dev.forma.ffmpeg

import dev.forma.core.*
import dev.forma.core.audio.AudioGraphPlanner
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class OutputCompletenessRejected(message: String) : IllegalStateException(message)

/** Supplements full decode and the audio DSP verifier before any output publication. */
suspend fun verifyOutputStreams(bridge: FfmpegBridge, source: Source, trim: Trim, settings: Settings,
                                inputPath: String, outputPath: String) {
    fun expect(ok: Boolean, reason: String) { if (!ok) throw OutputCompletenessRejected(reason) }
    val original=bridge.inspectStreams(inputPath)
    val origin=requireNotNull(original.originUs) { "The original stream clock origin is unknown." }
    val actual=bridge.inspectStreams(outputPath,countFrames=true)
    val videos=actual.streams.filter { it.kind==StreamKind.VIDEO }
    val audios=actual.streams.filter { it.kind==StreamKind.AUDIO }
    val video=!settings.container.audioOnly
    val audio=source.audioTracks>0 && settings.audio!=AudioEncoder.NONE
    expect(videos.size==(if(video) 1 else 0) && audios.size==(if(audio) 1 else 0),"Output tracks do not match the job.")
    val graph=if(audio) AudioGraphPlanner.plan(source,trim,settings) else null
    val from=Math.multiplyExact(trim.startMs,1000)
    val to=Math.multiplyExact(trim.endMs ?: source.durationMs,1000)
    require(to>from) { "The retained stream window is empty." }
    data class Window(val start: Long,val end: Long)
    fun window(stream: StreamFacts): Window {
        val start=Math.subtractExact(requireNotNull(stream.startUs) { "An original stream start is unknown." },origin)
        val end=Math.addExact(start,requireNotNull(stream.durationUs) { "An original stream duration is unknown." })
        val keptStart=maxOf(from,start);val keptEnd=minOf(to,end)
        require(keptEnd>keptStart) { "An included original stream has no data in the selected range." }
        val relative=Window(keptStart-from,keptEnd-from)
        // A processed graph explicitly resets PTS; neutral exports preserve selected stream offsets.
        return if(graph?.processed==true) Window(0,relative.end-relative.start) else relative
    }
    fun timing(stream: StreamFacts, expected: Window,toleranceUs: Long,label: String) {
        val start=stream.startUs;val duration=stream.durationUs
        expect(start!=null && duration!=null && duration>0,"$label stream timing is unknown or empty.")
        val end=runCatching { Math.addExact(requireNotNull(start),requireNotNull(duration)) }.getOrNull()
        expect(end!=null && abs(start!!.toDouble()-expected.start)<=toleranceUs &&
            abs(end!!.toDouble()-expected.end)<=toleranceUs,"$label stream start/end does not match the retained range.")
    }
    if(video) {
        val picture=videos.single()
        val expected=window(original.streams.firstOrNull { it.kind==StreamKind.VIDEO }
            ?: error("The original video stream is missing."))
        val frameUs=if(settings.fps>0) ceil(1_000_000.0/settings.fps).toLong() else 50_000L
        timing(picture,expected,frameUs+1000,"Video")
        val frames=picture.decodedFrames
        expect(frames!=null && frames>0,"Decoded video frame count is unknown or empty.")
        if(settings.fps>0) {
            val count=(expected.end-expected.start).toDouble()*settings.fps/1_000_000.0
            val lower=floor(count+0.000001).toLong();val upper=ceil(count-0.000001).toLong()
            expect(frames!! in lower..upper,"Decoded video frame count $frames differs from the retained CFR window ($lower–$upper).")
        }
    }
    if(audio) {
        val sound=audios.single()
        val selected=original.streams.filter { it.kind==StreamKind.AUDIO }.getOrNull(settings.audioTrack)
            ?: error("The selected original audio stream is missing.")
        val expected=if(graph?.processed==true) Window(0,graph.outputDurationUs) else window(selected)
        val rate=sound.sampleRate
        expect(rate!=null && rate>0,"Output audio sample rate is unknown.")
        val tolerance=maxOf(50_000L,ceil(2_048_000_000.0/requireNotNull(rate)).toLong())
        timing(sound,expected,tolerance,"Audio")
    }
}

/** Runtime retry uses only explicit output rejection as a recoverable codec failure. */
suspend fun verifyEncodedOutput(bridge: FfmpegBridge, source: Source, trim: Trim, settings: Settings,
                               output: java.io.File, attempt: PreparedAttempt) {
    val decoded=bridge.execute(listOf("-hide_banner","-nostdin","-v","error","-xerror","-i",output.absolutePath,
        "-map","0:v:0?","-map","0:a:0?","-f","null","-")) {}
    if(decoded.exitCode!=0) when(decoded.failure) {
        FailureKind.CANCELLED -> throw kotlinx.coroutines.CancellationException("Output verification cancelled.")
        FailureKind.IO -> throw java.io.IOException("Output verification failed because of a storage/access error.")
        FailureKind.INVALID_INPUT -> throw EncodedOutputRejected("The encoded output could not be fully decoded by this native build.")
        else -> error("Output verification failed (${decoded.failure}). ${decoded.diagnostics}")
    }
    val inputs=attempt.arguments.indices.filter { attempt.arguments[it]=="-i" }.map { attempt.arguments.getOrNull(it+1) }
    require(inputs.size==1 && inputs.single()!=null) { "Verify against the single original staged input." }
    try {
        verifyOutputStreams(bridge,source,trim,settings,requireNotNull(inputs.single()),output.absolutePath)
    } catch(error:OutputCompletenessRejected) { throw EncodedOutputRejected(error.message.orEmpty()) }
    if(!settings.container.audioOnly) {
        val picture=bridge.inspectStreams(output.absolutePath,countFrames=true).streams.single { it.kind==StreamKind.VIDEO }
        fun expect(ok:Boolean,reason:String) { if(!ok)throw EncodedOutputRejected(reason) }
        expect(picture.width>0 && picture.height>0 && picture.width%2==0 && picture.height%2==0,"Output dimensions are invalid.")
        expect(settings.maxHeight==0 || picture.height<=settings.maxHeight,"Output exceeds the requested height.")
        attempt.decision?.configuration?.takeIf { attempt.decision.backend==EncodeBackend.MEDIACODEC }?.let { request ->
            expect(picture.width==request.width && picture.height==request.height,"Device output dimensions do not match the exact requested dimensions.")
        }
        expect(!picture.hdr,"Unexpected output color precision/transfer; this route is SDR-only.")
    }
}
