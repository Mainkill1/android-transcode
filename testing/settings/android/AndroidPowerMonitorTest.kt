package dev.forma.app.settings

import android.content.Intent
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import dev.forma.core.settings.ChargeState
import org.junit.Test

class AndroidPowerMonitorTest {
    private fun battery(level: Int, scale: Int, status: Int, plugged: Int = 0) =
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, scale)
            .putExtra(BatteryManager.EXTRA_STATUS, status)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)

    @Test fun mapsChargingFullIdleAndDischargingStates() {
        check(powerSampleFromBattery(battery(20, 100, BatteryManager.BATTERY_STATUS_CHARGING,
            BatteryManager.BATTERY_PLUGGED_USB), thermalStatus = 1, batterySaver = false).charging == ChargeState.CHARGING)
        check(powerSampleFromBattery(battery(100, 100, BatteryManager.BATTERY_STATUS_FULL,
            BatteryManager.BATTERY_PLUGGED_AC), thermalStatus = 1, batterySaver = false).charging == ChargeState.FULL_PLUGGED)
        check(powerSampleFromBattery(battery(80, 100, BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            BatteryManager.BATTERY_PLUGGED_USB), thermalStatus = 1, batterySaver = false).charging == ChargeState.PLUGGED_IDLE)
        check(powerSampleFromBattery(battery(80, 100, BatteryManager.BATTERY_STATUS_DISCHARGING),
            thermalStatus = 1, batterySaver = false).charging == ChargeState.DISCHARGING)
    }

    @Test fun invalidBatteryTelemetryStaysUnknownWithoutInventingZeroPercent() {
        val sample = powerSampleFromBattery(
            battery(level = -1, scale = 0, status = BatteryManager.BATTERY_STATUS_UNKNOWN),
            thermalStatus = null, batterySaver = true)
        check(sample.percent == null)
        check(sample.charging == ChargeState.UNKNOWN)
        check(sample.thermal == null)
        check(sample.batterySaver)
    }

    @Test fun preservesExactPercentageThermalSeverityAndSaverState() {
        val sample = powerSampleFromBattery(
            battery(25, 50, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_WIRELESS),
            thermalStatus = 3, batterySaver = true)
        check(sample.percent == 50)
        check(sample.thermal == 3)
        check(sample.batterySaver)
    }

    @Test fun monitorUsesSystemBroadcastRegistrationMode() {
        check(POWER_RECEIVER_FLAGS == ContextCompat.RECEIVER_EXPORTED)
    }
}
