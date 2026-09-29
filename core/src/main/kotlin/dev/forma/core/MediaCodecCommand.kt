package dev.forma.core

/** Bind a component to an already validated CPU-filter/buffer-input export plan. */
object MediaCodecCommand {
    fun bind(arguments: List<String>, request: EncodeRequest, decision: EncodeDecision): List<String> {
        require(arguments.isNotEmpty() && arguments.last().isNotBlank())
        val conflicts = setOf("-crf", "-preset", "-hwaccel", "-hwaccel_output_format", "-codec_name", "-bitrate_mode", "-bf")
        require(arguments.none { it.substringBefore(':') in conflicts }) { "Conflicting software or device options in hardware plan." }
        require(arguments.count { it == "-c:v" } == 1) { "Exactly one video encoder is required." }
        val replace = setOf("-c:v", "-b:v", "-pix_fmt")
        val result = mutableListOf<String>()
        var index = 0
        while (index < arguments.lastIndex) {
            val value = arguments[index]
            if (value in replace) {
                require(index + 1 < arguments.lastIndex) { "Missing encoder option value." }
                index += 2
            } else { result.add(value); index++ }
        }
        result.addAll(decision.videoOptions(request))
        result.add(arguments.last())
        return result
    }

    /** Mirrors the existing scale=-2:evenHeight filter; never substitute this for display-matrix handling. */
    fun dimensions(width: Int, height: Int, maxHeight: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxHeight >= 0)
        val outHeight = (if (maxHeight == 0) height else minOf(height, maxHeight)) / 2 * 2
        require(outHeight >= 2)
        val outWidth = kotlin.math.floor(width.toDouble() * outHeight / height / 2 + 0.5).toInt() * 2
        require(outWidth >= 2)
        return outWidth to outHeight
    }
}
