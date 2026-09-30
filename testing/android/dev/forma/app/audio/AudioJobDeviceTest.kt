package dev.forma.app.audio

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.*
import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.ffmpeg.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class AudioJobDeviceTest {
    @Before fun requireNativeOptIn() {
        assumeTrue("Run explicitly with -e formaNative true on a native Android build.",
            InstrumentationRegistry.getArguments().getString("formaNative") == "true")
    }

    @Test fun productionJobsVerifyPeakCapAndCancellation():Unit=runBlocking(Dispatchers.IO) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val sourceDir=File(context.filesDir,"imports/audio-job-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val input=File(sourceDir,"program.wav")
        val bridge=ManagedFfmpegBridge(createFfmpegBridge());val files=MediaFiles(context)
        val report=JSONObject();val artifacts=mutableListOf<File>()
        val traced=object:FfmpegBridge by bridge {
            override suspend fun prepare(source:Source,trim:Trim,settings:Settings,input:String,output:String):List<String> = bridge.prepare(source,trim,settings,input,output).also { report.put("arguments",org.json.JSONArray(it)) }
            override suspend fun probe(localPath:String):Source = bridge.probe(localPath).also { report.put("lastProbe",JSONObject().put("path",localPath).put("durationMs",it.durationMs).put("audioFacts",it.audioStreams.toString())) }
        }
        try {
            writeFloatWav(input,FloatArray(480000) { (.08*sin(2*PI*440*it/48000)).toFloat() },48000)
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",input).toString()
            val source=bridge.probe(input.path).copy(uri=uri)
            val peakSettings=Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,stereo=true,
                audioEdit=AudioEdit(output=AudioOutputPolicy(normalization=NormalizationPolicy(mode=NormalizationMode.PEAK))))
            val spec=JobSpec(UUID.randomUUID().toString(),source,Trim(endMs=200),peakSettings)
            val states=mutableListOf<JobState>()
            FfmpegTranscoder(files,traced).run(spec,{states+=it},{})
            val output=files.output(spec);artifacts+=output
            check(states.last()==JobState.COMPLETED && output.isFile)
            val samples=readFloatWav(output);val peak=20*log10(samples.maxOf { abs(it.toDouble()) })
            check(abs(peak+1)<.1) { "Default stereo peak $peak" }
            check(samples.size==19200) { "Short stereo PCM length ${samples.size}" }
            val capped=spec.copy(id=UUID.randomUUID().toString(),settings=peakSettings.copy(audioEdit=peakSettings.audioEdit.copy(output=peakSettings.audioEdit.output.copy(maxBytes=100))))
            val capStates=mutableListOf<JobState>()
            check(runCatching { FfmpegTranscoder(files,traced).run(capped,{capStates+=it},{}) }.exceptionOrNull()?.message?.contains("byte limit")==true)
            check(!files.output(capped).exists() && JobState.COMPLETED !in capStates)
            val cancelled=spec.copy(id=UUID.randomUUID().toString())
            try { FfmpegTranscoder(files,traced).run(cancelled,{if(it==JobState.VERIFYING)throw CancellationException("Test verification cancellation")},{}) ;error("Cancellation was lost") }
            catch(_:CancellationException) { check(!files.output(cancelled).exists()) }
            val scanEntered=CompletableDeferred<File>()
            val nativeFinished=CompletableDeferred<Unit>()
            val scanning=object:FfmpegBridge by bridge {
                override suspend fun inspectStreams(localPath:String,countFrames:Boolean):OutputFacts {
                    if (!countFrames) return bridge.inspectStreams(localPath,false)
                    scanEntered.complete(File(localPath))
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { nativeFinished.await();check(File(localPath).isFile) }
                    }
                }
            }
            val stopInScan=spec.copy(id=UUID.randomUUID().toString())
            val scanningJob=launch { FfmpegTranscoder(files,scanning).run(stopInScan,{},{}) }
            try {
                val candidate=withTimeout(10_000) { scanEntered.await() }
                scanningJob.cancel();yield()
                check(candidate.isFile && !scanningJob.isCompleted && !files.output(stopInScan).exists())
                nativeFinished.complete(Unit)
                withTimeout(10_000) { scanningJob.join() }
                check(scanningJob.isCancelled && !candidate.exists() && !files.output(stopInScan).exists())
            } finally { nativeFinished.complete(Unit);scanningJob.cancelAndJoin() }
            report.put("phase","loudness")
            val loud=spec.copy(id=UUID.randomUUID().toString(),trim=Trim(),settings=peakSettings.copy(container=Container.M4A,audio=AudioEncoder.AAC,
                audioEdit=AudioEdit(output=AudioOutputPolicy(normalization=NormalizationPolicy(mode=NormalizationMode.LOUDNESS)))))
            FfmpegTranscoder(files,traced).run(loud,{},{})
            artifacts+=files.output(loud);check(files.output(loud).isFile)
            report.put("passed",true).put("peakDb",peak).put("shortStereoFrames",samples.size/2)
                .put("verificationScanCancellationLeftNoOutput",true).put("defaultStereoLoudnessExport",true)
        } catch(e:Throwable) { report.put("passed",false).put("error",e.toString());throw e }
        finally { artifacts.forEach { it.delete() };sourceDir.deleteRecursively();File(context.filesDir,"native-readiness").apply { mkdirs() }.resolve("audio-jobs.json").writeText(report.toString(2)) }
    }
}
