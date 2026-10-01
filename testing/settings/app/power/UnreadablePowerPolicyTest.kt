package dev.forma.app.settings

import dev.forma.app.work.*
import dev.forma.core.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class UnreadablePowerPolicyTest {
    @Test fun unreadablePolicyCannotDowngradeCriticalThermalStopToFinish() {
        val settings=MutableStateFlow(SettingsLoadState(error="Unreadable safeguards"))
        val samples=MutableStateFlow(PowerSample(100,ChargeState.CHARGING,thermal=4))
        val runs=MutableStateFlow(RunState(RunMode.RUNNING,1))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val runtime=PowerRuntime(settings,samples,runs,scope) { 100L }
            val critical=runtime.refresh()
            check(!critical.decision.canStart)
            check(critical.instruction==PowerWorkerInstruction.STOP_CURRENT)
            samples.value=PowerSample(100,ChargeState.CHARGING,thermal=null)
            check(runtime.refresh().instruction==PowerWorkerInstruction.STOP_CURRENT)
            check("settings_unavailable" in runtime.refresh().decision.reasons)
            settings.value=SettingsLoadState(SettingsDocument())
            check(runtime.refresh().instruction==PowerWorkerInstruction.STOP_CURRENT)
        } finally { scope.cancel() }
    }

    @Test fun unreadablePolicyKeepsTheLastKnownThermalRecoveryThreshold() {
        val settings=MutableStateFlow(SettingsLoadState(SettingsDocument(9,PreferenceValues.EMPTY
            .with("power.thermal_threshold",SettingValue.Choice("moderate")))))
        val samples=MutableStateFlow(PowerSample(100,ChargeState.CHARGING,thermal=4))
        val runs=MutableStateFlow(RunState(RunMode.RUNNING,1))
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        var now=100L
        try {
            val runtime=PowerRuntime(settings,samples,runs,scope) { now }
            settings.value=SettingsLoadState(error="Unreadable safeguards")
            samples.value=PowerSample(100,ChargeState.CHARGING,thermal=2)
            runtime.refresh()
            now=31_000L
            check(runtime.refresh().instruction==PowerWorkerInstruction.STOP_CURRENT)
        } finally { scope.cancel() }
    }
}
