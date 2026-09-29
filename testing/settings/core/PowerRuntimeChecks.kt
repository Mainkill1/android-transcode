package dev.forma.core.settings

/** Worker-integration contracts. Android telemetry and service code consume these pure decisions. */
object PowerRuntimeChecks {
    private fun c(value: String) = SettingValue.Choice(value)
    private fun n(value: Long) = SettingValue.Integer(value)
    private fun f(value: Boolean) = SettingValue.Flag(value)

    val cases: List<Pair<String, () -> Unit>> = listOf(
        "eight power controls are implemented without enabling deferred automatic restart" to {
            check(PowerSettings.boundIds == setOf(
                "power.low_action", "power.low_percent", "power.only_when_not_charging",
                "power.charging_only", "power.resume_margin", "power.unplug_action",
                "power.thermal_action", "power.thermal_threshold"
            ))
            check(PowerSettings.boundIds.all { SettingCatalog[it].implemented })
            check(!SettingCatalog["power.auto_continue"].implemented)
            check(!SettingCatalog["power.respect_saver"].implemented)
            check(SettingCatalog.all.count { it.implemented } == 26)
        },
        "resolved power values map exactly into the reducer preferences" to {
            val saved = PreferenceValues.of(mapOf(
                "power.low_action" to c("stop"),
                "power.low_percent" to n(33),
                "power.only_when_not_charging" to f(false),
                "power.charging_only" to f(true),
                "power.resume_margin" to n(7),
                "power.unplug_action" to c("finish"),
                "power.thermal_action" to c("warn"),
                "power.thermal_threshold" to c("moderate")
            ))
            val mapped = PowerSettings.from(SettingsResolver.resolve(saved))
            check(mapped == PowerPreferences(
                lowAction = PowerAction.STOP,
                lowPercent = 33,
                exemptWhileCharging = false,
                chargingOnly = true,
                autoContinue = true,
                resumeMargin = 7,
                unplugAction = PowerAction.FINISH,
                thermalAction = PowerAction.WARN,
                thermalThreshold = 2,
                respectSaver = true
            ))
        },
        "worker instruction distinguishes idle blocking warning draining and cancellation" to {
            val sample = PowerSample(10, ChargeState.DISCHARGING, thermal = 0)
            val blocked = PowerPolicy.evaluate(PowerPreferences(), sample, nowMs = 0)
            check(PowerWorkerPolicy.instruction(blocked, activeAttempt = false) == PowerWorkerInstruction.BLOCK_START)

            val warning = PowerPolicy.evaluate(PowerPreferences(lowAction = PowerAction.WARN), sample,
                nowMs = 0, activeAttempt = true)
            check(PowerWorkerPolicy.instruction(warning, activeAttempt = true) == PowerWorkerInstruction.WARN)

            val draining = PowerPolicy.evaluate(PowerPreferences(), sample, nowMs = 0, activeAttempt = true)
            check(PowerWorkerPolicy.instruction(draining, activeAttempt = true) == PowerWorkerInstruction.FINISH_CURRENT)

            val stopping = PowerPolicy.evaluate(PowerPreferences(lowAction = PowerAction.STOP), sample,
                nowMs = 0, activeAttempt = true)
            check(PowerWorkerPolicy.instruction(stopping, activeAttempt = true) == PowerWorkerInstruction.STOP_CURRENT)

            val clear = PowerPolicy.evaluate(PowerPreferences(), PowerSample(80, ChargeState.CHARGING, 0), nowMs = 0)
            check(PowerWorkerPolicy.instruction(clear, activeAttempt = false) == PowerWorkerInstruction.CONTINUE)
        },
        "recovery decisions expose the next monotonic reevaluation deadline" to {
            val low = PowerPolicy.evaluate(PowerPreferences(), PowerSample(20, ChargeState.DISCHARGING, 0), nowMs = 100)
            val recovering = PowerPolicy.evaluate(PowerPreferences(), PowerSample(25, ChargeState.DISCHARGING, 0),
                low.state, nowMs = 1_000)
            check(recovering.recheckAtMs == 11_000L)

            val hot = PowerPolicy.evaluate(PowerPreferences(), PowerSample(80, ChargeState.CHARGING, 3), nowMs = 5_000)
            val cooling = PowerPolicy.evaluate(PowerPreferences(), PowerSample(80, ChargeState.CHARGING, 2),
                hot.state, nowMs = 7_000)
            check(cooling.recheckAtMs == 37_000L)
        }
    )

    @JvmStatic fun main(args: Array<String>) {
        cases.forEach { (name, test) -> test(); println("PASS $name") }
        println("PASS ${cases.size} power runtime checks")
    }
}
