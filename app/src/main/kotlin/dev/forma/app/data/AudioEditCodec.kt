package dev.forma.app.data

import dev.forma.core.audio.*
import org.json.JSONArray
import org.json.JSONObject

/** Forward data stays opaque and unavailable, rather than disappearing during a queue save. */
internal object AudioEditCodec {
    fun encode(edit: AudioEdit): JSONObject {
        edit.preservedJson?.let { return JSONObject(it) }
        val output = edit.output
        val normalization = output.normalization
        return JSONObject().put("schema", edit.schemaVersion).put("nodes", JSONArray(edit.nodes.map(::encodeNode)))
            .put("rate", JSONObject().put("numerator", edit.rate.numerator).put("denominator", edit.rate.denominator))
            .put("output", JSONObject().put("channels", output.channels?.name ?: JSONObject.NULL)
                .put("sampleRateHz", output.sampleRateHz ?: JSONObject.NULL)
                .put("normalization", JSONObject().put("mode", normalization.mode.name)
                    .put("peakDb", normalization.peakDb).put("integratedLufs", normalization.integratedLufs)
                    .put("truePeakDb", normalization.truePeakDb).put("preserveDynamics", normalization.preserveDynamics)))
    }

    private fun encodeNode(node: AudioEffectNode): JSONObject {
        node.preservedJson?.let { return JSONObject(it).put("enabled", node.enabled) }
        val p = when (val value = node.parameters) {
            is GainParameters -> JSONObject().put("gainDb", value.gainDb).put("muted", value.muted)
            is FadeParameters -> JSONObject().put("fadeInUs", value.fadeInUs).put("fadeOutUs", value.fadeOutUs)
            is EqParameters -> JSONObject().put("bands", JSONArray(value.bands.map { band ->
                JSONObject().put("id", band.id).put("type", band.type.name).put("frequencyHz", band.frequencyHz)
                    .put("gainDb", band.gainDb).put("q", band.q).put("slopeDbPerOctave", band.slopeDbPerOctave).put("enabled", band.enabled)
            }))
            is CompressorParameters -> JSONObject().put("thresholdDb", value.thresholdDb).put("ratio", value.ratio)
                .put("kneeDb", value.kneeDb).put("attackMs", value.attackMs).put("releaseMs", value.releaseMs).put("makeupDb", value.makeupDb)
            is LimiterParameters -> JSONObject().put("ceilingDb", value.ceilingDb).put("attackMs", value.attackMs).put("releaseMs", value.releaseMs)
            is UnsupportedParameters -> JSONObject(value.json)
        }
        return JSONObject().put("id", node.id).put("type", node.type).put("version", node.version)
            .put("enabled", node.enabled).put("parameters", p)
    }

    fun decode(json: JSONObject): AudioEdit {
        val version = json.getInt("schema")
        fun opaque() = AudioEdit(schemaVersion = version, preservedJson = json.toString())
        if (version != 1 || hasUnknown(json, setOf("schema", "nodes", "output", "rate"))) return opaque()
        return try {
            val rate = json.optJSONObject("rate") ?: JSONObject()
            if (hasUnknown(rate, setOf("numerator", "denominator"))) return opaque()
            val output = json.optJSONObject("output") ?: JSONObject()
            if (hasUnknown(output, setOf("channels", "sampleRateHz", "normalization"))) return opaque()
            val normalization = output.optJSONObject("normalization") ?: JSONObject()
            if (hasUnknown(normalization, setOf("mode", "peakDb", "integratedLufs", "truePeakDb", "preserveDynamics"))) return opaque()
            val nodes = json.getJSONArray("nodes")
            AudioEdit(nodes = (0 until nodes.length()).map { decodeNode(nodes.getJSONObject(it)) },
                output = AudioOutputPolicy(if (output.isNull("channels")) null else ChannelMode.valueOf(output.getString("channels")),
                    if (output.isNull("sampleRateHz")) null else output.getInt("sampleRateHz"),
                    NormalizationPolicy(NormalizationMode.valueOf(normalization.optString("mode", "OFF")),
                        normalization.optDouble("peakDb", -1.0), normalization.optDouble("integratedLufs", -16.0),
                        normalization.optDouble("truePeakDb", -1.5), normalization.optBoolean("preserveDynamics", true))),
                rate = AudioRate(rate.optInt("numerator", 1), rate.optInt("denominator", 1)))
        } catch (_: IllegalArgumentException) { opaque() }
    }

    private fun hasUnknown(json: JSONObject, known: Set<String>) = json.keys().asSequence().any { it !in known }
    private fun decodeNode(json: JSONObject): AudioEffectNode {
        val id = json.getString("id")
        val type = json.getString("type")
        val version = json.getInt("version")
        val enabled = json.getBoolean("enabled")
        fun opaque() = AudioEffectNode(id, type, version, enabled,
            UnsupportedParameters(json.optJSONObject("parameters")?.toString() ?: "{}"), json.toString())
        if (version != 1 || hasUnknown(json, setOf("id", "type", "version", "enabled", "parameters"))) return opaque()
        val p = json.optJSONObject("parameters") ?: return opaque()
        val keys = when (type) {
            "gain" -> setOf("gainDb", "muted"); "fades" -> setOf("fadeInUs", "fadeOutUs")
            "eq" -> setOf("bands"); "compressor" -> setOf("thresholdDb", "ratio", "kneeDb", "attackMs", "releaseMs", "makeupDb")
            "limiter" -> setOf("ceilingDb", "attackMs", "releaseMs"); else -> return opaque()
        }
        if (hasUnknown(p, keys)) return opaque()
        val parameters = try {
            when (type) {
                "gain" -> GainParameters(p.optDouble("gainDb", 0.0), p.optBoolean("muted", false))
                "fades" -> FadeParameters(p.optLong("fadeInUs", 0), p.optLong("fadeOutUs", 0))
                "eq" -> {
                    val bands = p.getJSONArray("bands")
                    EqParameters((0 until bands.length()).map { i ->
                        val band = bands.getJSONObject(i)
                        if (hasUnknown(band, setOf("id", "type", "frequencyHz", "gainDb", "q", "slopeDbPerOctave", "enabled"))) return opaque()
                        EqBand(band.getString("id"), EqType.valueOf(band.optString("type", "BELL")),
                            band.optDouble("frequencyHz", 1000.0), band.optDouble("gainDb", 0.0),
                            band.optDouble("q", 0.707), band.optInt("slopeDbPerOctave", 12), band.optBoolean("enabled", true))
                    })
                }
                "compressor" -> CompressorParameters(p.optDouble("thresholdDb", -18.0), p.optDouble("ratio", 2.0),
                    p.optDouble("kneeDb", 6.0), p.optDouble("attackMs", 20.0), p.optDouble("releaseMs", 250.0), p.optDouble("makeupDb", 0.0))
                else -> LimiterParameters(p.optDouble("ceilingDb", -1.0), p.optDouble("attackMs", 5.0), p.optDouble("releaseMs", 50.0))
            }
        } catch (_: IllegalArgumentException) { return opaque() }
        return AudioEffectNode(id, type, version, enabled, parameters)
    }
}
