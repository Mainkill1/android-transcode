package dev.forma.ffmpeg

import dev.forma.core.*

data class NativeResult(val exitCode: Int, val diagnostics: String)

/** No activity, document-picker or queue ownership crosses this boundary. */
interface FfmpegBridge {
    suspend fun capabilities(): Capabilities
    suspend fun probe(localPath: String): Source
    /** Every application export must prepare again after staging or changing its size budget. */
    suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> {
        require(!settings.video.hardware || settings.container == Container.M4A) {
            "This bridge does not implement a checked Android hardware route."
        }
        return Planner.arguments(source, trim, settings, input, output)
    }
    /** Qualify each original input through the existing route before constructing the common graph. */
    suspend fun prepareSequence(sequence: SequenceSpec, settings: Settings, inputs: List<String>, output: String): List<String> {
        val problems = SequencePlanner.validate(sequence, settings, capabilities())
        require(problems.isEmpty()) { problems.joinToString("\n") }
        require(inputs.size == sequence.timeline.clips.size) { "Source count does not match the movie." }
        sequence.timeline.clips.forEachIndexed { index, clip ->
            prepare(clip.source, clip.trim, SequencePlanner.clipSettings(clip, settings, sequence.canvas), inputs[index], output)
        }
        return SequencePlanner.arguments(sequence, settings, inputs, output)
    }
    suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult
}
