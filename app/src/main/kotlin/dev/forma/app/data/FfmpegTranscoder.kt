package dev.forma.app.data

import android.os.Build
import dev.forma.core.*
import dev.forma.ffmpeg.*
import java.io.File
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Staging -> bounded native trials -> output verification -> private publication. */
class FfmpegTranscoder(private val files: MediaFiles, private val bridge: FfmpegBridge) {
    suspend fun run(spec: JobSpec, onState: suspend (JobState) -> Unit, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
        val directory = files.workDir(spec)
        val temporary = File(directory, "encoded.${spec.settings.container.extension}")
        val published = files.output(spec)
        require(!published.exists()) { "An output already exists for this job. Retry as a new job instead of overwriting it." }
        // A private sidecar survives work-directory cleanup. No source URI/path, logs or tokens.
        val reportFile = File(published.parentFile, "${spec.id}.acceleration.json")
        val events = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("jobId", spec.id)
            .put("requestedEncoder", spec.settings.video.name).put("events", events)
            .put("publicationTrackedByQueue", true).put("deviceQualification", false)
            .put("sdk", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT).put("model", Build.MODEL)
        try {
            val caps = bridge.capabilities()
            require(caps.available) { caps.reason }
            report.put("nativeBuild", caps.build)
            val input = files.stage(spec)
            val inspected = bridge.probe(input.absolutePath)
            val actual = inspected.copy(uri = spec.source.uri, name = spec.source.name)
            val problems = Planner.validate(actual, spec.trim, spec.settings, caps)
            require(problems.isEmpty()) { problems.joinToString("\n") }
            val attempts = bridge.prepareAttempts(actual, spec.trim, spec.settings, input.absolutePath, temporary.absolutePath)
            onState(JobState.RUNNING)
            ExportRetry.run(attempts, temporary,
                execute = { attempt, progress -> bridge.execute(attempt.arguments, progress) },
                verify = { attempt -> verifyEncodedOutput(bridge, actual, spec.trim, spec.settings, temporary, attempt) },
                onProgress = onProgress,
                onAttempt = { event ->
                    val route = event.attempt.decision
                    events.put(JSONObject().put("attempt", event.number).put("total", event.total)
                        .put("status", event.status.name).put("reason", event.reason)
                        .put("backend", route?.backend?.name ?: "UNSPECIFIED")
                        .put("encoder", route?.encoder ?: JSONObject.NULL)
                        .put("component", route?.codecName ?: JSONObject.NULL)
                        .put("hardware", route?.hardwareSupport?.name ?: "UNKNOWN")
                        .put("buffer", route?.bufferFormat?.name ?: JSONObject.NULL)
                        .put("bitrateMode", if (route?.backend == EncodeBackend.MEDIACODEC) route.bitrateMode.name else JSONObject.NULL))
                    reportFile.writeText(report.toString(2))
                })
            currentCoroutineContext().ensureActive()
            onState(JobState.VERIFYING)
            // Per-attempt checks are complete; the existing final-publication state is retained.
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                check(temporary.renameTo(published)) { "The verified output could not be published." }
                onState(JobState.COMPLETED)
            }
        } finally { directory.deleteRecursively() }
    }
}
