package dev.forma.app.data

import dev.forma.core.*
import dev.forma.core.settings.*
import dev.forma.core.audio.AudioEdit
import dev.forma.core.audio.SourceAudioFacts
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Versioned queue wire format. Unknown/corrupt data is surfaced, never silently erased. */
object JobCodec {
    private fun fields(value: JSONObject, allowed: Set<String>, required: Set<String> = allowed) {
        val keys=value.keys().asSequence().toSet()
        require(keys.all { it in allowed } && keys.containsAll(required)) {
            "Unsupported or missing saved field. The original queue is preserved."
        }
    }
    private fun integer(value: JSONObject,key: String): Long {
        val raw=value.get(key)
        require(raw is Int || raw is Long) { "Saved $key must be an integer. The original queue is preserved." }
        return (raw as Number).toLong()
    }
    private fun int(value: JSONObject,key: String): Int = integer(value,key).also {
        require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Saved $key is out of range." }
    }.toInt()
    private fun string(value: JSONObject,key: String): String = value.get(key).let {
        require(it is String) { "Saved $key must be text." };it
    }
    private fun boolean(value: JSONObject,key: String): Boolean = value.get(key).let {
        require(it is Boolean) { "Saved $key must be a Boolean." };it
    }

    fun encode(entries: List<QueueEntry>): String = JSONObject().put("schema", 3)
        .put("jobs", JSONArray(entries.map { entry ->
            val j = entry.spec
            val s = j.settings
            JSONObject().put("id", j.id).put("state", entry.state.name).put("message", entry.message)
                .put("preferences", encodePreferences(j.preferences))
                .put("completedAtMs",entry.completedAtMs ?: JSONObject.NULL)
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
        fields(root,setOf("schema","jobs"))
        val schema = int(root,"schema")
        require(schema in 1..3) { "Unsupported queue schema. The original file has been preserved." }
        val jobs = root.getJSONArray("jobs")
        return (0 until jobs.length()).map { i ->
            val j = jobs.getJSONObject(i)
            val base=setOf("id","state","message","source","trim","settings")
            fields(j,if(schema==3) base+setOf("preferences","completedAtMs") else base)
            val source = j.getJSONObject("source")
            val trim = j.getJSONObject("trim")
            val s = j.getJSONObject("settings")
            val sourceFields=setOf("uri","name","durationMs","width","height","videoTracks","audioTracks","hdr","bytes")
            fields(source,sourceFields+"audioStreams",if(schema==3) sourceFields+"audioStreams" else sourceFields)
            fields(trim,setOf("startMs","endMs"))
            val settingFields=setOf("container","video","rateControl","crf","videoKbps","maxHeight","fps","audio","audioKbps","audioTrack","stereo","denoise","deinterlace","keepMetadata")
            fields(s,if(schema==1) settingFields else settingFields+"audioEdit")
            if(schema>1) validateAudioWire(s.getJSONObject("audioEdit"),schema==3)
            if(schema==3) validateSavedAudioNodeIntent(s.getJSONObject("audioEdit"))
            val id = string(j,"id")
            require(UUID.fromString(id).toString() == id) { "Invalid job identifier." }
            val settings = Settings(Container.valueOf(string(s,"container")), VideoEncoder.valueOf(string(s,"video")),
                    RateControl.valueOf(string(s,"rateControl")), int(s,"crf"), int(s,"videoKbps"),
                    int(s,"maxHeight"), int(s,"fps"), AudioEncoder.valueOf(string(s,"audio")),
                    int(s,"audioKbps"), int(s,"audioTrack"), boolean(s,"stereo"), boolean(s,"denoise"),
                    boolean(s,"deinterlace"), boolean(s,"keepMetadata"),
                    if (schema == 1) AudioEdit() else AudioEditCodec.decode(s.getJSONObject("audioEdit")))
            QueueEntry(JobSpec(id, Source(string(source,"uri"), string(source,"name"), integer(source,"durationMs"),
                int(source,"width"), int(source,"height"), int(source,"videoTracks"), int(source,"audioTracks"),
                boolean(source,"hdr"), integer(source,"bytes"), decodeStreams(source,schema)),
                Trim(integer(trim,"startMs"), if (trim.isNull("endMs")) null else integer(trim,"endMs")),
                settings, if(schema < 3) MediaPreferences.legacy(settings) else decodePreferences(j.getJSONObject("preferences"))),
                JobState.valueOf(string(j,"state")), string(j,"message"),
                if(schema<3 || j.isNull("completedAtMs")) null else integer(j,"completedAtMs").also { require(it>=0) })
        }.also { require(it.map { j -> j.spec.id }.distinct().size == it.size) { "Duplicate job identifiers." } }
    }

    private fun encodePreferences(p: MediaPreferences) = JSONObject()
        .put("legacySnapshot",p.legacySnapshot)
        .put("app",SettingsCodec.encode(p.app))
        .put("preset",SettingsCodec.encode(SettingsDocument(values=p.preset)))
        .put("overrides",SettingsCodec.encode(SettingsDocument(values=p.overrides)))
        .put("presetName",p.presetName ?: JSONObject.NULL)

    private fun decodePreferences(p: JSONObject): MediaPreferences {
        fields(p,setOf("legacySnapshot","app","preset","overrides","presetName"))
        return MediaPreferences(
        SettingsCodec.decode(string(p,"app")),
        SettingsCodec.decode(string(p,"preset")).values,
        (if(boolean(p,"legacySnapshot")) SettingsCodec.decodeLegacyMediaSnapshot(string(p,"overrides")) else SettingsCodec.decode(string(p,"overrides"))).values,
        if(p.isNull("presetName")) null else string(p,"presetName"), boolean(p,"legacySnapshot"))
    }

    private fun decodeStreams(source: JSONObject,schema:Int): List<SourceAudioFacts> {
        if(!source.has("audioStreams")) return emptyList()
        val streams = source.getJSONArray("audioStreams")
        fun JSONObject.text(key: String): String? = if (isNull(key)) null else string(this,key)
        fun JSONObject.long(key: String): Long? = if (isNull(key)) null else integer(this,key)
        return (0 until streams.length()).map { i ->
            val f = streams.getJSONObject(i)
            fields(f,setOf("streamIndex","sampleRateHz","channels","layout","sampleFormat","durationUs","codecDelaySamples","paddingSamples","totalSamples","codec","language","title","timelineOffsetUs"),
                setOf("streamIndex","sampleRateHz","channels","layout","sampleFormat","durationUs","codecDelaySamples","paddingSamples","totalSamples","codec","language","title"))
            if(schema==3) require(f.has("timelineOffsetUs")) { "Saved source offset intent is missing." }
            fun nullableInt(key:String):Int?=if(f.isNull(key)) null else int(f,key)
            SourceAudioFacts(int(f,"streamIndex"), nullableInt("sampleRateHz"), nullableInt("channels"),
                f.text("layout"), f.text("sampleFormat"), integer(f,"durationUs"), f.long("codecDelaySamples"),
                f.long("paddingSamples"), f.long("totalSamples"), f.text("codec"), f.text("language"), f.text("title"), if(!f.has("timelineOffsetUs")) null else f.long("timelineOffsetUs"))
        }
    }
    /** Future graph payloads remain opaque. Recognized timing/output policy cannot be coerced. */
    private fun validateAudioWire(edit:JSONObject, requiredWriterFields:Boolean) {
        val version=int(edit,"schema")
        if(version!=1 || edit.keys().asSequence().any { it !in setOf("schema","nodes","rate","output") }) return
        if(requiredWriterFields) {
            edit.getJSONObject("rate");edit.getJSONObject("output");edit.getJSONArray("nodes")
        }
        edit.optJSONObject("rate")?.let { rate ->
            if(requiredWriterFields) require(rate.has("numerator") && rate.has("denominator"))
            for(key in setOf("numerator","denominator")) if(rate.has(key)) int(rate,key)
        }
        edit.optJSONObject("output")?.let { output ->
            if(requiredWriterFields) require(output.keys().asSequence().toSet().containsAll(setOf("channels","sampleRateHz","maxBytes","normalization")))
            if(output.has("sampleRateHz") && !output.isNull("sampleRateHz")) int(output,"sampleRateHz")
            if(output.has("maxBytes") && !output.isNull("maxBytes")) integer(output,"maxBytes")
            if(output.has("channels") && !output.isNull("channels")) string(output,"channels")
            if(requiredWriterFields) {
                val normalization=output.getJSONObject("normalization")
                val known=setOf("mode","peakDb","integratedLufs","truePeakDb","preserveDynamics")
                val keys=normalization.keys().asSequence().toSet()
                // Unknown normalization remains an opaque blocked graph in AudioEditCodec.
                if(keys.all { it in known }) require(keys.containsAll(known)) { "Saved normalization intent is missing." }
            }
        }
        edit.optJSONArray("nodes")?.let { nodes ->
            for(i in 0 until nodes.length()) {
                val node=nodes.getJSONObject(i)
                int(node,"version");string(node,"id");string(node,"type");boolean(node,"enabled")
            }
        }
    }
}
