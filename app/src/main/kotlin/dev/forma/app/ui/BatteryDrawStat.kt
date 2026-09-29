package dev.forma.app.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import java.util.Locale

private data class BatteryReading(val watts: Double? = null, val charging: Boolean = false)
private fun readBattery(context: Context): BatteryReading = try {
    val manager = context.getSystemService(BatteryManager::class.java)
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val charging = manager?.isCharging == true
    val discharging = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_DISCHARGING
    BatteryReading(batteryDrawWatts(manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE,
        battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0, discharging), charging)
} catch (_: Exception) { BatteryReading() }

@Composable internal fun BatteryDrawStat(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    var reading by remember { mutableStateOf(BatteryReading()) }
    LaunchedEffect(context, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                reading = withContext(Dispatchers.IO) { readBattery(context) }
                delay(2000)
            }
        }
    }
    ProgressStat("Battery draw", reading.watts?.let { String.format(Locale.ROOT, "≈%.1f W", it) }
        ?: if (reading.charging) "Charging" else "—", modifier)
}

@Composable internal fun ProgressStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}
