package dev.forma.app.data

import dev.forma.core.*
import dev.forma.core.settings.*
import dev.forma.core.image.*
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

    fun encode(entries: List<QueueEntry>): String = JSONObject().put("schema", 5)
        .put("jobs", JSONArray(entries.map { entry ->
            val common = JSONObject().put("id", entry.spec.id).put("state", entry.state.name).put("message", entry.message)
                .put("preferences", encodePreferences(entry.spec.preferences))
                .put("completedAtMs", entry.completedAtMs ?: JSONObject.NULL)
                .put("delivery", encodeDelivery(entry.delivery))
            when (val tagged = entry.spec) {
                is QueueJobSpec.Image -> {
                    common.put("kind", "image")
                        .put("resolvedFormat", tagged.job.resolvedFormat?.name ?: JSONObject.NULL)
                        .put("document", ImageDocumentCodec.encode(tagged.job.document))
                        .put("info", tagged.job.info?.let(ImageDocumentCodec::encodeInfo) ?: JSONObject.NULL)
                }
                is QueueJobSpec.Av -> encodeAv(common.put("kind", "av"), tagged.job)
            }
        })).toString()

    private fun encodeAv(record: JSONObject, j: JobSpec): JSONObject = record
        .put("targetBytes", j.targetBytes ?: JSONObject.NULL)
        .put("sequence", j.sequence?.let(::encodeSequence) ?: JSONObject.NULL)
        .put("source", encodeSource(j.source)).put("trim", encodeTrim(j.trim)).put("settings", encodeSettings(j.settings))

    private fun encodeSource(source: Source): JSONObject = JSONObject().put("uri", source.uri).put("name", source.name)
        .put("durationMs", source.durationMs).put("width", source.width).put("height", source.height)
        .put("videoTracks", source.videoTracks).put("audioTracks", source.audioTracks).put("hdr", source.hdr).put("bytes", source.bytes)
        .apply { if(source.displayRotationDegrees!=0) put("displayRotationDegrees",source.displayRotationDegrees ?: JSONObject.NULL) }
        .put("audioStreams", JSONArray(source.audioStreams.map { f ->
            JSONObject().put("streamIndex", f.streamIndex).put("sampleRateHz", f.sampleRateHz ?: JSONObject.NULL)
                .put("channels", f.channels ?: JSONObject.NULL).put("layout", f.channelLayout ?: JSONObject.NULL)
                .put("sampleFormat", f.sampleFormat ?: JSONObject.NULL).put("durationUs", f.durationUs)
                .put("codecDelaySamples", f.encoderDelaySamples ?: JSONObject.NULL)
                .put("paddingSamples", f.paddingSamples ?: JSONObject.NULL).put("totalSamples", f.totalSamples ?: JSONObject.NULL)
                .put("codec", f.codec ?: JSONObject.NULL).put("language", f.language ?: JSONObject.NULL)
                .put("title", f.title ?: JSONObject.NULL).put("timelineOffsetUs", f.timelineOffsetUs ?: JSONObject.NULL)
        }))

    private fun encodeTrim(trim: Trim): JSONObject = JSONObject().put("startMs", trim.startMs).put("endMs", trim.endMs ?: JSONObject.NULL)
    private fun encodeSettings(s: Settings): JSONObject = JSONObject().put("container", s.container.name).put("video", s.video.name)
        .put("rateControl", s.rateControl.name).put("crf", s.crf).put("videoKbps", s.videoKbps)
        .put("maxHeight", s.maxHeight).put("fps", s.fps).put("audio", s.audio.name)
        .put("audioKbps", s.audioKbps).put("audioTrack", s.audioTrack).put("stereo", s.stereo)
        .put("denoise", s.denoise).put("deinterlace", s.deinterlace).put("keepMetadata", s.keepMetadata)
        .put("audioEdit", AudioEditCodec.encode(s.audioEdit)).put("effects", ClipEffectsCodec.encode(s.effects))

    private fun encodeSequence(sequence: SequenceSpec): JSONObject = JSONObject().put("transitionMs", sequence.transitionMs)
        .put("canvas", JSONObject().put("width", sequence.canvas.width).put("height", sequence.canvas.height)
            .put("fps", sequence.canvas.fps).put("fit", sequence.canvas.fit.name).put("backgroundRgb", sequence.canvas.backgroundRgb))
        .put("clips", JSONArray(sequence.timeline.clips.map { clip -> JSONObject().put("id", clip.id)
            .put("source", encodeSource(clip.source)).put("trim", encodeTrim(clip.trim)).put("settings", encodeSettings(clip.settings)) }))

    private fun encodeDelivery(delivery: Delivery): JSONObject = JSONObject().apply {
        val destination = when (val chosen = delivery.destination) {
            null -> JSONObject.NULL
            is SaveDestination.FormaLibrary -> JSONObject().put("kind", "forma").put("category", chosen.category.name)
            is SaveDestination.DocumentTree -> JSONObject().put("kind", "tree").put("uri", chosen.uri).put("label", chosen.label)
        }
        put("destination", destination)
        when (val receipt = delivery.receipt) {
            DeliveryReceipt.PrivateLegacy -> put("state", "private")
            DeliveryReceipt.Waiting -> put("state", "waiting")
            is DeliveryReceipt.Copying -> { put("state", "copying"); put("intentName", receipt.intentName); put("uri", receipt.uri ?: JSONObject.NULL) }
            is DeliveryReceipt.Saved -> { put("state", "saved"); put("uri", receipt.uri); put("displayName", receipt.displayName)
                put("bytes", receipt.bytes); put("digest", receipt.digest) }
            is DeliveryReceipt.Failed -> { put("state", "failed"); put("message", receipt.message); put("uri", receipt.uri ?: JSONObject.NULL) }
        }
    }

    private fun decodeDelivery(record: JSONObject): Delivery {
        val destination = if (record.isNull("destination")) null else record.getJSONObject("destination").let { d ->
            when (string(d,"kind")) {
                "forma" -> { fields(d,setOf("kind","category")); SaveDestination.FormaLibrary(MediaCategory.valueOf(string(d,"category"))) }
                "tree" -> { fields(d,setOf("kind","uri","label")); SaveDestination.DocumentTree(string(d,"uri"),string(d,"label")) }
                else -> throw IllegalArgumentException("Unsupported saved destination.")
            }
        }
        fun uri(): String? = if (record.isNull("uri")) null else string(record,"uri").also(::requireContentUri)
        val receipt = when (string(record,"state")) {
            "private" -> { fields(record,setOf("destination","state")); DeliveryReceipt.PrivateLegacy }
            "waiting" -> { fields(record,setOf("destination","state")); DeliveryReceipt.Waiting }
            "copying" -> { fields(record,setOf("destination","state","intentName","uri"));
                DeliveryReceipt.Copying(string(record,"intentName").also(::requireDisplayName),uri()) }
            "saved" -> { fields(record,setOf("destination","state","uri","displayName","bytes","digest"));
                val bytes=integer(record,"bytes");require(bytes>=0) { "Invalid saved byte count." }
                val digest=string(record,"digest");require(Regex("[0-9a-f]{64}").matches(digest)) { "Invalid saved digest." }
                DeliveryReceipt.Saved(uri() ?: throw IllegalArgumentException("Missing saved URI."),
                    string(record,"displayName").also(::requireDisplayName),bytes,digest) }
            "failed" -> { fields(record,setOf("destination","state","message","uri"));
                DeliveryReceipt.Failed(string(record,"message").also { require(it.length in 1..500) },uri()) }
            else -> throw IllegalArgumentException("Unsupported saved delivery state.")
        }
        return Delivery(destination,receipt)
    }

    private fun requireContentUri(uri:String) { require(uri.startsWith("content://") && uri.length<=4096 && uri.none(Char::isISOControl)) { "Invalid saved URI." } }
    private fun requireDisplayName(name:String) { require(name.isNotBlank() && name.length<=255 && name.none(Char::isISOControl) && '/' !in name && '\\' !in name) { "Invalid saved filename." } }

    private enum class Family { LEGACY1, AUDIO2, RUNTIME2, EFFECTS2, TAGGED3, SETTINGS3, MOVIE3, MODERN4, MODERN5 }

    /** A historical envelope has one recognized family; never infer missing intent record by record. */
    private fun family(record: JSONObject, schema: Int): Family = when(schema) {
        1 -> Family.LEGACY1
        2 -> {
            val settings=record.getJSONObject("settings")
            val signatures=listOf(settings.has("audioEdit"),record.has("targetBytes"),settings.has("effects"))
            require(signatures.count { it } == 1) { "Unrecognized or hybrid schema-2 intent; the original queue is preserved." }
            when(signatures.indexOf(true)) { 0 -> Family.AUDIO2; 1 -> Family.RUNTIME2; else -> Family.EFFECTS2 }
        }
        3 -> when {
            record.has("kind") -> Family.TAGGED3
            record.has("preferences") || record.has("completedAtMs") -> Family.SETTINGS3
            record.has("sequence") || record.has("targetBytes") || record.getJSONObject("settings").has("effects") -> Family.MOVIE3
            else -> throw IllegalArgumentException("Unrecognized schema-3 envelope; the original queue is preserved.")
        }
        4 -> Family.MODERN4
        else -> Family.MODERN5
    }

    fun decode(text: String): List<QueueEntry> {
        val root=JSONObject(text)
        fields(root,setOf("schema","jobs"))
        val schema=int(root,"schema")
        require(schema in 1..5) { "Unsupported queue schema. The original file has been preserved." }
        val jobs=root.getJSONArray("jobs")
        require(jobs.length() <= 200) { "Too many saved jobs." }
        val families=(0 until jobs.length()).map { family(jobs.getJSONObject(it),schema) }
        if(schema in 2..3) require(families.distinct().size <= 1) {
            "Mixed historical queue envelopes are unsupported; the original queue is preserved."
        }
        return (0 until jobs.length()).map { index ->
            val record=jobs.getJSONObject(index)
            val wire=families[index]
            if(wire in setOf(Family.MODERN4,Family.MODERN5,Family.TAGGED3)) decodeTagged(record,wire) else decodeAv(record,wire)
        }.also { require(it.map { entry -> entry.spec.id }.distinct().size == it.size) { "Duplicate job identifiers." } }
    }

    private fun decodeTagged(record: JSONObject, family: Family): QueueEntry = when(string(record,"kind")) {
        "av" -> decodeAv(record,family)
        "image" -> decodeImage(record,family)
        else -> throw IllegalArgumentException("Unsupported queue kind; the original file is preserved.")
    }

    private fun identifier(record: JSONObject): String = string(record,"id").also {
        require(UUID.fromString(it).toString() == it) { "Invalid job identifier." }
    }
    private fun completionTime(record: JSONObject, preferences: Boolean): Long? =
        if(!preferences || record.isNull("completedAtMs")) null else integer(record,"completedAtMs").also {
            require(it >= 0) { "Invalid saved completion timestamp." }
        }

    private fun decodeImage(record: JSONObject, family: Family): QueueEntry {
        val preferences=family in setOf(Family.MODERN4,Family.MODERN5)
        val base=setOf("kind","id","state","message","resolvedFormat","document","info")
        fields(record,base + (if(preferences) setOf("preferences","completedAtMs") else emptySet()) +
            (if(family==Family.MODERN5) setOf("delivery") else emptySet()))
        val job=ImageJobSpec(identifier(record),ImageDocumentCodec.decode(record.getJSONObject("document")),
            if(record.isNull("info")) null else ImageDocumentCodec.decodeInfo(record.getJSONObject("info")),
            if(record.isNull("resolvedFormat")) null else ImageFormat.valueOf(string(record,"resolvedFormat")),
            if(preferences) decodePreferences(record.getJSONObject("preferences")) else MediaPreferences.legacy(Settings()))
        return QueueEntry(QueueJobSpec.Image(job),JobState.valueOf(string(record,"state")),string(record,"message"),
            completionTime(record,preferences),if(family==Family.MODERN5) decodeDelivery(record.getJSONObject("delivery")) else Delivery.LEGACY)
    }

    private fun hasAudio(family: Family) = family in setOf(Family.AUDIO2,Family.TAGGED3,Family.SETTINGS3,Family.MODERN4,Family.MODERN5)
    private fun hasEffects(family: Family) = family in setOf(Family.EFFECTS2,Family.MOVIE3,Family.MODERN4,Family.MODERN5)
    private fun fullAudioWriter(family: Family) = family in setOf(Family.TAGGED3,Family.SETTINGS3,Family.MODERN4,Family.MODERN5)

    private fun decodeAv(record: JSONObject, family: Family): QueueEntry {
        val preferences=family in setOf(Family.MODERN4,Family.MODERN5,Family.SETTINGS3)
        val tagged=family in setOf(Family.MODERN4,Family.MODERN5,Family.TAGGED3)
        val hasTarget=family in setOf(Family.MODERN4,Family.MODERN5,Family.RUNTIME2,Family.MOVIE3)
        val hasSequence=family in setOf(Family.MODERN4,Family.MODERN5,Family.MOVIE3)
        fields(record,setOf("id","state","message","source","trim","settings") +
            (if(tagged) setOf("kind") else emptySet()) + (if(preferences) setOf("preferences","completedAtMs") else emptySet()) +
            (if(hasTarget) setOf("targetBytes") else emptySet()) + (if(hasSequence) setOf("sequence") else emptySet()) +
            (if(family==Family.MODERN5) setOf("delivery") else emptySet()))
        val settings=decodeSettings(record.getJSONObject("settings"),family)
        val target=if(!hasTarget || record.isNull("targetBytes")) null else integer(record,"targetBytes").also(UploadFit::validateTarget)
        val sequence=if(!hasSequence || record.isNull("sequence")) null else decodeSequence(record.getJSONObject("sequence"),family)
        val job=JobSpec(identifier(record),decodeSource(record.getJSONObject("source"),family),decodeTrim(record.getJSONObject("trim")),settings,
            preferences=if(preferences) decodePreferences(record.getJSONObject("preferences")) else MediaPreferences.legacy(settings),
            targetBytes=target,sequence=sequence)
        val endMs=job.trim.endMs
        require(job.source.durationMs >= 0 && job.trim.startMs >= 0 &&
            (endMs == null || endMs > job.trim.startMs) &&
            (job.source.durationMs == 0L || (job.trim.startMs < job.source.durationMs && (job.trim.endMs ?: job.source.durationMs) <= job.source.durationMs))) {
            "Invalid saved source range; the original queue is preserved."
        }
        return QueueEntry(job,JobState.valueOf(string(record,"state")),string(record,"message"),completionTime(record,preferences),
            if(family==Family.MODERN5) decodeDelivery(record.getJSONObject("delivery")) else Delivery.LEGACY)
    }

    private fun decodeSource(source: JSONObject, family: Family): Source {
        val base=setOf("uri","name","durationMs","width","height","videoTracks","audioTracks","hdr","bytes")
        val supportsFacts=hasAudio(family) || family == Family.LEGACY1
        val audio=if(supportsFacts) base + "audioStreams" else base
        val rotation=if(family==Family.MODERN5) setOf("displayRotationDegrees") else emptySet()
        fields(source,audio+rotation,if(fullAudioWriter(family)) base + "audioStreams" else base)
        val displayRotation=if(!source.has("displayRotationDegrees")) 0 else if(source.isNull("displayRotationDegrees")) null
            else int(source,"displayRotationDegrees").also { require(it in setOf(0,90,180,270)) { "Unsupported saved display rotation." } }
        return Source(string(source,"uri"),string(source,"name"),integer(source,"durationMs"),int(source,"width"),int(source,"height"),
            int(source,"videoTracks"),int(source,"audioTracks"),boolean(source,"hdr"),integer(source,"bytes"),decodeStreams(source,fullAudioWriter(family)),displayRotationDegrees=displayRotation)
    }
    private fun decodeTrim(trim: JSONObject): Trim {
        fields(trim,setOf("startMs","endMs"))
        return Trim(integer(trim,"startMs"),if(trim.isNull("endMs")) null else integer(trim,"endMs"))
    }
    private fun decodeSettings(record: JSONObject, family: Family): Settings {
        val base=setOf("container","video","rateControl","crf","videoKbps","maxHeight","fps","audio","audioKbps","audioTrack","stereo","denoise","deinterlace","keepMetadata")
        fields(record,base + (if(hasAudio(family)) setOf("audioEdit") else emptySet()) + (if(hasEffects(family)) setOf("effects") else emptySet()))
        val audio=if(hasAudio(family)) record.getJSONObject("audioEdit").also {
            validateAudioWire(it,fullAudioWriter(family));validateSavedAudioNodeIntent(it)
        }.let(AudioEditCodec::decode) else AudioEdit()
        val effects=if(hasEffects(family)) record.getJSONObject("effects").also {
            fields(it,ClipEffectsCodec.encode(ClipEffects()).keys().asSequence().toSet())
        }.let(ClipEffectsCodec::decode) else ClipEffects()
        return Settings(Container.valueOf(string(record,"container")),VideoEncoder.valueOf(string(record,"video")),
            RateControl.valueOf(string(record,"rateControl")),int(record,"crf"),int(record,"videoKbps"),int(record,"maxHeight"),
            int(record,"fps"),AudioEncoder.valueOf(string(record,"audio")),int(record,"audioKbps"),int(record,"audioTrack"),
            boolean(record,"stereo"),boolean(record,"denoise"),boolean(record,"deinterlace"),boolean(record,"keepMetadata"),audio,effects)
    }
    private fun decodeSequence(record: JSONObject, family: Family): SequenceSpec {
        fields(record,setOf("transitionMs","canvas","clips"))
        val canvas=record.getJSONObject("canvas")
        fields(canvas,setOf("width","height","fps","fit","backgroundRgb"))
        val clips=record.getJSONArray("clips")
        require(clips.length() in 1..SequencePlanner.MAX_RENDER_CLIPS) { "Unsupported saved movie clip count." }
        return SequenceSpec(EditTimeline((0 until clips.length()).map { index ->
            val clip=clips.getJSONObject(index)
            fields(clip,setOf("id","source","trim","settings"))
            TimelineClip(string(clip,"id"),decodeSource(clip.getJSONObject("source"),family),
                decodeTrim(clip.getJSONObject("trim")),decodeSettings(clip.getJSONObject("settings"),family))
        }),CanvasSpec(int(canvas,"width"),int(canvas,"height"),int(canvas,"fps"),
            CanvasFit.valueOf(string(canvas,"fit")),string(canvas,"backgroundRgb")),integer(record,"transitionMs"))
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

    private fun decodeStreams(source: JSONObject,requiredWriterFields:Boolean): List<SourceAudioFacts> {
        if(!source.has("audioStreams")) return emptyList()
        val streams = source.getJSONArray("audioStreams")
        fun JSONObject.text(key: String): String? = if (isNull(key)) null else string(this,key)
        fun JSONObject.long(key: String): Long? = if (isNull(key)) null else integer(this,key)
        return (0 until streams.length()).map { i ->
            val f = streams.getJSONObject(i)
            fields(f,setOf("streamIndex","sampleRateHz","channels","layout","sampleFormat","durationUs","codecDelaySamples","paddingSamples","totalSamples","codec","language","title","timelineOffsetUs"),
                setOf("streamIndex","sampleRateHz","channels","layout","sampleFormat","durationUs","codecDelaySamples","paddingSamples","totalSamples","codec","language","title"))
            if(requiredWriterFields) require(f.has("timelineOffsetUs")) { "Saved source offset intent is missing." }
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
                val nodeVersion=int(node,"version");string(node,"id");val type=string(node,"type");boolean(node,"enabled")
                if(requiredWriterFields && nodeVersion==1 &&
                    node.keys().asSequence().all { it in setOf("id","type","version","enabled","parameters") }) {
                    val expected=when(type) {
                        "gain" -> setOf("gainDb","muted")
                        "fades" -> setOf("fadeInUs","fadeOutUs")
                        "eq" -> setOf("bands")
                        "compressor" -> setOf("thresholdDb","ratio","kneeDb","attackMs","releaseMs","makeupDb")
                        "limiter" -> setOf("ceilingDb","attackMs","releaseMs")
                        else -> emptySet()
                    }
                    if(expected.isNotEmpty()) {
                        val parameters=node.getJSONObject("parameters")
                        val actual=parameters.keys().asSequence().toSet()
                        if(actual.all {it in expected}) require(actual.containsAll(expected)) {
                            "Saved audio effect parameters are missing; the original queue is preserved."
                        }
                        if(type=="eq" && actual==expected) {
                            val bands=parameters.getJSONArray("bands")
                            val required=setOf("id","type","frequencyHz","gainDb","q","slopeDbPerOctave","enabled")
                            for(index in 0 until bands.length()) {
                                val fields=bands.getJSONObject(index).keys().asSequence().toSet()
                                if(fields.all {it in required}) require(fields.containsAll(required)) {
                                    "Saved EQ band intent is missing; the original queue is preserved."
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
