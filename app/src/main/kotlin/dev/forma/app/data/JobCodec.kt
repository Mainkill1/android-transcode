package dev.forma.app.data

import dev.forma.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Versioned queue wire format. Unknown/corrupt data is surfaced, never silently erased. */
object JobCodec {
    private fun fields(value: JSONObject, allowed: Set<String>) {
        require(value.keys().asSequence().toSet().all { it in allowed }) { "Unsupported saved field. The original queue is preserved." }
    }
    private fun integer(value: JSONObject, key: String): Long {
        val raw = value.get(key)
        require(raw is Int || raw is Long) { "Saved $key must be an integer. The original queue is preserved." }
        return (raw as Number).toLong()
    }
    private fun int(value: JSONObject, key: String): Int = integer(value,key).also {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Saved $key is out of range." }
    }.toInt()
    private fun string(value: JSONObject, key: String): String = value.get(key).let {
        require(it is String) { "Saved $key must be text." }; it
    }
    private fun boolean(value: JSONObject, key: String): Boolean = value.get(key).let {
        require(it is Boolean) { "Saved $key must be a Boolean." }; it
    }
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
        fields(root,setOf("schema","jobs"))
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
            fields(source,setOf("uri","name","durationMs","width","height","videoTracks","audioTracks","hdr","bytes"))
            fields(trim,setOf("startMs","endMs"))
            require(trim.has("endMs")) { "Saved trim requires explicit end intent. The original queue is preserved." }
            require(schema != 1L || !j.has("targetBytes")) { "Schema 1 cannot represent a size limit. The original queue is preserved." }
            require(schema != 2L || j.has("targetBytes")) { "Schema 2 requires explicit size-limit intent. The original queue is preserved." }
            val target = if (schema == 1L || j.isNull("targetBytes")) null else j.get("targetBytes").let {
                require(it is Int || it is Long) { "Size limit must be integer decimal bytes." }
                (it as Number).toLong().also(UploadFit::validateTarget)
            }
            val id = string(j,"id")
            require(UUID.fromString(id).toString() == id) { "Invalid job identifier." }
            QueueEntry(JobSpec(id, Source(string(source,"uri"), string(source,"name"), integer(source,"durationMs"),
                int(source,"width"), int(source,"height"), int(source,"videoTracks"), int(source,"audioTracks"),
                boolean(source,"hdr"), integer(source,"bytes")),
                Trim(integer(trim,"startMs"), if (trim.isNull("endMs")) null else integer(trim,"endMs")),
                Settings(Container.valueOf(string(s,"container")), VideoEncoder.valueOf(string(s,"video")),
                    RateControl.valueOf(string(s,"rateControl")), int(s,"crf"), int(s,"videoKbps"),
                    int(s,"maxHeight"), int(s,"fps"), AudioEncoder.valueOf(string(s,"audio")),
                    int(s,"audioKbps"), int(s,"audioTrack"), boolean(s,"stereo"), boolean(s,"denoise"),
                    boolean(s,"deinterlace"), boolean(s,"keepMetadata")), target),
                JobState.valueOf(string(j,"state")), string(j,"message"))
        }.also { require(it.map { j -> j.spec.id }.distinct().size == it.size) { "Duplicate job identifiers." } }
    }
}
