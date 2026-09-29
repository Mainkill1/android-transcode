package dev.forma.app.data

import dev.forma.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Versioned queue wire format. Unknown/corrupt data is surfaced, never silently erased. */
object JobCodec {
    fun encode(entries: List<QueueEntry>): String = JSONObject().put("schema", 2)
        .put("jobs", JSONArray(entries.map { entry ->
            val j = entry.spec
            val s = j.settings
            JSONObject().put("id", j.id).put("targetBytes", j.targetBytes ?: JSONObject.NULL).put("state", entry.state.name).put("message", entry.message)
                .put("source", JSONObject().put("uri", j.source.uri).put("name", j.source.name)
                    .put("durationMs", j.source.durationMs).put("width", j.source.width).put("height", j.source.height)
                    .put("videoTracks", j.source.videoTracks).put("audioTracks", j.source.audioTracks)
                    .put("hdr", j.source.hdr).put("bytes", j.source.bytes))
                .put("trim", JSONObject().put("startMs", j.trim.startMs).put("endMs", j.trim.endMs ?: JSONObject.NULL))
                .put("settings", JSONObject().put("container", s.container.name).put("video", s.video.name)
                    .put("rateControl", s.rateControl.name).put("crf", s.crf).put("videoKbps", s.videoKbps)
                    .put("maxHeight", s.maxHeight).put("fps", s.fps).put("audio", s.audio.name)
                    .put("audioKbps", s.audioKbps).put("audioTrack", s.audioTrack).put("stereo", s.stereo)
                    .put("denoise", s.denoise).put("deinterlace", s.deinterlace).put("keepMetadata", s.keepMetadata))
        })).toString()

    fun decode(text: String): List<QueueEntry> {
        val root = JSONObject(text)
        val wireSchema = root.get("schema")
        require(wireSchema is Int || wireSchema is Long) { "Queue schema must be an integer." }
        val schema = (wireSchema as Number).toLong()
        require(schema in 1L..2L) { "Unsupported queue schema. The original file has been preserved." }
        val jobs = root.getJSONArray("jobs")
        return (0 until jobs.length()).map { i ->
            val j = jobs.getJSONObject(i)
            val source = j.getJSONObject("source")
            val trim = j.getJSONObject("trim")
            val s = j.getJSONObject("settings")
            require(j.keys().asSequence().toSet().all { it in setOf("id","state","message","source","trim","settings","targetBytes") }) {
                "This build does not support a saved job feature. The original queue is preserved."
            }
            require(s.keys().asSequence().toSet().all { it in setOf("container","video","rateControl","crf","videoKbps","maxHeight","fps","audio","audioKbps","audioTrack","stereo","denoise","deinterlace","keepMetadata") }) {
                "This build cannot process saved editor effects. The original queue is preserved."
            }
            val target = if (schema == 1L || j.isNull("targetBytes")) null else j.get("targetBytes").let {
                require(it is Int || it is Long) { "Size limit must be integer decimal bytes." }
                (it as Number).toLong().also(UploadFit::validateTarget)
            }
            val id = j.getString("id")
            require(UUID.fromString(id).toString() == id) { "Invalid job identifier." }
            QueueEntry(JobSpec(id, Source(source.getString("uri"), source.getString("name"), source.getLong("durationMs"),
                source.getInt("width"), source.getInt("height"), source.getInt("videoTracks"), source.getInt("audioTracks"),
                source.getBoolean("hdr"), source.getLong("bytes")),
                Trim(trim.getLong("startMs"), if (trim.isNull("endMs")) null else trim.getLong("endMs")),
                Settings(Container.valueOf(s.getString("container")), VideoEncoder.valueOf(s.getString("video")),
                    RateControl.valueOf(s.getString("rateControl")), s.getInt("crf"), s.getInt("videoKbps"),
                    s.getInt("maxHeight"), s.getInt("fps"), AudioEncoder.valueOf(s.getString("audio")),
                    s.getInt("audioKbps"), s.getInt("audioTrack"), s.getBoolean("stereo"), s.getBoolean("denoise"),
                    s.getBoolean("deinterlace"), s.getBoolean("keepMetadata")), target),
                JobState.valueOf(j.getString("state")), j.getString("message"))
        }.also { require(it.map { j -> j.spec.id }.distinct().size == it.size) { "Duplicate job identifiers." } }
    }
}
