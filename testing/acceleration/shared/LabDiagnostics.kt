package dev.forma.accelerationlab

/** Validate the requested experiment against its session-scoped native diagnostics. */
object LabDiagnostics {
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
