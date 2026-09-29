package dev.forma.app.settings

import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.settings.*
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Test APK only: invoke with am instrument directly; there is no host wrapper or production endpoint. */
class SettingsScenarioTest {
    @Test fun runRequestedCase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("formaSettingsCase") ?: "all"
        val requestedId = args.getString("formaSettingsRunId")
        val runId = requestedId?.let {
            val canonical = UUID.fromString(it).toString()
            require(canonical.equals(it, ignoreCase=true)) { "Use a complete UUID for formaSettingsRunId" }
            canonical
        } ?: UUID.randomUUID().toString()
        val directory = File(instrumentation.targetContext.filesDir, "settings-tests").apply {
            check(isDirectory || mkdirs()) { "Could not create the test report directory in the target sandbox" }
        }
        // Read exactly this run's report, never a previous last-result.json.
        val resultFile = File(directory, "$runId.json")
        check(!resultFile.exists()) { "Run ID already used; supply a fresh UUID: $runId" }
        val report = JSONObject().put("schema", 2).put("runId", runId).put("case", name)
            .put("callerReportedAppRevision", args.getString("formaAppRevision") ?: JSONObject.NULL)
            .put("device", Build.MODEL).put("osFingerprint", Build.FINGERPRINT)
            .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("nativeExecution", false).put("powerInputs", "synthetic")
            .put("reportPath", "files/settings-tests/$runId.json")
        var failure: Throwable? = null
        try {
            val all = SettingsChecks.cases + NativePreferenceChecks.cases + SettingsStoreChecks.cases + PowerRegressionChecks.cases
            val checks = when (name) {
                "all" -> all + AndroidSettingsStorageChecks.cases
                "catalog" -> all.filter { "catalog" in it.first }
                "overrides" -> all.filter { "explicit auto" in it.first || "reset removes" in it.first || "preset precedence" in it.first }
                "battery_low" -> all.filter { "battery boundary" in it.first || "battery recovery" in it.first }
                "charging_exception" -> all.filter { "charging must not" in it.first || "unknown charging" in it.first }
                "thermal_wait" -> all.filter { "thermal" in it.first }
                "storage_memory" -> SettingsStoreChecks.cases
                "storage_roundtrip" -> AndroidSettingsStorageChecks.cases
                else -> throw IllegalArgumentException("Unknown or unimplemented scenario: $name")
            }
            check(checks.isNotEmpty()) { "No checks discovered for $name" }
            val passed = JSONArray()
            report.put("passedChecks", passed).put("selectedCount", checks.size)
            checks.forEach { (label, assertion) ->
                report.put("currentCheck", label)
                assertion()
                passed.put(label)
            }
            report.remove("currentCheck")
            report.put("result", "PASS").put("passedCount", passed.length())
                .put("androidStorage", name in setOf("all", "storage_roundtrip"))
        } catch (error: Throwable) {
            failure = error
            report.put("result", "FAIL").put("reason", error.message ?: error.javaClass.simpleName)
        }
        val atomic = AtomicFile(resultFile)
        val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
        try {
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
            check(atomic.openRead().use { it.readBytes().contentEquals(bytes) }) { "Report did not round trip" }
        } catch (error: Throwable) {
            failure?.let(error::addSuppressed)
            throw error // No PASS stream when the result could not be stored and read back.
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", report.toString()) })
        failure?.let { throw it }
    }
}
