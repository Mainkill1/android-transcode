package dev.forma.app.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.forma.core.settings.ChargeState
import dev.forma.core.settings.PowerSample
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun powerSampleFromBattery(
    battery: Intent?, thermalStatus: Int?, batterySaver: Boolean
): PowerSample {
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        ?: BatteryManager.BATTERY_STATUS_UNKNOWN
    val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
    val charging = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> ChargeState.CHARGING
        BatteryManager.BATTERY_STATUS_FULL -> if (plugged != 0) ChargeState.FULL_PLUGGED else ChargeState.DISCHARGING
        BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeState.DISCHARGING
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> if (plugged != 0) ChargeState.PLUGGED_IDLE else ChargeState.DISCHARGING
        else -> ChargeState.UNKNOWN
    }
    return PowerSample(
        percent = PowerSample.percent(level, scale),
        charging = charging,
        thermal = thermalStatus?.takeIf { it in 0..6 },
        batterySaver = batterySaver
    )
}

/** Process-owned Android telemetry source. It never starts work or owns an encoder. */
class AndroidPowerMonitor(context: Context) : Closeable {
    private val application = context.applicationContext
    private val power = application.getSystemService(PowerManager::class.java)
    private var battery: Intent? = null
    private var thermal: Int? = if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null
    private var receiverRegistered = false
    private val mutable = MutableStateFlow(powerSampleFromBattery(null, thermal, power.isPowerSaveMode))
    val samples: StateFlow<PowerSample> = mutable.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            battery = if (intent?.action == Intent.ACTION_BATTERY_CHANGED) intent else currentBattery() ?: battery
            publish()
        }
    }
    private val thermalListener: PowerManager.OnThermalStatusChangedListener? =
        if (Build.VERSION.SDK_INT >= 29) PowerManager.OnThermalStatusChangedListener { status ->
            thermal = status
            publish()
        } else null

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        battery = ContextCompat.registerReceiver(
            application, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        publish()
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let { power.addThermalStatusListener(application.mainExecutor, it) }
        }
    }

    private fun publish() {
        mutable.value = powerSampleFromBattery(battery, thermal, power.isPowerSaveMode)
    }

    @Suppress("DEPRECATION")
    private fun currentBattery(): Intent? =
        application.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    override fun close() {
        if (receiverRegistered) {
            runCatching { application.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let { listener -> runCatching { power.removeThermalStatusListener(listener) } }
        }
    }
}
