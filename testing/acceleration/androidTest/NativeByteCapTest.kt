package dev.forma.app

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.*
import dev.forma.core.*
import dev.forma.ffmpeg.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in downloaded-source qualification through the actual production exporter. */
class NativeByteCapTest {
    @Test fun originalSourceSizeRetryAndCancellation(): Unit = runBlocking(Dispatchers.IO) {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("formaNative")=="true" && args.getString("formaByteCapTests")=="true")
        val id=requireNotNull(args.getString("formaRunId"));require(UUID.fromString(id).toString()==id)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=File(context.filesDir,"outputs/cap-original.mp4");check(original.isFile)
        val before=hash(original);val files=MediaFiles(context);val native=ManagedFfmpegBridge(createFfmpegBridge())
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",original).toString()
        val source=native.probe(original.path).copy(uri=uri)
        val settings=Settings(video=VideoEncoder.H264_AUTO,rateControl=RateControl.BITRATE,fps=30,maxHeight=360)
        val job=JobSpec(UUID.randomUUID().toString(),source,Trim(5000,10000),settings,250000)
        val report=JSONObject().put("runId",id).put("targetBytes",job.targetBytes).put("sourceSha256",before).put("passed",false)
        val states=mutableListOf<JobState>();val prepared=JSONArray();val measured=JSONArray();var encodes=0
        val padded=object:FfmpegBridge by native {
            override suspend fun prepareAttempts(source:Source,trim:Trim,settings:Settings,input:String,output:String):List<PreparedAttempt> =
                native.prepareAttempts(source,trim,settings,input,output).also { prepared.put(JSONObject().put("inputSha256",hash(File(input))).put("bitrate",settings.videoKbps).put("routes",JSONArray(it.map { a -> JSONArray(a.arguments) }))) }
            override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult {
                val result=native.execute(arguments,onProgress)
                if(arguments.last()!="-" && result.exitCode==0) {
                    encodes++
                    // One valid native MP4 is oversized by inert trailing bytes; every retry still encodes the original.
                    val encodedBytes=File(arguments.last()).length()
                    if(encodes==1) File(arguments.last()).appendBytes(ByteArray(250000))
                    measured.put(JSONObject().put("encodedBytes",encodedBytes).put("paddedBytes",File(arguments.last()).length()).put("arguments",JSONArray(arguments)))
                }
                return result
            }
        }
        try {
            FfmpegTranscoder(files,padded).run(job,{states+=it},{})
            val output=files.output(job);check(states.last()==JobState.COMPLETED && output.length() in 1 until 250000)
            check(encodes in 2..4);check(prepared.length()>=2);check(hash(original)==before)
            val cancelled=job.copy(id=UUID.randomUUID().toString())
            try { FfmpegTranscoder(files,native).run(cancelled,{ if(it==JobState.VERIFYING) throw CancellationException("Stop before publication") },{});error("Cancelled job completed") }
            catch(_:CancellationException) { check(!files.output(cancelled).exists()) }
            report.put("passed",true).put("nativeBuild",native.capabilities().build).put("encodes",encodes)
                .put("outputFile",output.name).put("outputBytes",output.length()).put("outputSha256",hash(output))
                .put("prepared",prepared).put("states",JSONArray(states.map { it.name })).put("cancellationLeftNoOutput",true)
        } finally {
            report.put("encodes",encodes).put("prepared",prepared).put("measurements",measured).put("states",JSONArray(states.map { it.name }))
            val dir=File(context.filesDir,"acceleration").apply { mkdirs() };val out=File(dir,"cap-$id.json")
            check(out.createNewFile());out.writeText(report.toString(2))
        }
    }
    private fun hash(file:File):String {
        val md=MessageDigest.getInstance("SHA-256");file.inputStream().use { input -> val block=ByteArray(65536);while(true) { val n=input.read(block);if(n<0)break;md.update(block,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
