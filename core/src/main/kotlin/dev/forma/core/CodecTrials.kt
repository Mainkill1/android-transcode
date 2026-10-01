package dev.forma.core

/** A trial is not a support claim. Keep output intent fixed while changing device configuration. */
object CodecTrials {
    const val MAX_COMPONENTS = 3
    const val MAX_DEVICE_ATTEMPTS = MAX_COMPONENTS * 4

    fun plan(request: EncodeRequest, mode: AccelerationMode, compiledEncoders: Set<String>,
             candidates: List<CodecCandidate>): List<EncodeDecision> {
        fun software(reason: String): List<EncodeDecision> =
            if (mode != AccelerationMode.HARDWARE_REQUIRED && request.format.software in compiledEncoders)
                listOf(EncodeDecision(EncodeBackend.SOFTWARE, request.format.software,
                    reason = reason, configuration = request)) else emptyList()
        // These are media-semantics gates, not unreliable device capability gates.
        if (request.hdr || request.bitDepth != 8) return emptyList()
        if (mode == AccelerationMode.SOFTWARE_ONLY) return software("Software explicitly selected.")
        if (request.constantQuality) return software("Preserve the requested constant-quality mode.")
        if (request.rotationDegrees % 360 != 0) return software("Device display-matrix handling is not implemented.")
        if (request.format.device !in compiledEncoders) return software("Native device wrapper is not compiled.")
        val components = candidates.filter {
            it.request == request && it.format == request.format && it.encoder && it.hardware != Support.NO
        }.distinctBy { it.name }.sortedWith(
            compareBy<CodecCandidate> { it.hardware != Support.YES }.thenBy { it.configuration != Support.YES }.then(CodecRanking.preference)
        ).take(MAX_COMPONENTS) // Stable ordering keeps Android's preference when evidence is tied.
        return buildList {
            for (rate in DeviceBitrateMode.values()) {
                for (alternate in listOf(false, true)) {
                    for (candidate in components) {
                        val preferred = candidate.bufferFormat ?: BufferFormat.NV12
                        val buffer = if (!alternate) preferred else when (preferred) {
                            BufferFormat.NV12 -> BufferFormat.YUV420P
                            BufferFormat.YUV420P -> BufferFormat.NV12
                        }
                        add(EncodeDecision(EncodeBackend.MEDIACODEC, request.format.device,
                            candidate.name, buffer,
                            "Runtime trial; advertised support=${candidate.configuration}, hardware=${candidate.hardware}.",
                            configuration = request, bitrateMode = rate, hardwareSupport = candidate.hardware))
                    }
                }
            }
            addAll(software("Automatic device attempts exhausted or unavailable; preserve the same codec and job settings in software."))
        }
    }
}

/**
 * FFmpeg exposes a return code, not MediaCodec.CodecException. Only recognize
 * specific encoder-local initialization messages. Unrecognized errors stay fatal.
 * Observe session-scoped callbacks, not a truncated log tail or global logcat.
 */
class CodecFailureEvidence(encoder: String) {
    private val logger = Regex("^\\[(?:vost#[0-9]+:[0-9]+/)?${Regex.escape(encoder)}\\s+@[^]\\r\\n]*]\\s+(.*)$")
    private var codecInitialization = false
    private var io = false
    private var invalidInput = false
    private var ambiguous = false

    @Synchronized fun observe(message: String) {
        for (line in message.lineSequence()) {
            val lower = line.lowercase(java.util.Locale.ROOT)
            if (listOf("no space left on device", "permission denied", "read-only file system",
                    "input/output error", "too many open files").any { it in lower }) io = true
            if (listOf("invalid data found when processing input", "error while decoding",
                    "error submitting packet to decoder").any { it in lower }) invalidInput = true
            if (listOf("cannot allocate memory", "out of memory", "failed to configure output pad",
                    "error reinitializing filters").any { it in lower }) ambiguous = true
            val local = logger.matchEntire(line.trimEnd())?.groupValues?.get(1) ?: continue
            if (local.startsWith("MediaCodec configure failed,") ||
                local.startsWith("MediaCodec failed to start,") ||
                local.startsWith("Failed to create encoder for type ")) codecInitialization = true
        }
    }

    @Synchronized fun failure(encodedFrames: Boolean): FailureKind = when {
        io -> FailureKind.IO
        invalidInput -> FailureKind.INVALID_INPUT
        encodedFrames || ambiguous -> FailureKind.UNKNOWN
        codecInitialization -> FailureKind.CODEC_INITIALIZATION
        else -> FailureKind.UNKNOWN
    }
}
