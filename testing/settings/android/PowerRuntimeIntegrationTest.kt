package dev.forma.app.settings

import dev.forma.core.settings.*
import org.junit.Test

class PowerRuntimeIntegrationTest {
    @Test fun evaluatorUsesSavedPolicyAndCarriesRecoveryHysteresis() {
        val document = SettingsDocument(4, PreferenceValues.of(mapOf(
            "power.low_percent" to SettingValue.Integer(30),
            "power.low_action" to SettingValue.Choice("stop"),
            "power.resume_margin" to SettingValue.Integer(5)
        )))
        val evaluator = PowerRuntimeEvaluator()

        val low = evaluator.evaluate(document, PowerSample(30, ChargeState.DISCHARGING, 0),
            activeAttempt = true, nowMs = 100)
        check(low.settingsRevision == 4L)
        check(!low.decision.canStart)
        check(low.decision.action == PowerAction.STOP)

        val recovering = evaluator.evaluate(document, PowerSample(35, ChargeState.DISCHARGING, 0),
            activeAttempt = false, nowMs = 1_000)
        check(!recovering.decision.canStart)
        check(recovering.decision.recheckAtMs == 11_000L)

        val ready = evaluator.evaluate(document, PowerSample(35, ChargeState.DISCHARGING, 0),
            activeAttempt = false, nowMs = 11_000)
        check(ready.decision.canStart)
    }

    @Test fun runtimeMessagesDoNotClaimAFileCanResumeMidEncode() {
        val evaluator = PowerRuntimeEvaluator()
        val blocked = evaluator.evaluate(SettingsDocument(), PowerSample(10, ChargeState.DISCHARGING, 0),
            activeAttempt = false, nowMs = 0)
        check(blocked.blockingMessage.contains("waiting", ignoreCase = true))
        check(!blocked.blockingMessage.contains("resume at", ignoreCase = true))
    }
}
