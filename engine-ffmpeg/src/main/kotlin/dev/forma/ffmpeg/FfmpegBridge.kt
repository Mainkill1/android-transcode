package dev.forma.ffmpeg

import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.ffmpeg.image.ImageProbe

data class NativeResult(val exitCode: Int, val diagnostics: String)

/** No activity, document-picker or queue ownership crosses this boundary. */
interface FfmpegBridge {
    suspend fun capabilities(): Capabilities
    suspend fun probe(localPath: String): Source
    /** Every application export must prepare again after staging or changing its size budget. */
    suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> {
        require(!settings.video.hardware || settings.container.audioOnly) {
            "This bridge does not implement a checked Android hardware route."
        }
        return Planner.arguments(source, trim, settings, input, output)
    }
    suspend fun prepareAudio(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> =
        Planner.audioArguments(source,trim,settings,input,output)
    suspend fun inspectImage(localPath: String): ImageInfo {
        val facts = ImageProbe.inspect(localPath)
        val caps=capabilities()
        if(!caps.available || facts.format.decoder !in caps.decoders || facts.format.demuxer !in caps.demuxers)
            throw ImageFailure("CAPABILITY_UNAVAILABLE", "Native ${facts.format.decoder} decoder / ${facts.format.demuxer} demuxer are unavailable. Rebuild with the image-native profile.")
        val decoded = execute(listOf("-hide_banner", "-nostdin", "-v", "error", "-xerror", "-noautorotate", "-i", localPath, "-map", "0:v:0", "-an", "-sn", "-dn", "-f", "null", "-")) {}
        if (decoded.exitCode != 0) throw ImageFailure("DECODE_FAILED", "Native image decode failed. ${decoded.diagnostics}")
        val decodedFacts=probe(localPath)
        if(decodedFacts.videoTracks!=1 || decodedFacts.audioTracks!=0 || decodedFacts.width!=facts.width || decodedFacts.height!=facts.height || decodedFacts.hdr)
            throw ImageFailure("DECODE_FAILED", "Native decoded image facts disagree with the inspected still-image header.")
        return facts
    }
    suspend fun prepare(spec: ImageJobSpec, actual: ImageInfo, attempt: ImageAttempt, input: String, output: String): List<String> =
        ImagePlanner.plan(actual, spec, attempt, capabilities()).arguments(input, output)
    suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult
}
