package dev.forma.core.settings

/** Typed bridge from persisted settings into the pure power-policy reducer. */
object PowerSettings {
    val boundIds: Set<String> = linkedSetOf(
        "power.low_action",
        "power.low_percent",
        "power.only_when_not_charging",
        "power.charging_only",
        "power.resume_margin",
        "power.unplug_action",
        "power.thermal_action",
        "power.thermal_threshold"
    )

    fun from(resolved: Map<String, ResolvedSetting>): PowerPreferences {
        fun value(id: String): SettingValue = resolved[id]?.value ?: SettingCatalog[id].defaultValue
        fun choice(id: String): String = (value(id) as SettingValue.Choice).value
        fun integer(id: String): Int = (value(id) as SettingValue.Integer).value.toInt()
        fun flag(id: String): Boolean = (value(id) as SettingValue.Flag).value
        fun action(id: String): PowerAction = when (val selected = choice(id)) {
            "continue", "ignore" -> PowerAction.CONTINUE
            "warn" -> PowerAction.WARN
            "finish" -> PowerAction.FINISH
            "stop" -> PowerAction.STOP
            else -> error("Unsupported action for $id: $selected")
        }
        return PowerPreferences(
            lowAction = action("power.low_action"),
            lowPercent = integer("power.low_percent"),
            exemptWhileCharging = flag("power.only_when_not_charging"),
            chargingOnly = flag("power.charging_only"),
            autoContinue = flag("power.auto_continue"),
            resumeMargin = integer("power.resume_margin"),
            unplugAction = action("power.unplug_action"),
            thermalAction = action("power.thermal_action"),
            thermalThreshold = when (val selected = choice("power.thermal_threshold")) {
                "moderate" -> 2
                "severe" -> 3
                else -> error("Unsupported thermal threshold: $selected")
            },
            respectSaver = flag("power.respect_saver")
        )
    }
}

/** Existing native bindings and live worker policies are both implemented controls. */
val SettingSpec.implemented: Boolean get() = wired || id in PowerSettings.boundIds

/** Replace stale design copy without changing the frozen 64-key registry shape. */
val SettingSpec.effectiveHelp: String get() = when (id) {
    "power.low_action" -> "Warn, finish the active attempt, or cancel safely. A cancelled attempt is requeued only after native cleanup."
    "power.low_percent" -> "At or below this level. Recovery requires the configured margin for 10 continuous seconds."
    "power.only_when_not_charging" -> "Active charging or full-and-plugged can exempt the low-battery rule; cable presence alone cannot."
    "power.charging_only" -> "Blocks service start and each new queue claim unless charging is active or the battery is full while plugged."
    "power.resume_margin" -> "Percentage points above the threshold required before a low-battery episode clears."
    "power.unplug_action" -> "Applied when eligible charging stops; Continue still obeys low-battery and charging-only gates."
    "power.thermal_action" -> "Uses Android thermal severity. Critical or higher always cancels and blocks new attempts."
    "power.thermal_threshold" -> "Android severity, not an invented CPU temperature. Thermal telemetry is available on API 29 and newer."
    else -> help
}

enum class PowerWorkerInstruction { CONTINUE, WARN, FINISH_CURRENT, STOP_CURRENT, BLOCK_START }

object PowerWorkerPolicy {
    fun instruction(decision: PowerDecision, activeAttempt: Boolean): PowerWorkerInstruction {
        if (!activeAttempt && !decision.canStart) return PowerWorkerInstruction.BLOCK_START
        return when (decision.action) {
            PowerAction.CONTINUE -> PowerWorkerInstruction.CONTINUE
            PowerAction.WARN -> PowerWorkerInstruction.WARN
            PowerAction.FINISH -> PowerWorkerInstruction.FINISH_CURRENT
            PowerAction.STOP -> PowerWorkerInstruction.STOP_CURRENT
        }
    }
}
