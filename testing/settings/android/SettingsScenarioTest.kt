package dev.forma.app.settings

import android.os.Build
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.settings.*
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Test APK only. No production receiver, exported endpoint, external command or arbitrary input path. */
class SettingsScenarioTest {
    @Test fun runRequestedCase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("formaSettingsCase") ?: "catalog"
        val selections = mapOf(
            "catalog" to "catalog", "overrides" to "explicit auto", "battery_low" to "battery boundary",
            "charging_exception" to "charging must not", "thermal_wait" to "thermal recovery",
            "storage_roundtrip" to "store loads"
        )
        val requestedId = args.getString("formaSettingsRunId")
        val runId = requestedId?.let { UUID.fromString(it).toString() } ?: UUID.randomUUID().toString()
        val report = JSONObject().put("schema",1).put("runId",runId)
            .put("case",name).put("appRevision",args.getString("formaAppRevision") ?: "unrecorded")
            .put("device",Build.MODEL).put("osFingerprint",Build.FINGERPRINT)
            .put("abi",Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .put("nativeExecution",false).put("powerInputs","synthetic")
        var failure: Throwable? = null
        try {
            val filter = requireNotNull(selections[name]) { "Unknown or unimplemented scenario: $name" }
            val checks = (SettingsChecks.cases + NativePreferenceChecks.cases + SettingsStoreChecks.cases).filter { filter in it.first }
            check(checks.isNotEmpty()) { "No checks discovered for $name" }
            checks.forEach { it.second() }
            report.put("result","PASS").put("checks",JSONArray(checks.map { it.first }))
        } catch (error: Throwable) {
            failure = error
            report.put("result","FAIL").put("reason",error.message ?: error.javaClass.simpleName)
        }
        // Instrumentation runs as the target application's UID, not the test APK's UID.
        // Keep this test-only report separate from preferences, queue state and media files.
        val directory = File(instrumentation.targetContext.filesDir,"settings-tests").apply {
            check(isDirectory || mkdirs()) { "Could not create the test report directory in the target app sandbox." }
        }
        val resultFile = File(directory,"last-result.json")
        val encoded = report.toString(2)
        resultFile.writeText(encoded)
        check(resultFile.readText() == encoded) { "The instrumentation report did not round trip." }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", report.toString()) })
        failure?.let { throw it }
    }
}
