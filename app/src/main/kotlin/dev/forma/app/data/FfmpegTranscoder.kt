package dev.forma.app.data

import dev.forma.core.*
import dev.forma.ffmpeg.*
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.*

/** One service lease covers every staged original, analysis, retry, verification and durable commit. */
class FfmpegTranscoder(private val files:MediaFiles,private val bridge:FfmpegBridge) {
    suspend fun run(spec:JobSpec,onState:suspend(JobState)->Unit,onProgress:(Progress)->Unit,
        onAttempt:(AttemptEvent)->Unit={},onRenderedAttempt:(RenderAttempt)->Unit={})=withContext(Dispatchers.IO) {
        val directory=files.workDir(spec)
        val temporary=File(directory,"encoded.${spec.settings.container.extension}")
        val published=files.output(spec)
        require(!published.exists()){ "An output already exists for this job. Retry as a new job instead of overwriting it." }
        try {
            val caps=bridge.capabilities();check(caps.available){caps.reason}
            val inputs=files.stageInputs(spec)
            val reportFile=File(published.parentFile,"${spec.id}.acceleration.json")
            val events=JSONArray()
            val report=JSONObject().put("schemaVersion",1).put("jobId",spec.id)
                .put("targetBytes",spec.targetBytes ?: JSONObject.NULL).put("requestedEncoder",spec.settings.video.name)
                .put("events",events).put("publicationTrackedByQueue",true).put("deviceQualification",false)
                .put("sdk",Build.VERSION.SDK_INT).put("fingerprint",Build.FINGERPRINT).put("model",Build.MODEL).put("nativeBuild",caps.build)
            onState(JobState.RUNNING)
            FfmpegRenderSession(bridge).render(spec,inputs,temporary,onProgress,
                onAttempt=onRenderedAttempt,onVerifying={onState(JobState.VERIFYING)},
                onRoute={event ->
                    val route=event.attempt.decision
                    events.put(JSONObject().put("attempt",event.number).put("total",event.total)
                        .put("status",event.status.name).put("reason",event.reason)
                        .put("backend",route?.backend?.name ?: "UNSPECIFIED").put("encoder",route?.encoder ?: JSONObject.NULL)
                        .put("component",route?.codecName ?: JSONObject.NULL).put("hardware",route?.hardwareSupport?.name ?: "UNKNOWN")
                        .put("buffer",route?.bufferFormat?.name ?: JSONObject.NULL)
                        .put("bitrateMode",if(route?.backend==EncodeBackend.MEDIACODEC)route.bitrateMode.name else JSONObject.NULL))
                    reportFile.writeText(report.toString(2));onAttempt(event)
                })
            currentCoroutineContext().ensureActive()
            publishVerified(temporary,published){onState(JobState.COMPLETED)}
        }finally {directory.deleteRecursively()}
    }
}
