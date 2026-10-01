package dev.forma.core

/** Manufacturer estimate for this component/size on this device, never an application benchmark. */
data class CodecPerformanceHint(val lowerFps: Double, val upperFps: Double) {
    init { require(valid(lowerFps, upperFps)) { "Invalid achievable frame-rate range." } }

    companion object {
        fun from(lowerFps: Double, upperFps: Double): CodecPerformanceHint? =
            if (valid(lowerFps, upperFps)) CodecPerformanceHint(lowerFps, upperFps) else null

        private fun valid(lower: Double, upper: Double): Boolean =
            lower.isFinite() && upper.isFinite() && lower > 0.0 && upper >= lower
    }
}

/**
 * A provisional choice among already-compatible encoders, not a speed guarantee.
 * Only compare hints from the current device/OS. Missing hints never make a codec unavailable.
 * Actual end-to-end qualification must include decode, filters, transfers, verification and retries.
 */
object CodecRanking {
    val preference: Comparator<CodecCandidate> =
        compareByDescending<CodecCandidate> { it.performanceHint != null }
            .thenByDescending { it.performanceHint?.lowerFps ?: 0.0 }
            .thenByDescending { it.performanceHint?.upperFps ?: 0.0 }
            .thenBy { it.platformRank }
            .thenBy { it.name }
}
