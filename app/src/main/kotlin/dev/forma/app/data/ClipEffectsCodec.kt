package dev.forma.app.data

import dev.forma.core.*
import org.json.JSONObject

/** Version-2 queue edits and future project files share this typed wire contract. */
object ClipEffectsCodec {
    fun encode(e: ClipEffects): JSONObject = JSONObject()
        .put("crop", e.crop?.let { JSONObject().put("x", it.x).put("y", it.y).put("width", it.width).put("height", it.height) } ?: JSONObject.NULL)
        .put("rotation", e.rotation.name).put("flipHorizontal", e.flipHorizontal).put("flipVertical", e.flipVertical)
        .put("speedPercent", e.speedPercent).put("brightnessPercent", e.brightnessPercent)
        .put("contrastPercent", e.contrastPercent).put("saturationPercent", e.saturationPercent).put("gammaPercent", e.gammaPercent)
        .put("blurSigma", e.blurSigma).put("sharpen", e.sharpen).put("fadeInMs", e.fadeInMs).put("fadeOutMs", e.fadeOutMs)
        .put("volumePercent", e.volumePercent).put("audioFadeInMs", e.audioFadeInMs).put("audioFadeOutMs", e.audioFadeOutMs)
        .put("normalizeAudio", e.normalizeAudio)

    fun decode(j: JSONObject): ClipEffects {
        val defaults = ClipEffects()
        val known = encode(defaults).keys().asSequence().toSet()
        require(j.keys().asSequence().all { it in known }) { "Unknown clip-edit field; refusing to discard an edit." }
        fun number(name: String, default: Long): Long {
            if (!j.has(name)) return default
            val value = j.get(name)
            require(value is Int || value is Long) { "$name must be an integer." }
            return (value as Number).toLong()
        }
        fun int(name: String, default: Int): Int = number(name, default.toLong()).let {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$name is out of range." }; it.toInt()
        }
        fun bool(name: String): Boolean {
            if (!j.has(name)) return false
            val value = j.get(name)
            require(value is Boolean) { "$name must be a boolean." }
            return value
        }
        val crop = if (!j.has("crop") || j.isNull("crop")) null else {
            val c = j.get("crop")
            require(c is JSONObject) { "crop must be an object or null." }
            require(c.keys().asSequence().toSet() == setOf("x", "y", "width", "height")) { "Crop requires x, y, width and height only." }
            fun pixel(name: String): Int {
                val value = c.get(name)
                require(value is Int || value is Long) { "Crop $name must be an integer." }
                val long = (value as Number).toLong()
                require(long in 0..Int.MAX_VALUE.toLong()) { "Crop $name is out of range." }
                return long.toInt()
            }
            CropRect(pixel("x"), pixel("y"), pixel("width"), pixel("height"))
        }
        val rotation = if (j.has("rotation")) {
            val value = j.get("rotation")
            require(value is String) { "rotation must be a name." }
            QuarterTurn.valueOf(value)
        } else QuarterTurn.NONE
        return ClipEffects(crop, rotation, bool("flipHorizontal"), bool("flipVertical"),
            int("speedPercent", 100), int("brightnessPercent", 0), int("contrastPercent", 100),
            int("saturationPercent", 100), int("gammaPercent", 100), int("blurSigma", 0), bool("sharpen"),
            number("fadeInMs", 0), number("fadeOutMs", 0), int("volumePercent", 100),
            number("audioFadeInMs", 0), number("audioFadeOutMs", 0), bool("normalizeAudio"))
    }
}
