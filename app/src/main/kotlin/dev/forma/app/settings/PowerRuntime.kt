package dev.forma.app.settings

import android.os.SystemClock
import dev.forma.app.work.RunMode
import dev.forma.app.work.RunState
import dev.forma.core.settings.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** One evaluated policy snapshot. It describes a restart from the original, never partial-file resume. */
data class PowerRuntimeSnapshot(
    val settingsRevision: Long,
    val preferences: PowerPreferences,
    val sample: PowerSample,
    val decision: PowerDecision,
    val instruction: PowerWorkerInstruction,
    val blockingMessage: String,
    val warningMessage: String?
)

/** Stateful adapter around the pure reducer; callers provide monotonic time. */
class PowerRuntimeEvaluator {
    private var state = PowerState()

    @Synchronized fun evaluate(
        document: SettingsDocument?, sample: PowerSample, activeAttempt: Boolean, nowMs: Long
    ): PowerRuntimeSnapshot {
        val saved = document ?: SettingsDocument()
        val preferences = PowerSettings.from(SettingsResolver.resolve(saved.values))
        val decision = PowerPolicy.evaluate(preferences, sample, state, nowMs, activeAttempt)
        state = decision.state
        val reasons = describe(decision.reasons)
        val warnings = describe(decision.warnings)
        return PowerRuntimeSnapshot(
            settingsRevision = saved.revision,
            preferences = preferences,
            sample = sample,
            decision = decision,
            instruction = PowerWorkerPolicy.instruction(decision, activeAttempt),
            blockingMessage = if (decision.canStart) "Device conditions allow conversion."
                else "Waiting for $reasons. A stopped attempt restarts from the original file.",
            warningMessage = warnings.takeIf { it.isNotBlank() }?.let { "Device condition warning: $it." }
        )
    }

    private fun describe(reasons: Set<String>): String = reasons.map { reason ->
        when (reason) {
            "battery" -> "battery recovery"
            "thermal" -> "the device to cool"
            "charging_required" -> "active charging"
            "charging_stopped" -> "charging power"
            "user" -> "user review"
            "battery_unknown" -> "battery status is unavailable"
            "thermal_unavailable" -> "thermal status is unavailable"
            else -> reason.replace('_', ' ')
        }
    }.joinToString(" and ")
}

/** Process-owned live policy. It observes only; queue execution remains owned by the foreground service. */
class PowerRuntime(
    private val settings: StateFlow<SettingsLoadState>,
    private val samples: StateFlow<PowerSample>,
    private val runs: StateFlow<RunState>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    private val evaluator = PowerRuntimeEvaluator()
    private val evaluationLock = Any()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var timer: Job? = null
    private var lastReadableDocument: SettingsDocument? = null
    private val mutable = MutableStateFlow(evaluateCurrent())
    val state: StateFlow<PowerRuntimeSnapshot> = mutable.asStateFlow()

    init {
        scope.launch { settings.collect { signal.trySend(Unit) } }
        scope.launch { samples.collect { signal.trySend(Unit) } }
        scope.launch { runs.collect { signal.trySend(Unit) } }
        scope.launch {
            for (ignored in signal) {
                val snapshot = evaluateAndPublish()
                timer?.cancel()
                timer = snapshot.decision.recheckAtMs?.let { deadline ->
                    scope.launch {
                        delay((deadline - clock()).coerceAtLeast(1L))
                        signal.trySend(Unit)
                    }
                }
            }
        }
        signal.trySend(Unit)
    }

    /**
     * Synchronously re-reads the latest durable-settings state, telemetry and run state.
     * The foreground worker calls this after initialization and before every queue claim,
     * so a just-loaded safety policy cannot trail one Flow dispatch behind job one.
     */
    fun refresh(): PowerRuntimeSnapshot {
        val snapshot = evaluateAndPublish()
        // Timer ownership stays in the single actor. A conflated follow-up cannot make
        // this returned snapshot stale and will reschedule any recovery deadline.
        signal.trySend(Unit)
        return snapshot
    }

    private fun evaluateAndPublish(): PowerRuntimeSnapshot = synchronized(evaluationLock) {
        evaluateCurrent().also { mutable.value = it }
    }

    private fun evaluateCurrent(): PowerRuntimeSnapshot {
        val loaded = settings.value
        val sample = samples.value
        val activeAttempt = runs.value.mode != RunMode.IDLE
        loaded.error?.let { error ->
            // A corrupt or unreadable saved policy may contain stricter charging or
            // battery requirements than factory defaults. Do not silently replace it.
            // Mandatory thermal safety and its recovery latch remain active even
            // when an optional saved policy cannot be read. Never downgrade Critical
            // to finishing the file, nor erase a Critical episode on Unknown telemetry.
            val safetyDocument=loaded.document ?: lastReadableDocument ?: SettingsDocument(values=
                PreferenceValues.EMPTY.with("power.thermal_threshold",SettingValue.Choice("moderate")))
            val safety=evaluator.evaluate(safetyDocument,sample,activeAttempt,clock().coerceAtLeast(0L))
            val decision = safety.decision.copy(
                canStart = false,
                action = if(safety.decision.state.criticalLatched) PowerAction.STOP
                    else if (activeAttempt) PowerAction.FINISH else PowerAction.CONTINUE,
                reasons = safety.decision.reasons + "settings_unavailable",
                warnings = safety.decision.warnings + "settings_unavailable",
                state = safety.decision.state.copy(waitingForUser = true),
                threadCeiling = null
            )
            return PowerRuntimeSnapshot(
                settingsRevision = loaded.document?.revision ?: 0,
                preferences = PowerPreferences(),
                sample = sample,
                decision = decision,
                instruction = PowerWorkerPolicy.instruction(decision, activeAttempt),
                blockingMessage = "Waiting for Settings review. Saved power safeguards could not be read, so factory defaults were not applied.",
                warningMessage = error
            )
        }
        loaded.document?.let { lastReadableDocument=it }
        return evaluator.evaluate(
            document = loaded.document,
            sample = sample,
            activeAttempt = activeAttempt,
            nowMs = clock().coerceAtLeast(0L)
        )
    }
}
