package dev.forma.app.data

import org.json.JSONObject

/** Queue snapshots are complete writer records; sparse external recipes use AudioEditCodec separately. */
internal fun validateSavedAudioNodeIntent(edit:JSONObject) {
    fun keys(value:JSONObject)=value.keys().asSequence().toSet()
    fun integer(value:JSONObject,key:String):Long {
        val raw=value.get(key)
        require(raw is Int || raw is Long) { "Saved $key must be an integer." }
        return (raw as Number).toLong().also {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Saved $key is out of range." }
        }
    }
    val version=integer(edit,"schema")
    if(version!=1L || keys(edit).any { it !in setOf("schema","nodes","rate","output") }) return
    val knownNode=setOf("id","type","version","enabled","parameters")
    val knownBand=setOf("id","type","frequencyHz","gainDb","q","slopeDbPerOctave","enabled")
    val nodes=edit.getJSONArray("nodes")
    for(index in 0 until nodes.length()) {
        val node=nodes.getJSONObject(index)
        val nodeVersion=integer(node,"version")
        require(node.get("id") is String && node.get("type") is String && node.get("enabled") is Boolean) {
            "Saved audio node identity or enabled intent is missing."
        }
        if(nodeVersion!=1L || keys(node).any { it !in knownNode }) continue
        val required=when(node.getString("type")) {
            "gain" -> setOf("gainDb","muted")
            "fades" -> setOf("fadeInUs","fadeOutUs")
            "compressor" -> setOf("thresholdDb","ratio","kneeDb","attackMs","releaseMs","makeupDb")
            "limiter" -> setOf("ceilingDb","attackMs","releaseMs")
            "eq" -> setOf("bands")
            else -> continue
        }
        val parameters=node.getJSONObject("parameters")
        val present=keys(parameters)
        // Any future field makes the node opaque in AudioEditCodec; retain that payload verbatim.
        if(present.any { it !in required }) continue
        require(present.containsAll(required)) { "Saved ${node.getString("type")} parameter intent is missing." }
        if(node.getString("type")=="eq") {
            val bands=parameters.getJSONArray("bands")
            val fields=(0 until bands.length()).map { keys(bands.getJSONObject(it)) }
            if(fields.any { field -> field.any { it !in knownBand } }) continue
            require(fields.all { it.containsAll(knownBand) }) { "Saved EQ band intent is missing." }
        }
    }
}
