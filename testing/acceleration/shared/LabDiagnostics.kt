package dev.forma.accelerationlab

/** Validate the requested experiment against its session-scoped native diagnostics. */
object LabDiagnostics {
    fun decoderComponent(log: String): String {
        // Pinned FFmpeg 9 identifies the successfully started decoder after selection.
        val started = Regex("(?m)^\\[h264_mediacodec[ \\t]+@[^]\\r\\n]*][ \\t]+MediaCodec started successfully: codec = ([A-Za-z0-9._-]+), ret = 0(?:[ \\t]|$)")
            .findAll(log).map { it.groupValues[1] }.toSet()
        check(started.size == 1) { "No unambiguous actual decoder component was observed; do not claim hardware decode." }
        return started.single()
    }
    fun validate(mode: LabMode?, encoder: String, log: String) {
        check("has not been used for any stream" !in log) { "FFmpeg ignored a requested option; this is not a valid experiment." }
        if (mode == LabMode.NDK_ASYNC) {
            val fallback = Regex("(?m)^\\[(?:vost#[0-9]+:[0-9]+/)?${Regex.escape(encoder)}[ \\t]+@[^]\\r\\n]*][ \\t]+Try MediaCodec async mode failed,[^\\r\\n]*switch to sync mode")
            check(!fallback.containsMatchIn(log)) {
                "The requested NDK async experiment fell back to synchronous encoding; it is not an async result."
            }
        }
    }
}
