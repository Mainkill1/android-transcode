package dev.forma.app.data

import dev.forma.core.*
import dev.forma.ffmpeg.*
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import dev.forma.core.audio.*
import dev.forma.ffmpeg.audio.*
import kotlinx.coroutines.*

/** Staging -> native execution -> structural verification -> private publication. */
class FfmpegTranscoder(private val files: MediaFiles, private val bridge: FfmpegBridge) {
    suspend fun run(spec: JobSpec, onState: suspend (JobState) -> Unit, onProgress: (Progress) -> Unit,
        onAttempt: (AttemptEvent) -> Unit = {}) = withContext(Dispatchers.IO) {
        val directory = files.workDir(spec)
        val temporary = File(directory, "encoded.${spec.settings.container.extension}")
        val published = files.output(spec)
        require(!published.exists()) { "An output already exists for this job. Retry as a new job instead of overwriting it." }
        try {
            val caps = bridge.capabilities()
            require(caps.available) { caps.reason }
            val input = files.stage(spec)
            val inspected = bridge.probe(input.absolutePath)
            val actual = inspected.copy(uri = spec.source.uri, name = spec.source.name)
            val problems = Planner.validate(actual,spec.trim,spec.targetBytes?.let { UploadFit.initial(spec.settings,Planner.outputDuration(actual,spec.trim,spec.settings),actual.audioTracks>0 && spec.settings.audio!=AudioEncoder.NONE,it) } ?: spec.settings,caps)
            require(problems.isEmpty()) { problems.joinToString("\n") }
            val normalization=spec.settings.audioEdit.output.normalization
            val measurements = if (normalization.mode != NormalizationMode.OFF) {
                val identity=AudioAnalysisIdentity.create(AudioAnalysisIdentity.fingerprint(input),caps.build,actual,spec.trim,spec.settings)
                val analyzed=AudioAnalyzer(bridge).analyze(AudioAnalysisRequest(identity,input,actual,spec.trim,spec.settings))
                check(analyzed.identity==identity) { "Audio analysis is stale. Try again." }
                analyzed.measurement
            } else null
            val normalizationFilter=measurements?.normalizationFilter(normalization).orEmpty()
            val reportFile=File(published.parentFile,"${spec.id}.acceleration.json")
            val events=JSONArray()
            val report=JSONObject().put("schemaVersion",1).put("jobId",spec.id)
                .put("targetBytes",spec.targetBytes ?: JSONObject.NULL).put("requestedEncoder",spec.settings.video.name)
                .put("events",events).put("publicationTrackedByQueue",true).put("deviceQualification",false)
                .put("sdk",Build.VERSION.SDK_INT).put("fingerprint",Build.FINGERPRINT).put("model",Build.MODEL).put("nativeBuild",caps.build)
            onState(JobState.RUNNING)
            ByteCapExport.run(bridge,actual,spec.trim,spec.settings,input,temporary,spec.targetBytes,
                onProgress=onProgress,
                onAttempt={ event ->
                    val route=event.attempt.decision
                    events.put(JSONObject().put("attempt",event.number).put("total",event.total)
                        .put("status",event.status.name).put("reason",event.reason)
                        .put("backend",route?.backend?.name ?: "UNSPECIFIED").put("encoder",route?.encoder ?: JSONObject.NULL)
                        .put("component",route?.codecName ?: JSONObject.NULL).put("hardware",route?.hardwareSupport?.name ?: "UNKNOWN")
                        .put("buffer",route?.bufferFormat?.name ?: JSONObject.NULL)
                        .put("bitrateMode",if(route?.backend==EncodeBackend.MEDIACODEC) route.bitrateMode.name else JSONObject.NULL))
                    reportFile.writeText(report.toString(2))
                    onAttempt(event)
                },
                transformAttempt={ budget,attempt ->
                    val arguments=AudioAnalyzer.appendFilter(attempt.arguments,normalizationFilter,
                        AudioGraphPlanner.plan(actual,spec.trim,budget).sampleRateHz).toMutableList()
                    if(normalization.mode==NormalizationMode.LOUDNESS) {
                        val log=arguments.indexOf("-loglevel")
                        if(log>=0)arguments[log+1]="info" else arguments.addAll(0,listOf("-loglevel","info"))
                    }
                    attempt.copy(arguments=arguments)
                },
                executeAttempt={ _,attempt,progress ->
                    val result=bridge.execute(attempt.arguments,progress)
                    if(result.exitCode==0 && normalization.mode==NormalizationMode.LOUDNESS && normalization.preserveDynamics && normalizationFilter.startsWith("loudnorm="))
                        check(Regex(""""normalization_type"\s*:\s*"linear"""").containsMatchIn(result.diagnostics)) { "The requested normalization did not preserve dynamics." }
                    result
                },
                verifyAttempt={ budget,attempt ->
                    verifyEncodedOutput(bridge,actual,spec.trim,budget,temporary,attempt)
                    val output=bridge.probe(temporary.absolutePath)
                    val problems=AudioArtifactVerification.problems(actual,spec.trim,budget,output,temporary.length())
                    check(problems.isEmpty()) { problems.joinToString("\n") }
                    if(normalization.mode!=NormalizationMode.OFF) {
                        val finalSettings=budget.copy(audioTrack=0,audioEdit=AudioEdit(output=AudioOutputPolicy(channels=ChannelMode.SOURCE,normalization=normalization)))
                        val measured=AudioAnalyzer(bridge).analyze(AudioAnalysisRequest("final",temporary,output,Trim(),finalSettings)).measurement
                        if(normalization.mode==NormalizationMode.LOUDNESS) {
                            val values=(measured as? AudioMeasurementResult.Measured)?.values ?: error("The encoded output could not be measured.")
                            check(kotlin.math.abs(values.integratedLufs-normalization.integratedLufs)<=0.5) { "The encoded output missed its loudness target." }
                            check(values.truePeakDb<=normalization.truePeakDb+0.1) { "The encoded output exceeded its true-peak ceiling." }
                        } else check((measured as? AudioMeasurementResult.Peak)?.samplePeakDb?.let { kotlin.math.abs(it-normalization.peakDb)<=0.1 }==true) { "The encoded output exceeded its sample-peak target." }
                    }
                })
            onState(JobState.VERIFYING)
            currentCoroutineContext().ensureActive()
            publishVerified(temporary,published) { onState(JobState.COMPLETED) }
        } finally { directory.deleteRecursively() }
    }
}
