package dev.forma.app.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.ffmpeg.*
import dev.forma.ffmpeg.audio.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class AudioAnalysisDeviceTest {
    @Test fun twoPassAndSilentMeasurements():Unit=runBlocking(Dispatchers.IO) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"analysis-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val report=JSONObject()
        try {
            val bridge=ManagedFfmpegBridge(createFfmpegBridge());val caps=bridge.capabilities();check(caps.available)
            val analyzer=AudioAnalyzer(bridge)
            val input=File(dir,"program.wav")
            writeFloatWav(input,FloatArray(480000) { ((.03*sin(2*PI*440*it/48000) + .015*sin(2*PI*1300*it/48000))*(.6+.4*sin(2*PI*it/48000/3).pow(2))).toFloat() },48000)
            val source=bridge.probe(input.path)
            val policy=NormalizationPolicy(mode=NormalizationMode.LOUDNESS,integratedLufs=-16.0,truePeakDb=-1.5)
            val settings=Settings(container=Container.M4A,stereo=false,audioEdit=AudioEdit(output=AudioOutputPolicy(normalization=policy)))
            val identity=AudioAnalysisIdentity.create(AudioAnalysisIdentity.fingerprint(input),caps.build,source,Trim(),settings)
            val measured=analyzer.analyze(AudioAnalysisRequest(identity,input,source,Trim(),settings))
            check(measured.identity==identity)
            val values=(measured.measurement as AudioMeasurementResult.Measured).values
            val output=File(dir,"normalized.m4a")
            val args=AudioAnalyzer.appendFilter(bridge.prepare(source,Trim(),settings,input.path,output.path),values.normalizationFilter(policy),48000)
            val result=bridge.execute(args) {};check(result.exitCode==0) { result.diagnostics }
            val delivered=bridge.probe(output.path)
            val final=analyzer.analyze(AudioAnalysisRequest("final",output,delivered,Trim(),Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,stereo=false)))
            val actual=(final.measurement as AudioMeasurementResult.Measured).values
            check(abs(actual.integratedLufs+16)<=.5) { "Delivered LUFS ${actual.integratedLufs}" }
            check(actual.truePeakDb<=-1.4) { "Delivered true peak ${actual.truePeakDb}" }
            val silence=File(dir,"silence.wav");writeFloatWav(silence,FloatArray(48000),48000)
            check(analyzer.analyze(AudioAnalysisRequest("silent",silence,bridge.probe(silence.path),Trim(),settings)).measurement is AudioMeasurementResult.NotMeasurable)
            // Cancellation must join native completion before the shared bridge accepts another task.
            val task=launch { bridge.execute(listOf("-re","-f","lavfi","-i","sine=sample_rate=48000","-t","30","-f","null","-")) {} }
            delay(200);task.cancelAndJoin()
            check(bridge.probe(input.path).audioTracks==1)
            report.put("passed",true).put("build",caps.build).put("integratedLufs",actual.integratedLufs).put("truePeakDb",actual.truePeakDb)
        } catch(e:Throwable) { report.put("passed",false).put("error",e.toString());throw e }
        finally { File(context.filesDir,"native-readiness").apply { mkdirs() }.resolve("audio-analysis.json").writeText(report.toString(2));dir.deleteRecursively() }
    }
}
