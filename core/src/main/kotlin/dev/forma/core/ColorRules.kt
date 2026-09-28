package dev.forma.core

/** Unknown formats are held rather than silently reducing color precision to yuv420p. */
object ColorRules {
    private val eightBit = setOf(
        "yuv420p", "yuv422p", "yuv444p", "yuv440p", "yuv411p", "yuv410p",
        "yuvj420p", "yuvj422p", "yuvj444p", "yuvj440p", "nv12", "nv21",
        "yuva420p", "yuva422p", "yuva444p", "gray", "gray8", "pal8",
        "rgb24", "bgr24", "rgba", "bgra", "argb", "abgr", "gbrp", "gbrap"
    )
    fun needsQualifiedPipeline(pixelFormat: String, transfer: String, rawBits: Int = 0): Boolean =
        transfer in setOf("smpte2084", "arib-std-b67") || rawBits > 8 || pixelFormat !in eightBit
}
