package dev.forma.ffmpeg

import dev.forma.core.*

data class NativeResult(val exitCode: Int, val diagnostics: String)

/** No activity, document-picker or queue ownership crosses this boundary. */
interface FfmpegBridge {
    suspend fun capabilities(): Capabilities
    suspend fun probe(localPath: String): Source
    /** Fresh native stream clocks/counts; never persisted as source summaries. */
    suspend fun inspectStreams(localPath: String, countFrames: Boolean = false): OutputFacts =
        error("This native bridge cannot inspect stream completeness.")
    /** Every application export must prepare again after staging or changing its size budget. */
    suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> {
        require(!settings.video.hardware || settings.container.audioOnly) {
            "This bridge does not implement a checked Android hardware route."
        }
        return Planner.arguments(source, trim, settings, input, output)
    }
    suspend fun prepareAudio(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> =
        Planner.audioArguments(source,trim,settings,input,output)
    suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult
}

enum class StreamKind { VIDEO, AUDIO, OTHER }
data class StreamFacts(
    val kind: StreamKind, val startUs: Long?, val durationUs: Long?, val decodedFrames: Long? = null,
    val width: Int = 0, val height: Int = 0, val hdr: Boolean = false, val sampleRate: Int? = null
)
data class OutputFacts(val originUs: Long?, val streams: List<StreamFacts>)

/** Parses only FFprobe fields; malformed/absent clocks stay unknown and cannot satisfy verification. */
object OutputFactsReader {
    fun read(origin: String?, streams: List<Map<String,String>>): OutputFacts {
        fun micros(value: String?): Long? = value?.toBigDecimalOrNull()?.let {
            runCatching { it.movePointRight(6).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact() }.getOrNull()
        }
        val facts=streams.filterNot { it["attached_pic"] == "1" || it["disposition:attached_pic"] == "1" }.map { s ->
            val kind=when(s["codec_type"]) { "video" -> StreamKind.VIDEO; "audio" -> StreamKind.AUDIO; else -> StreamKind.OTHER }
            val start=ticks(s["start_pts"],s["time_base"]) ?: micros(s["start_time"])
                ?: ticks(s["observed_start_pts"],s["time_base"]) ?: micros(s["observed_start_time"])
            val tag=s["tag:DURATION"]?.split(':')?.takeIf { it.size==3 }?.let { parts ->
                runCatching { (parts[0].toBigDecimal()*3600.toBigDecimal()+parts[1].toBigDecimal()*60.toBigDecimal()+parts[2].toBigDecimal()).toPlainString() }.getOrNull()
            }
            val duration=ticks(s["duration_ts"],s["time_base"]) ?: micros(s["duration"])
                ?: micros(tag)?.let { end -> start?.let { runCatching { Math.subtractExact(end,it) }.getOrNull() } }
            StreamFacts(kind,start,duration,s["nb_read_frames"]?.toLongOrNull()?.takeIf { it>=0 },
                s["width"]?.toIntOrNull() ?: 0,s["height"]?.toIntOrNull() ?: 0,
                kind==StreamKind.VIDEO && dev.forma.core.ColorRules.needsQualifiedPipeline(s["pix_fmt"].orEmpty(),s["color_transfer"].orEmpty(),s["bits_per_raw_sample"]?.toIntOrNull() ?: 0),
                s["sample_rate"]?.toIntOrNull()?.takeIf { it>0 })
        }
        val retained=facts.filter { it.kind!=StreamKind.OTHER }
        // A missing format origin can be established by actual stream presentation clocks.
        // Clockless WAV/FLAC observations come from packets/decoded frames, never an assumed zero.
        val measuredOrigin=if(retained.isNotEmpty() && retained.all { it.startUs!=null }) retained.minOf { it.startUs!! } else null
        return OutputFacts(micros(origin) ?: measuredOrigin,facts)
    }
    private fun ticks(count: String?, base: String?): Long? {
        val n=count?.toLongOrNull() ?: return null
        val ratio=base?.split('/')?.takeIf { it.size==2 } ?: return null
        return runCatching {
            val a=ratio[0].toBigDecimal();val b=ratio[1].toBigDecimal()
            require(a.signum()>0 && b.signum()>0)
            n.toBigDecimal().multiply(a).multiply(1_000_000.toBigDecimal()).divide(b,0,java.math.RoundingMode.HALF_UP).longValueExact()
        }.getOrNull()
    }
}
