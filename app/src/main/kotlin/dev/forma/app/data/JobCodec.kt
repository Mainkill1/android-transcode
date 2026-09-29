package dev.forma.app.data

import dev.forma.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Schema 3 owns the entire immutable render request; legacy 1/2 jobs remain manual single-file exports. */
object JobCodec {
    fun encode(entries: List<QueueEntry>): String = JSONObject().put("schema", 3)
        .put("jobs", JSONArray(entries.map { entry ->
            val j = entry.spec
            JSONObject().put("id", j.id).put("state", entry.state.name).put("message", entry.message)
                .put("source", source(j.source)).put("trim", trim(j.trim)).put("settings", settings(j.settings))
                .put("targetBytes", j.targetBytes ?: JSONObject.NULL)
                .put("sequence", j.sequence?.let(::sequence) ?: JSONObject.NULL)
        })).toString()

    private fun source(s: Source) = JSONObject().put("uri", s.uri).put("name", s.name).put("durationMs", s.durationMs)
        .put("width", s.width).put("height", s.height).put("videoTracks", s.videoTracks).put("audioTracks", s.audioTracks)
        .put("hdr", s.hdr).put("bytes", s.bytes)
    private fun trim(t: Trim) = JSONObject().put("startMs", t.startMs).put("endMs", t.endMs ?: JSONObject.NULL)
    private fun settings(s: Settings) = JSONObject().put("container", s.container.name).put("video", s.video.name)
        .put("rateControl", s.rateControl.name).put("crf", s.crf).put("videoKbps", s.videoKbps).put("maxHeight", s.maxHeight)
        .put("fps", s.fps).put("audio", s.audio.name).put("audioKbps", s.audioKbps).put("audioTrack", s.audioTrack)
        .put("stereo", s.stereo).put("denoise", s.denoise).put("deinterlace", s.deinterlace).put("keepMetadata", s.keepMetadata)
        .put("effects", ClipEffectsCodec.encode(s.effects))
    private fun sequence(s: SequenceSpec) = JSONObject().put("transitionMs", s.transitionMs)
        .put("canvas", JSONObject().put("width", s.canvas.width).put("height", s.canvas.height).put("fps", s.canvas.fps)
            .put("fit", s.canvas.fit.name).put("backgroundRgb", s.canvas.backgroundRgb))
        .put("clips", JSONArray(s.timeline.clips.map { c -> JSONObject().put("id", c.id)
            .put("source", source(c.source)).put("trim", trim(c.trim)).put("settings", settings(c.settings)) }))

    private fun fields(j: JSONObject, known: Set<String>) {
        require(j.keys().asSequence().all { it in known }) { "Unknown saved field; refusing to discard a render setting." }
    }
    private fun long(j: JSONObject, key: String): Long {
        val v = j.get(key)
        require(v is Int || v is Long) { "$key must be an integer." }
        return (v as Number).toLong()
    }
    private fun int(j: JSONObject, key: String): Int = long(j, key).let {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key is out of range." }; it.toInt()
    }
    private fun bool(j: JSONObject, key: String): Boolean = j.get(key).also { require(it is Boolean) { "$key must be a boolean." } } as Boolean
    private fun readSource(j: JSONObject): Source {
        fields(j, setOf("uri", "name", "durationMs", "width", "height", "videoTracks", "audioTracks", "hdr", "bytes"))
        return Source(j.getString("uri"), j.getString("name"), long(j,"durationMs"), int(j,"width"), int(j,"height"),
            int(j,"videoTracks"), int(j,"audioTracks"), bool(j,"hdr"), long(j,"bytes"))
    }
    private fun readTrim(j: JSONObject): Trim {
        fields(j, setOf("startMs", "endMs"))
        return Trim(long(j,"startMs"), if (j.isNull("endMs")) null else long(j,"endMs"))
    }
    private fun readSettings(j: JSONObject, schema: Long): Settings {
        fields(j, setOf("container", "video", "rateControl", "crf", "videoKbps", "maxHeight", "fps", "audio", "audioKbps",
            "audioTrack", "stereo", "denoise", "deinterlace", "keepMetadata") + if(schema >= 2) setOf("effects") else emptySet())
        return Settings(Container.valueOf(j.getString("container")), VideoEncoder.valueOf(j.getString("video")),
            RateControl.valueOf(j.getString("rateControl")), int(j,"crf"), int(j,"videoKbps"), int(j,"maxHeight"), int(j,"fps"),
            AudioEncoder.valueOf(j.getString("audio")), int(j,"audioKbps"), int(j,"audioTrack"), bool(j,"stereo"),
            bool(j,"denoise"), bool(j,"deinterlace"), bool(j,"keepMetadata"),
            if (schema == 1L) ClipEffects() else ClipEffectsCodec.decode(j.getJSONObject("effects")))
    }
    private fun readSequence(j: JSONObject): SequenceSpec {
        fields(j, setOf("clips", "canvas", "transitionMs"))
        val c = j.getJSONObject("canvas")
        fields(c, setOf("width", "height", "fps", "fit", "backgroundRgb"))
        val clips = j.getJSONArray("clips")
        require(clips.length() in 1..SequencePlanner.MAX_RENDER_CLIPS) { "Saved movie has an unsupported number of clips." }
        return SequenceSpec(EditTimeline((0 until clips.length()).map { i ->
            val clip = clips.getJSONObject(i)
            fields(clip, setOf("id", "source", "trim", "settings"))
            TimelineClip(clip.getString("id"), readSource(clip.getJSONObject("source")), readTrim(clip.getJSONObject("trim")), readSettings(clip.getJSONObject("settings"),3))
        }), CanvasSpec(int(c,"width"), int(c,"height"), int(c,"fps"), CanvasFit.valueOf(c.getString("fit")), c.getString("backgroundRgb")), long(j,"transitionMs"))
    }
    fun decode(text: String): List<QueueEntry> {
        val root = JSONObject(text)
        fields(root, setOf("schema", "jobs"))
        val schema = long(root,"schema")
        require(schema in 1L..3L) { "Unsupported queue schema. The original file has been preserved." }
        val jobs = root.getJSONArray("jobs")
        require(jobs.length() <= 200) { "The saved queue exceeds its supported size." }
        return (0 until jobs.length()).map { i ->
            val j = jobs.getJSONObject(i)
            fields(j, setOf("id", "state", "message", "source", "trim", "settings") + if (schema == 3L) setOf("sequence", "targetBytes") else emptySet())
            val id = j.getString("id")
            require(UUID.fromString(id).toString() == id) { "Invalid job identifier." }
            if (schema == 3L) require(j.has("sequence") && j.has("targetBytes")) { "A saved render request is incomplete." }
            val spec = JobSpec(id, readSource(j.getJSONObject("source")), readTrim(j.getJSONObject("trim")), readSettings(j.getJSONObject("settings"),schema),
                if (schema == 3L && !j.isNull("sequence")) readSequence(j.getJSONObject("sequence")) else null,
                if (schema == 3L && !j.isNull("targetBytes")) long(j,"targetBytes") else null)
            val problems = JobPlans.validate(spec)
            require(problems.isEmpty()) { "Invalid saved job $id: ${problems.joinToString("; ")}" }
            QueueEntry(spec, JobState.valueOf(j.getString("state")), j.getString("message"))
        }.also { require(it.map { entry -> entry.spec.id }.distinct().size == it.size) { "Duplicate job identifiers." } }
    }
}
