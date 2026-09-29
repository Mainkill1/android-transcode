package dev.forma.app.data

import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.core.audio.AudioEdit
import dev.forma.core.audio.SourceAudioFacts
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Versioned queue wire format. Unknown/corrupt data is surfaced, never silently erased. */
object JobCodec {
    fun encode(entries: List<QueueEntry>): String = JSONObject().put("schema", 3)
        .put("jobs", JSONArray(entries.map { entry ->
            val tagged = entry.spec
            if (tagged is QueueJobSpec.Image) {
                return@map JSONObject().put("kind", "image").put("id", tagged.id).put("state", entry.state.name).put("message", entry.message)
                    .put("resolvedFormat",tagged.job.resolvedFormat?.name?:JSONObject.NULL).put("document", ImageDocumentCodec.encode(tagged.job.document)).put("info", tagged.job.info?.let(ImageDocumentCodec::encodeInfo) ?: JSONObject.NULL)
            }
            val j = (tagged as QueueJobSpec.Av).job
            val s = j.settings
            JSONObject().put("kind", "av").put("id", j.id).put("state", entry.state.name).put("message", entry.message)
                .put("source", JSONObject().put("uri", j.source.uri).put("name", j.source.name)
                    .put("durationMs", j.source.durationMs).put("width", j.source.width).put("height", j.source.height)
                    .put("videoTracks", j.source.videoTracks).put("audioTracks", j.source.audioTracks)
                    .put("hdr", j.source.hdr).put("bytes", j.source.bytes)
                    .put("audioStreams", JSONArray(j.source.audioStreams.map { f ->
                        JSONObject().put("streamIndex", f.streamIndex).put("sampleRateHz", f.sampleRateHz ?: JSONObject.NULL)
                            .put("channels", f.channels ?: JSONObject.NULL).put("layout", f.channelLayout ?: JSONObject.NULL)
                            .put("sampleFormat", f.sampleFormat ?: JSONObject.NULL).put("durationUs", f.durationUs)
                            .put("codecDelaySamples", f.encoderDelaySamples ?: JSONObject.NULL)
                            .put("paddingSamples", f.paddingSamples ?: JSONObject.NULL).put("totalSamples", f.totalSamples ?: JSONObject.NULL)
                            .put("codec", f.codec ?: JSONObject.NULL).put("language", f.language ?: JSONObject.NULL).put("title", f.title ?: JSONObject.NULL).put("timelineOffsetUs", f.timelineOffsetUs ?: JSONObject.NULL)
                    })))
                .put("trim", JSONObject().put("startMs", j.trim.startMs).put("endMs", j.trim.endMs ?: JSONObject.NULL))
                .put("settings", JSONObject().put("container", s.container.name).put("video", s.video.name)
                    .put("rateControl", s.rateControl.name).put("crf", s.crf).put("videoKbps", s.videoKbps)
                    .put("maxHeight", s.maxHeight).put("fps", s.fps).put("audio", s.audio.name)
                    .put("audioKbps", s.audioKbps).put("audioTrack", s.audioTrack).put("stereo", s.stereo)
                    .put("denoise", s.denoise).put("deinterlace", s.deinterlace).put("keepMetadata", s.keepMetadata)
                    .put("audioEdit", AudioEditCodec.encode(s.audioEdit)))
        })).toString()

    fun decode(text: String): List<QueueEntry> {
        val root = JSONObject(text)
        val schema = root.getInt("schema")
        require(schema in 1..3) { "Unsupported queue schema. The original file has been preserved." }
        val jobs = root.getJSONArray("jobs")
        return (0 until jobs.length()).map { i ->
            val j = jobs.getJSONObject(i)
            if (schema == 3 && j.getString("kind") == "image") {
                require(j.has("resolvedFormat")){"Missing saved resolved format; original queue preserved."}
                require(j.has("info")){"Missing saved image facts; original queue preserved."}
                val id = j.getString("id")
                require(UUID.fromString(id).toString() == id)
                return@map QueueEntry(QueueJobSpec.Image(ImageJobSpec(id, ImageDocumentCodec.decode(j.getJSONObject("document")),
                    if (j.isNull("info")) null else ImageDocumentCodec.decodeInfo(j.getJSONObject("info")),if(j.isNull("resolvedFormat"))null else ImageFormat.valueOf(j.getString("resolvedFormat")))), JobState.valueOf(j.getString("state")), j.getString("message"))
            }
            require(schema < 3 || j.getString("kind") == "av") { "Unsupported queue kind; original preserved." }
            val source = j.getJSONObject("source")
            val trim = j.getJSONObject("trim")
            require(trim.has("endMs")){"Missing saved trim endpoint; original queue preserved."}
            val s = j.getJSONObject("settings")
            if(schema>=2)validateSavedAudioNodeIntent(s.getJSONObject("audioEdit"))
            val id = j.getString("id")
            require(UUID.fromString(id).toString() == id) { "Invalid job identifier." }
            QueueEntry(JobSpec(id, Source(source.getString("uri"), source.getString("name"), source.getLong("durationMs"),
                source.getInt("width"), source.getInt("height"), source.getInt("videoTracks"), source.getInt("audioTracks"),
                source.getBoolean("hdr"), source.getLong("bytes"), decodeStreams(source)),
                Trim(trim.getLong("startMs"), if (trim.isNull("endMs")) null else trim.getLong("endMs")),
                Settings(Container.valueOf(s.getString("container")), VideoEncoder.valueOf(s.getString("video")),
                    RateControl.valueOf(s.getString("rateControl")), s.getInt("crf"), s.getInt("videoKbps"),
                    s.getInt("maxHeight"), s.getInt("fps"), AudioEncoder.valueOf(s.getString("audio")),
                    s.getInt("audioKbps"), s.getInt("audioTrack"), s.getBoolean("stereo"), s.getBoolean("denoise"),
                    s.getBoolean("deinterlace"), s.getBoolean("keepMetadata"),
                    if (schema == 1) AudioEdit() else AudioEditCodec.decode(s.getJSONObject("audioEdit")))),
                JobState.valueOf(j.getString("state")), j.getString("message"))
        }.also { require(it.map { j -> j.spec.id }.distinct().size == it.size) { "Duplicate job identifiers." } }
    }

    private fun decodeStreams(source: JSONObject): List<SourceAudioFacts> {
        val streams = source.optJSONArray("audioStreams") ?: return emptyList()
        fun JSONObject.text(key: String): String? = if (isNull(key)) null else getString(key)
        fun JSONObject.long(key: String): Long? = if (isNull(key)) null else getLong(key)
        return (0 until streams.length()).map { i ->
            val f = streams.getJSONObject(i)
            SourceAudioFacts(f.getInt("streamIndex"), f.long("sampleRateHz")?.toInt(), f.long("channels")?.toInt(),
                f.text("layout"), f.text("sampleFormat"), f.getLong("durationUs"), f.long("codecDelaySamples"),
                f.long("paddingSamples"), f.long("totalSamples"), f.text("codec"), f.text("language"), f.text("title"), f.long("timelineOffsetUs"))
        }
    }
}
