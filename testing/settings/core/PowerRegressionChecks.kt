package dev.forma.core.settings

/** Telemetry-transition regressions; these do not pretend to enforce policy in the Android worker. */
object PowerRegressionChecks {
    val cases: List<Pair<String, () -> Unit>> = listOf(
        "unknown charging sample cannot erase a later unplug transition" to {
            val policy = PowerPreferences(unplugAction=PowerAction.STOP)
            val charging = PowerPolicy.evaluate(policy, PowerSample(80, ChargeState.CHARGING, 0), nowMs=0)
            val unknown = PowerPolicy.evaluate(policy, PowerSample(80, ChargeState.UNKNOWN, 0), charging.state, 1000, activeAttempt=true)
            check(unknown.action == PowerAction.CONTINUE)
            val unplugged = PowerPolicy.evaluate(policy, PowerSample(80, ChargeState.DISCHARGING, 0), unknown.state, 2000, activeAttempt=true)
            check(unplugged.action == PowerAction.STOP && !unplugged.canStart && "charging_stopped" in unplugged.reasons)
        },
        "unknown charging samples do not invent an unplug on first reading" to {
            val policy = PowerPreferences(unplugAction=PowerAction.STOP)
            val unknown = PowerPolicy.evaluate(policy, PowerSample(80, ChargeState.UNKNOWN, 0), nowMs=0)
            val unplugged = PowerPolicy.evaluate(policy, PowerSample(80, ChargeState.DISCHARGING, 0), unknown.state, 1000, activeAttempt=true)
            check(unplugged.action == PowerAction.CONTINUE && unplugged.canStart)
        },
        "unknown during unplug recovery restarts the continuous recovery timer" to {
            val p = PowerPreferences(unplugAction=PowerAction.FINISH)
            val charging = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 0), nowMs=0)
            val unplugged = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.DISCHARGING, 0), charging.state, 100)
            val recovery = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 0), unplugged.state, 1000)
            val unknown = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.UNKNOWN, 0), recovery.state, 10000)
            val restarted = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 0), unknown.state, 11000)
            check(!PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 0), restarted.state, 20999).canStart)
            check(PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 0), restarted.state, 21000).canStart)
        },
        "critical thermal latch survives unknown telemetry until stable recovery" to {
            val p = PowerPreferences(thermalAction=PowerAction.WARN)
            val critical = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 4), nowMs=0)
            val unknown = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, null), critical.state, 1000)
            val recovery = PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 0), unknown.state, 2000)
            check(PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 0), recovery.state, 31999, activeAttempt=true).action == PowerAction.STOP)
            check(PowerPolicy.evaluate(p, PowerSample(80, ChargeState.CHARGING, 0), recovery.state, 32000).canStart)
        }
    )
    @JvmStatic fun main(args: Array<String>) {
        cases.forEach { (name, test) -> test(); println("PASS $name") }
        println("PASS ${cases.size} power regression checks")
    }
}
