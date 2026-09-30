package dev.forma.core

/** Unknown formats are held rather than silently reducing color precision to yuv420p. */
object ColorRules {
    private val eightBit = setOf(
        "yuv420p", "yuv422p", "yuv444p", "yuv440p", "yuv411p", "yuv410p",
        "yuvj420p", "yuvj422p", "yuvj444p", "yuvj440p", "nv12", "nv21",
        "yuva420p", "yuva422p", "yuva444p", "gray", "gray8", "pal8",
        "rgb24", "bgr24", "rgba", "bgra", "argb", "abgr", "gbrp", "gbrap"
    )
    fun needsQualifiedPipeline(pixelFormat: String, transfer: String, rawBits: Int = 0,
                               primaries: String = "", matrix: String = "", hdrMetadata: Boolean = false): Boolean {
        // Untagged Main 10 is treated as SDR only when there is no contrary color evidence.
        // Explicit wide-gamut/HDR tags and other high-depth layouts remain blocked.
        val sdrMain10 = pixelFormat == "yuv420p10le" && rawBits in 0..10 &&
            transfer in setOf("", "bt709") && primaries in setOf("", "bt709") &&
            matrix in setOf("", "bt709") && !hdrMetadata
        return hdrMetadata || transfer in setOf("smpte2084", "arib-std-b67") ||
            (!sdrMain10 && (rawBits > 8 || pixelFormat !in eightBit))
    }

    /** Output qualification remains strict even when a 10-bit source may enter the SDR path. */
    fun needsQualifiedOutputPipeline(pixelFormat: String, transfer: String, rawBits: Int = 0): Boolean =
        transfer in setOf("smpte2084", "arib-std-b67") || rawBits > 8 || pixelFormat !in eightBit
}
