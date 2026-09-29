package dev.forma.app.data

import dev.forma.core.*
import dev.forma.ffmpeg.FfmpegBridge
import dev.forma.ffmpeg.verifyOutputStreams
import java.io.File
import dev.forma.core.audio.*
import dev.forma.ffmpeg.audio.*
import kotlinx.coroutines.*

/** Staging -> native execution -> structural verification -> private publication. */
class FfmpegTranscoder(private val files: MediaFiles, private val bridge: FfmpegBridge) {
    suspend fun run(spec: JobSpec, onState: suspend (JobState) -> Unit, onProgress: (Progress) -> Unit) = withContext(Dispatchers.IO) {
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
            val problems = Planner.validate(actual, spec.trim, spec.settings, caps)
            require(problems.isEmpty()) { problems.joinToString("\n") }
            val normalization=spec.settings.audioEdit.output.normalization
            val measurements = if (normalization.mode != NormalizationMode.OFF) {
                val identity=AudioAnalysisIdentity.create(AudioAnalysisIdentity.fingerprint(input),caps.build,actual,spec.trim,spec.settings)
                val analyzed=AudioAnalyzer(bridge).analyze(AudioAnalysisRequest(identity,input,actual,spec.trim,spec.settings))
                check(analyzed.identity==identity) { "Audio analysis is stale. Try again." }
                analyzed.measurement
            } else null
            val prepared = bridge.prepare(actual, spec.trim, spec.settings, input.absolutePath, temporary.absolutePath)
            val normalizationFilter=measurements?.normalizationFilter(normalization).orEmpty()
            val arguments = AudioAnalyzer.appendFilter(prepared,normalizationFilter,
                AudioGraphPlanner.plan(actual,spec.trim,spec.settings).sampleRateHz).toMutableList()
            if(normalization.mode==NormalizationMode.LOUDNESS) arguments[arguments.indexOf("-loglevel")+1]="info"
            onState(JobState.RUNNING)
            val result = bridge.execute(arguments, onProgress)
            check(result.exitCode == 0) { "FFmpeg failed (${result.exitCode}). ${result.diagnostics}" }
            if (normalization.mode==NormalizationMode.LOUDNESS && normalization.preserveDynamics && normalizationFilter.startsWith("loudnorm="))
                check(Regex(""""normalization_type"\s*:\s*"linear"""").containsMatchIn(result.diagnostics)) { "The requested normalization did not preserve dynamics." }
            currentCoroutineContext().ensureActive()
            onState(JobState.VERIFYING)
            check(temporary.isFile && temporary.length() > 0) { "FFmpeg did not produce a non-empty output." }
            val output = bridge.probe(temporary.absolutePath)
            val verification = AudioArtifactVerification.problems(actual, spec.trim, spec.settings, output, temporary.length())
            check(verification.isEmpty()) { verification.joinToString("\n") }
            val decoded = bridge.execute(listOf("-hide_banner", "-nostdin", "-v", "error", "-xerror", "-i", temporary.absolutePath,
                "-map", "0:v?", "-map", "0:a?", "-f", "null", "-")) {}
            check(decoded.exitCode == 0) { "The output could not be fully decoded. ${decoded.diagnostics}" }
            verifyOutputStreams(bridge, actual, spec.trim, spec.settings, input.absolutePath, temporary.absolutePath)
            if (normalization.mode != NormalizationMode.OFF) {
                val finalSettings=spec.settings.copy(audioTrack=0,audioEdit=AudioEdit(output=AudioOutputPolicy(channels=ChannelMode.SOURCE,normalization=normalization)))
                val measured=AudioAnalyzer(bridge).analyze(AudioAnalysisRequest("final",temporary,output,Trim(),finalSettings)).measurement
                if(normalization.mode==NormalizationMode.LOUDNESS) {
                    val values=(measured as? AudioMeasurementResult.Measured)?.values ?: error("The encoded output could not be measured.")
                    check(kotlin.math.abs(values.integratedLufs-normalization.integratedLufs)<=0.5) { "The encoded output missed its loudness target." }
                    check(values.truePeakDb<=normalization.truePeakDb+0.1) { "The encoded output exceeded its true-peak ceiling." }
                } else check((measured as? AudioMeasurementResult.Peak)?.samplePeakDb?.let { kotlin.math.abs(it-normalization.peakDb)<=0.1 } == true) { "The encoded output exceeded its sample-peak target." }
            }
            currentCoroutineContext().ensureActive()
            publishVerified(temporary,published) { onState(JobState.COMPLETED) }
        } finally { directory.deleteRecursively() }
    }
}
