package dev.forma.ffmpeg

import dev.forma.core.*
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Verify each included stream, rather than letting the longest track mask a missing picture/sound tail. */
internal fun verifyMovieStreams(job: JobSpec, settings: Settings, originals: List<OutputFacts>, actual: OutputFacts) {
    val sequence = job.sequence
    val durationUs = JobPlans.duration(job) * 1000
    val kinds = buildList {
        if (!settings.container.audioOnly) add(StreamKind.VIDEO)
        if (JobPlans.hasAudio(job)) add(StreamKind.AUDIO)
    }
    check(actual.streams.filter { it.kind != StreamKind.OTHER }.map { it.kind }.sortedBy { it.ordinal } == kinds.sortedBy { it.ordinal }) { "Output stream count changed." }
    kinds.forEach { kind ->
        val stream = actual.streams.single { it.kind == kind }
        val start = requireNotNull(stream.startUs) { "Output stream start is unknown." }
        val length = requireNotNull(stream.durationUs) { "Output stream duration is unknown." }
        check(length > 0) { "Output stream is empty." }
        val expected = if (sequence != null) 0L to durationUs else {
            val original = originals.single()
            val origin = requireNotNull(original.originUs) { "Source timeline origin is unknown." }
            val selected = original.streams.filter { it.kind == kind }.getOrNull(if(kind == StreamKind.AUDIO) settings.audioTrack else 0)
                ?: error("Included original stream is missing.")
            val sourceStart = requireNotNull(selected.startUs) { "Source stream start is unknown." } - origin
            val sourceDuration = requireNotNull(selected.durationUs) { "Source stream duration is unknown." }
            val trimStart = job.trim.startMs * 1000
            val trimEnd = (job.trim.endMs ?: job.source.durationMs) * 1000
            val graph=if(JobPlans.hasAudio(job))dev.forma.core.audio.AudioGraphPlanner.plan(job.source,job.trim,settings)else null
            val speed=settings.effects.speedPercent/100.0*(if(kind==StreamKind.AUDIO)settings.audioEdit.rate.value else 1.0)
            val keptStart=maxOf(0,sourceStart-trimStart)
            val keptEnd=minOf(trimEnd,sourceStart+sourceDuration)-trimStart
            if(graph?.originNormalized==true) {
                if(kind==StreamKind.AUDIO)0L to graph.outputDurationUs else 0L to ((keptEnd-keptStart)/speed).toLong()
            }else (keptStart/speed).toLong() to (keptEnd/speed).toLong()
        }
        check(expected.second > expected.first) { "The selected range contains no included stream." }
        val tolerance = if(kind == StreamKind.VIDEO && settings.fps > 0) maxOf(2000L, ceil(1_000_000.0/settings.fps).toLong()) else if(kind==StreamKind.AUDIO) maxOf(50_000L,ceil(2_048_000_000.0/(stream.sampleRate ?: 48000)).toLong())else 50_000L
        check(abs(start - expected.first) <= tolerance && abs(start + length - expected.second) <= tolerance) {
            "$kind stream timing does not match the selected movie: $start..${start+length}, expected ${expected.first}..${expected.second}."
        }
        if(kind == StreamKind.VIDEO) {
            check(!stream.hdr){"Unexpected HDR/high-precision output."}
            if(sequence!=null)check(stream.width==sequence.canvas.width && stream.height==sequence.canvas.height){"Typed movie dimensions do not match the canvas."}
            val frames = requireNotNull(stream.decodedFrames) { "Decoded video frame count is unknown." }
            check(frames > 0) { "Decoded video frame count is empty." }
            if(settings.fps == 0) return@forEach
            val count = if(sequence != null) {
                (SequencePlanner.frames(sequence).sum() - SequencePlanner.overlapFrames(sequence)*(sequence.timeline.clips.size-1)).toDouble()
            } else (expected.second-expected.first) * settings.fps / 1_000_000.0
            check(frames in floor(count + 0.000001).toLong()..ceil(count - 0.000001).toLong()) {
                "Decoded video frame count $frames does not match requested $count."
            }
        }
    }
}
