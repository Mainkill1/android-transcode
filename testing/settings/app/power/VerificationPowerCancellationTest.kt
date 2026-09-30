package dev.forma.app.settings

import dev.forma.app.service.PowerServiceRules
import dev.forma.app.work.RunCoordinator
import dev.forma.app.work.RunMode
import dev.forma.app.work.StopReason
import dev.forma.core.*
import dev.forma.core.settings.*
import dev.forma.ffmpeg.*
import kotlinx.coroutines.*
import org.junit.Test

/** The worker lease stays occupied until a cancelled native verifier finishes. */
class VerificationPowerCancellationTest {
    @Test fun policyRecoveryCannotOverlapVerificationAndManualStopWins(): Unit = runBlocking {
        for (manualOverride in listOf(false, true)) {
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val entered=CompletableDeferred<Unit>()
            val nativeFinished=CompletableDeferred<Unit>()
            val bridge=ManagedFfmpegBridge(object:FfmpegBridge {
                override suspend fun capabilities()=Capabilities(true,"",emptySet(),emptySet(),emptySet())
                override suspend fun probe(localPath:String):Source=error("Only verification is exercised")
                override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult=error("No encode")
                override suspend fun inspectStreams(localPath:String,countFrames:Boolean):OutputFacts {
                    if (countFrames) {
                        entered.complete(Unit)
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) { nativeFinished.await() }
                        }
                    }
                    return OutputFacts(0,emptyList())
                }
            })
            try {
                val runs=RunCoordinator(scope)
                val first=requireNotNull(runs.start { bridge.inspectStreams("candidate",true) })
                withTimeout(5000) { entered.await() }
                val policy=SettingsDocument(values=PreferenceValues.of(mapOf(
                    "power.low_percent" to SettingValue.Integer(30),
                    "power.low_action" to SettingValue.Choice("stop"))))
                val low=PowerRuntimeEvaluator().evaluate(policy,PowerSample(20,ChargeState.DISCHARGING,0),true,100)
                check(low.instruction==PowerWorkerInstruction.STOP_CURRENT)
                runs.stop(first.id,StopReason.POWER_POLICY)
                yield()
                check(runs.state.value.mode==RunMode.STOPPING && !first.job.isCompleted)
                check(runs.start { error("Overlapping native work") }==null)
                val recovered=PowerRuntimeEvaluator().evaluate(policy,PowerSample(90,ChargeState.CHARGING,0),false,20_000)
                check(recovered.decision.canStart)
                check(runs.start { error("Policy recovery stole native lease") }==null)
                if (manualOverride) runs.stop(first.id,StopReason.USER)
                check(first.stopReason==(if(manualOverride) StopReason.USER else StopReason.POWER_POLICY))
                nativeFinished.complete(Unit)
                withTimeout(5000) { first.job.join() }
                withTimeout(5000) { while(runs.state.value.mode!=RunMode.IDLE) yield() }
                if (!manualOverride) check(PowerServiceRules.powerStopTransitions(JobState.VERIFYING)==
                    listOf(JobState.INTERRUPTED,JobState.QUEUED))
                val next=requireNotNull(runs.start { bridge.inspectStreams("next",false) })
                withTimeout(5000) { next.job.join() }
                check(next.failure==null)
            } finally { nativeFinished.complete(Unit);scope.cancel() }
        }
    }
}
