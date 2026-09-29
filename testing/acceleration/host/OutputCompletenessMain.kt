package dev.forma.ffmpeg

import dev.forma.core.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/** Real desktop media through the production stream reader/verifier; separate from Android qualification. */
private class DesktopCompletenessBridge:FfmpegBridge {
    fun command(vararg args:String):String {
        val p=ProcessBuilder(*args).redirectErrorStream(true).start()
        val text=p.inputStream.bufferedReader().readText();check(p.waitFor()==0){text};return text
    }
    private fun fields(path:String,count:Boolean):List<Map<String,String>> {
        val argv=mutableListOf("ffprobe","-v","error","-show_entries",
            "stream=codec_type,width,height,pix_fmt,color_transfer,bits_per_raw_sample,start_time,start_pts,time_base,duration,duration_ts,nb_read_frames,sample_rate:stream_tags=DURATION:format=start_time,duration","-of","compact=p=0")
        if(count) argv+="-count_frames"
        argv+=path
        return command(*argv.toTypedArray()).lineSequence().filter { it.isNotBlank() }.map { line ->
            line.split('|').associate { it.substringBefore('=') to it.substringAfter('=') }
        }.toList()
    }
    override suspend fun capabilities()=Capabilities(true)
    override suspend fun probe(localPath:String):Source {
        val values=fields(localPath,false);val stream=values.filter { "codec_type" in it };val video=stream.firstOrNull { it["codec_type"]=="video" }
        val format=values.first { "codec_type" !in it }
        return Source(localPath,File(localPath).name,(format.getValue("duration").toDouble()*1000).toLong(),
            video?.get("width")?.toInt() ?: 0,video?.get("height")?.toInt() ?: 0,stream.count { it["codec_type"]=="video" },stream.count { it["codec_type"]=="audio" })
    }
    override suspend fun inspectStreams(localPath:String,countFrames:Boolean):OutputFacts {
        val rows=fields(localPath,countFrames)
        return OutputFactsReader.read(rows.first { "codec_type" !in it }["start_time"],rows.filter { "codec_type" in it })
    }
    override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult {
        val p=ProcessBuilder(listOf("ffmpeg")+arguments).redirectErrorStream(true).start();val log=p.inputStream.bufferedReader().readText()
        return NativeResult(p.waitFor(),log)
    }
}
fun main(args:Array<String>)=runBlocking {
    val dir=File(args.single()).apply { check(mkdirs()) };val bridge=DesktopCompletenessBridge()
    fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
    fun fixture(name:String,vSeconds:String="5",aSeconds:String="5",videoDelay:Boolean=false,audioDelay:Boolean=false,drop:Boolean=false):File {
        val out=File(dir,"$name.mp4")
        val argv=mutableListOf("ffmpeg","-hide_banner","-v","error","-nostdin","-n")
        if(videoDelay) argv+=listOf("-itsoffset","0.3")
        argv+=listOf("-f","lavfi","-i","testsrc2=size=320x240:rate=30:duration=$vSeconds")
        if(audioDelay) argv+=listOf("-itsoffset","0.3")
        argv+=listOf("-f","lavfi","-i","sine=frequency=440:sample_rate=48000:duration=$aSeconds")
        if(drop) argv+=listOf("-vf","select=not(eq(n\\,40))")
        argv+=listOf("-c:v","libx264","-threads:v","2","-pix_fmt","yuv420p","-fps_mode","passthrough","-c:a","aac",out.path)
        bridge.command(*argv.toTypedArray());return out
    }
    val original=fixture("original");val originalHash=hash(original)
    val settings=Settings(fps=30,maxHeight=240)
    suspend fun checkOutput(name:String,input:File,output:File,trim:Trim=Trim(),s:Settings=settings,reject:Boolean=false) {
        val source=bridge.probe(input.path)
        val attempt=PreparedAttempt(listOf("-i",input.path,output.path),EncodeDecision(EncodeBackend.SOFTWARE,"libx264",reason="Desktop fixture"))
        val failure=runCatching { verifyEncodedOutput(bridge,source,trim,s,output,attempt) }.exceptionOrNull()
        if(reject) check(failure is EncodedOutputRejected){"$name: shortened/shifted output was accepted: $failure"}
        else check(failure==null){"$name: $failure"}
        println("PASS desktop completeness/$name ${if(reject) "rejected" else "accepted"}")
    }
    checkOutput("short-video",original,fixture("short-video",vSeconds="1"),reject=true)
    checkOutput("short-audio",original,fixture("short-audio",aSeconds="1"),reject=true)
    checkOutput("missing-frame",original,fixture("missing-frame",drop=true),reject=true)
    for((name,trim) in listOf("whole" to Trim(),"fractional-trim" to Trim(1017,4119))) {
        val output=File(dir,"$name.mp4");bridge.command(*(listOf("ffmpeg")+Planner.arguments(bridge.probe(original.path),trim,settings,original.path,output.path)).toTypedArray())
        checkOutput(name,original,output,trim)
    }
    checkOutput("source-rate",original,original,s=settings.copy(fps=0))
    val delayedAudio=fixture("delayed-audio",aSeconds="4.7",audioDelay=true)
    checkOutput("delayed-audio",delayedAudio,delayedAudio)
    checkOutput("lost-audio-offset",delayedAudio,original,reject=true)
    val delayedVideo=fixture("delayed-video",vSeconds="4.7",videoDelay=true)
    checkOutput("delayed-video",delayedVideo,delayedVideo)
    checkOutput("lost-video-offset",delayedVideo,original,reject=true)
    val mkv=File(dir,"whole.mkv");bridge.command("ffmpeg","-v","error","-nostdin","-n","-i",original.path,"-c","copy",mkv.path)
    checkOutput("matroska-clock",original,mkv)
    check(hash(original)==originalHash)
    println("Original SHA-256 retained: $originalHash")
}
