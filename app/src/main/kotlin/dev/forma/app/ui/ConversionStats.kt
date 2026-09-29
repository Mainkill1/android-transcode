package dev.forma.app.ui

import dev.forma.core.Progress
import dev.forma.core.WorkPolicy
import kotlin.math.ceil

internal data class ConversionStats(val percent: Int? = null, val speed: Double? = null, val etaMs: Long? = null)
internal fun conversionStats(progress: Progress?, durationMs: Long, completed: Boolean = false): ConversionStats {
    if (completed) return ConversionStats(percent = 100, etaMs = 0)
    val speed = progress?.speed?.takeIf { it.isFinite() && it > 0 }
    val percent = progress?.let { WorkPolicy.fraction(it.processedMs, durationMs)?.let { f -> (f * 100).toInt() } }
    val remaining = if (progress != null && durationMs > 0 && progress.processedMs > 0 && speed != null)
        (durationMs - progress.processedMs.coerceIn(0, durationMs)).toDouble() / speed else null
    return ConversionStats(percent, speed, remaining?.takeIf { it.isFinite() && it < Long.MAX_VALUE.toDouble() }?.let { ceil(it).toLong() })
}
/** Public Android units: microamps × millivolts / 1e9 = watts. Net battery draw, not SoC power. */
internal fun batteryDrawWatts(currentMicroAmps: Int, voltageMilliVolts: Int, discharging: Boolean): Double? {
    if (!discharging || currentMicroAmps == Int.MIN_VALUE || currentMicroAmps >= 0 || voltageMilliVolts <= 0) return null
    return -currentMicroAmps.toDouble() * voltageMilliVolts / 1_000_000_000.0
}
