package dev.forma.app.ui

import dev.forma.core.Progress
import org.junit.Assert.*
import org.junit.Test

class ConversionStatsTest {
    @Test fun etaUsesRemainingMediaTimeAndReportedConversionSpeed() {
        val stats = conversionStats(Progress(25_000, 2.0), 100_000)
        assertEquals(25, stats.percent)
        assertEquals(2.0, stats.speed!!, 0.0)
        assertEquals(37_500L, stats.etaMs)
    }
    @Test fun unknownOrInvalidSpeedDoesNotInventAnEta() {
        for (speed in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val stats = conversionStats(Progress(5_000, speed), 10_000)
            assertEquals(50, stats.percent)
            assertNull(stats.speed)
            assertNull(stats.etaMs)
        }
        assertNull(conversionStats(Progress(0, 2.0), 10_000).etaMs)
        assertNull(conversionStats(Progress(5_000, 2.0), 0).percent)
    }
    @Test fun encodingStaysBelow100UntilVerifiedCompletion() {
        assertEquals(99, conversionStats(Progress(20_000, 2.0), 10_000).percent)
        assertEquals(100, conversionStats(null, 10_000, completed = true).percent)
        assertEquals(0L, conversionStats(null, 10_000, completed = true).etaMs)
        assertNull(conversionStats(null, 10_000).percent)
    }
    @Test fun batteryCurrentAndVoltageConvertToApproximateWatts() {
        assertEquals(4.8, batteryDrawWatts(-1_200_000, 4000, true)!!, 0.000001)
        assertNull(batteryDrawWatts(Int.MIN_VALUE, 4000, true))
        assertNull(batteryDrawWatts(-1_200_000, 0, true))
        assertNull(batteryDrawWatts(1_200_000, 4000, true))
        assertNull(batteryDrawWatts(-1_200_000, 4000, false))
        assertNull(batteryDrawWatts(0, 4000, true))
    }
}
