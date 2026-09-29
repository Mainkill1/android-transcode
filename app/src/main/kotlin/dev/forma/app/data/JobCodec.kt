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
        val schema = root.getInt("schema")
        require(schema in 1..3) { "Unsupported queue schema. The original file has been preserved." }
        val jobs = root.getJSONArray("jobs")
        return (0 until jobs.length()).map { i ->
            val j = jobs.getJSONObject(i)
            val source = j.getJSONObject("source")
            val trim = j.getJSONObject("trim")
            val s = j.getJSONObject("settings")
            val id = j.getString("id")
            require(UUID.fromString(id).toString() == id) { "Invalid job identifier." }
            val settings = Settings(Container.valueOf(s.getString("container")), VideoEncoder.valueOf(s.getString("video")),
                    RateControl.valueOf(s.getString("rateControl")), s.getInt("crf"), s.getInt("videoKbps"),
                    s.getInt("maxHeight"), s.getInt("fps"), AudioEncoder.valueOf(s.getString("audio")),
                    s.getInt("audioKbps"), s.getInt("audioTrack"), s.getBoolean("stereo"), s.getBoolean("denoise"),
                    s.getBoolean("deinterlace"), s.getBoolean("keepMetadata"),
                    if (schema == 1) AudioEdit() else AudioEditCodec.decode(s.getJSONObject("audioEdit")))
            QueueEntry(JobSpec(id, Source(source.getString("uri"), source.getString("name"), source.getLong("durationMs"),
                source.getInt("width"), source.getInt("height"), source.getInt("videoTracks"), source.getInt("audioTracks"),
                source.getBoolean("hdr"), source.getLong("bytes"), decodeStreams(source)),
                Trim(trim.getLong("startMs"), if (trim.isNull("endMs")) null else trim.getLong("endMs")),
                settings, if(schema < 3) MediaPreferences.legacy(settings) else decodePreferences(j.getJSONObject("preferences"))),
                JobState.valueOf(j.getString("state")), j.getString("message"),
                if(schema<3 || j.isNull("completedAtMs")) null else j.getLong("completedAtMs").also { require(it>=0) })
        }.also { require(it.map { j -> j.spec.id }.distinct().size == it.size) { "Duplicate job identifiers." } }
    }

    private fun encodePreferences(p: MediaPreferences) = JSONObject()
        .put("legacySnapshot",p.legacySnapshot)
        .put("app",SettingsCodec.encode(p.app))
        .put("preset",SettingsCodec.encode(SettingsDocument(values=p.preset)))
        .put("overrides",SettingsCodec.encode(SettingsDocument(values=p.overrides)))
        .put("presetName",p.presetName ?: JSONObject.NULL)

    private fun decodePreferences(p: JSONObject) = MediaPreferences(
        SettingsCodec.decode(p.getString("app")),
        SettingsCodec.decode(p.getString("preset")).values,
        (if(p.getBoolean("legacySnapshot")) SettingsCodec.decodeLegacyMediaSnapshot(p.getString("overrides")) else SettingsCodec.decode(p.getString("overrides"))).values,
        if(p.isNull("presetName")) null else p.getString("presetName"), p.getBoolean("legacySnapshot"))

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
