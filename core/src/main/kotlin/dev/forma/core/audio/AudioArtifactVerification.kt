package dev.forma.core.audio

import dev.forma.core.*
import kotlin.math.abs

/** Structural facts supplement, never replace, full native decoder verification. */
object AudioArtifactVerification {
    fun problems(source: Source, trim: Trim, settings: Settings, output: Source, bytes: Long): List<String> = buildList {
        settings.audioEdit.output.maxBytes?.let { if (bytes >= it) add("The verified output exceeds the byte limit. Choose a lower bitrate or a larger limit.") }
        if (bytes <= 0) add("The output is empty.")
        val expectedVideo = if (settings.container.audioOnly) 0 else 1
        val expectedAudio = if (source.audioTracks > 0 && settings.audio != AudioEncoder.NONE) 1 else 0
        if (output.videoTracks != expectedVideo || output.audioTracks != expectedAudio) add("The output track layout does not match the plan.")
        val graph = if (expectedAudio == 1) AudioGraphPlanner.plan(source,trim,settings) else null
        val durationMs = if (settings.container.audioOnly) graph?.outputDurationUs?.div(1000) ?: Planner.duration(source,trim) else Planner.outputDuration(source,trim,settings)
        if (output.durationMs <= 0 || abs(output.durationMs-durationMs) > if (expectedVideo > 0) 250 else 100)
            add("The output duration does not match the selected range.")
        val audio = output.audioStreams.singleOrNull()
        if (expectedAudio == 1) {
            if (audio == null) add("The output audio facts could not be verified.")
            else {
                if (graph?.sampleRateHz != null && graph.sampleRateHz != audio.sampleRateHz) add("The output sample rate does not match the plan.")
                if (graph?.channels != null && graph.channels != audio.channels) add("The output channel count does not match the plan.")
                if (settings.audio in setOf(AudioEncoder.PCM_F32LE,AudioEncoder.PCM_S16LE,AudioEncoder.FLAC) &&
                    graph?.outputFrames != null && graph.outputFrames != audio.totalSamples) add("The PCM sample count does not match the plan.")
            }
        }
        if (expectedVideo > 0) {
            if (output.width <= 0 || output.height <= 0 || output.width % 2 != 0 || output.height % 2 != 0) add("The output dimensions are invalid.")
            if (settings.maxHeight > 0 && output.height > settings.maxHeight) add("The output exceeded the requested height.")
        }
    }
}
