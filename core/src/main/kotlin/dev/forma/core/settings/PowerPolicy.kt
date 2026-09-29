package dev.forma.core.settings

/** Ordered by the strongest action a worker must take. No action resumes a partial file. */
enum class PowerAction { CONTINUE, WARN, FINISH, STOP }
enum class ChargeState {
    CHARGING, FULL_PLUGGED, DISCHARGING, PLUGGED_IDLE, UNKNOWN;
    val eligible get() = this == CHARGING || this == FULL_PLUGGED
}
data class PowerSample(val percent: Int?, val charging: ChargeState, val thermal: Int? = null, val batterySaver: Boolean = false) {
    init { require(percent == null || percent in 0..100); require(thermal == null || thermal in 0..6) }
    companion object {
        fun percent(level: Int, scale: Int): Int? =
            if (scale <= 0 || level !in 0..scale) null else (level.toLong() * 100L / scale).toInt()
    }
}
data class PowerPreferences(
    val lowAction: PowerAction = PowerAction.FINISH, val lowPercent: Int = 20,
    val exemptWhileCharging: Boolean = true, val chargingOnly: Boolean = false,
    val autoContinue: Boolean = true, val resumeMargin: Int = 5,
    val unplugAction: PowerAction = PowerAction.CONTINUE,
    val thermalAction: PowerAction = PowerAction.STOP, val thermalThreshold: Int = 3,
    val respectSaver: Boolean = true
) {
    init {
        require(lowPercent in 5..50 && resumeMargin in 2..20 && lowPercent + resumeMargin <= 95)
        require(thermalThreshold in 2..3)
        require(thermalAction != PowerAction.CONTINUE)
        require(unplugAction != PowerAction.WARN)
    }
}
data class PowerState(
    val lowLatched: Boolean = false, val lowRecoverySince: Long? = null,
    val thermalLatched: Boolean = false, val thermalRecoverySince: Long? = null,
    val criticalLatched: Boolean = false,
    val unplugLatched: Boolean = false, val unplugRecoverySince: Long? = null,
    val previousCharge: ChargeState? = null, val waitingForUser: Boolean = false,
    val userStopped: Boolean = false
)
data class PowerDecision(
    val canStart: Boolean, val action: PowerAction, val reasons: Set<String>,
    val warnings: Set<String>, val state: PowerState, val threadCeiling: Int?
)

/** Pure policy only. The native worker adapter must still enforce cleanup and Android service rules. */
object PowerPolicy {
    private data class Latch(val active: Boolean, val since: Long?)
    private fun latch(was: Boolean, trigger: Boolean, recovering: Boolean, since: Long?, now: Long, delay: Long): Latch {
        if (trigger) return Latch(true, null)
        if (!was) return Latch(false, null)
        if (!recovering) return Latch(true, null)
        val start = since?.takeIf { it <= now } ?: now
        return if (now - start >= delay) Latch(false, null) else Latch(true, start)
    }
    fun evaluate(policy: PowerPreferences, sample: PowerSample, previous: PowerState = PowerState(),
                 nowMs: Long, activeAttempt: Boolean = false, userStopped: Boolean = false): PowerDecision {
        require(nowMs >= 0) { "Use monotonic, nonnegative time" }
        val exempt = policy.exemptWhileCharging && sample.charging.eligible
        val low = latch(previous.lowLatched,
            !exempt && sample.percent != null && sample.percent <= policy.lowPercent,
            exempt || sample.percent != null && sample.percent >= policy.lowPercent + policy.resumeMargin,
            previous.lowRecoverySince, nowMs, 10_000)
        val thermal = latch(previous.thermalLatched,
            sample.thermal != null && sample.thermal >= policy.thermalThreshold,
            sample.thermal != null && sample.thermal < policy.thermalThreshold,
            previous.thermalRecoverySince, nowMs, 30_000)
        val critical = (previous.criticalLatched || sample.thermal != null && sample.thermal >= 4) && thermal.active
        val unplug = latch(previous.unplugLatched,
            previous.previousCharge?.eligible == true && !sample.charging.eligible && sample.charging != ChargeState.UNKNOWN,
            sample.charging.eligible, previous.unplugRecoverySince, nowMs, 10_000)
        val actions = mutableListOf<PowerAction>()
        val reasons = linkedSetOf<String>()
        val warnings = linkedSetOf<String>()
        fun apply(reason: String, action: PowerAction) {
            actions += action
            if (action >= PowerAction.FINISH) reasons += reason
            if (action != PowerAction.CONTINUE) warnings += reason
        }
        if (low.active) apply("battery", policy.lowAction)
        if (thermal.active) apply("thermal", if (critical) PowerAction.STOP else policy.thermalAction)
        if (unplug.active) apply("charging_stopped", policy.unplugAction)
        if (policy.chargingOnly && !sample.charging.eligible) reasons += "charging_required"
        val stopped = previous.userStopped || userStopped
        val waitUser = previous.waitingForUser || !policy.autoContinue && reasons.isNotEmpty()
        if (stopped || waitUser) reasons += "user"
        if (stopped) actions += PowerAction.STOP
        if (sample.percent == null || sample.charging == ChargeState.UNKNOWN) warnings += "battery_unknown"
        if (sample.thermal == null) warnings += "thermal_unavailable"
        // Unknown telemetry is not a new charge state. Retain the last known sample
        // only for transition detection; eligibility still uses the current sample.
        val lastKnownCharge = sample.charging.takeUnless { it == ChargeState.UNKNOWN } ?: previous.previousCharge
        val state = PowerState(low.active, low.since, thermal.active, thermal.since, critical,
            unplug.active, unplug.since, lastKnownCharge, waitUser, stopped)
        return PowerDecision(reasons.isEmpty(), if (activeAttempt) actions.maxOrNull() ?: PowerAction.CONTINUE else PowerAction.CONTINUE,
            reasons.toSet(), warnings.toSet(), state, if (policy.respectSaver && sample.batterySaver) 2 else null)
    }
}
