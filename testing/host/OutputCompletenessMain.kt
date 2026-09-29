package dev.forma.ffmpeg

import dev.forma.core.*
import dev.forma.core.audio.*
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
            "stream=index,codec_type,width,height,pix_fmt,color_transfer,bits_per_raw_sample,start_time,start_pts,time_base,duration,duration_ts,nb_read_frames,sample_rate,channels,sample_fmt,codec_name:stream_tags=DURATION:format=start_time,duration","-of","compact=p=0")
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
            video?.get("width")?.toInt() ?: 0,video?.get("height")?.toInt() ?: 0,stream.count { it["codec_type"]=="video" },stream.count { it["codec_type"]=="audio" },audioStreams=stream.filter { it["codec_type"]=="audio" }.map { a ->
                val rate=a["sample_rate"]?.toIntOrNull();val ticks=a["duration_ts"]?.toLongOrNull();val base=a["time_base"].orEmpty().split('/')
                val duration=OutputFactsReader.read("0",listOf(a)).streams.single().durationUs ?: (format.getValue("duration").toDouble()*1000000).toLong()
                val total=if((a["codec_name"].orEmpty().startsWith("pcm_") || a["codec_name"]=="flac") && ticks!=null && rate!=null && base.size==2)
                    AudioGraphPlanner.roundRatio(ticks,base[0].toLong()*rate,base[1].toLong()) else null
                SourceAudioFacts(a.getValue("index").toInt(),rate,a["channels"]?.toIntOrNull(),sampleFormat=a["sample_fmt"],durationUs=duration,totalSamples=total)
            })
    }
    override suspend fun inspectStreams(localPath:String,countFrames:Boolean):OutputFacts {
        val rows=fields(localPath,countFrames)
        val origin=rows.first { "codec_type" !in it }["start_time"]
        val streams=rows.filter { "codec_type" in it }
        val first=OutputFactsReader.read(origin,streams)
        val observed=streams.mapIndexed { index,s ->
            if(first.streams[index].kind==StreamKind.OTHER || first.streams[index].startUs!=null) s else {
                val packet=command("ffprobe","-v","error","-select_streams",s.getValue("index"),"-read_intervals","%+#1",
                    "-show_packets","-show_entries","packet=pts,pts_time","-of","compact=p=0",localPath).lineSequence().first { it.isNotBlank() }
                    .split('|').associate { it.substringBefore('=') to it.substringAfter('=') }
                s+mapOf("observed_start_pts" to packet["pts"].orEmpty(),"observed_start_time" to packet["pts_time"].orEmpty())
            }
        }
        return OutputFactsReader.read(origin,observed)
    }
    override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult {
        val p=ProcessBuilder(listOf("ffmpeg")+arguments).redirectErrorStream(true).start();val log=p.inputStream.bufferedReader().readText()
        return NativeResult(p.waitFor(),log)
    }
}
fun main(args:Array<String>)=runBlocking {
    val dir=File(args.first()).apply { check(mkdirs()) };val bridge=DesktopCompletenessBridge()
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
        val failure=runCatching { val decoded=bridge.execute(listOf("-v","error","-xerror","-i",output.path,"-map","0:v?","-map","0:a?","-f","null","-")) {}
            check(decoded.exitCode==0) { decoded.diagnostics }
            verifyOutputStreams(bridge,source,trim,s,input.path,output.path) }.exceptionOrNull()
        if(reject) check(failure is OutputCompletenessRejected){"$name: shortened/shifted output was accepted: $failure"}
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
    val wave=File(dir,"clockless.wav")
    if(args.size>1) File(args[1]).copyTo(wave) else bridge.command("ffmpeg","-v","error","-nostdin","-n","-f","lavfi","-i","sine=frequency=330:sample_rate=44100:duration=5.12345","-c:a","pcm_s16le",wave.path)
    val waveHash=hash(wave)
    val lossless=File(dir,"clockless.flac");bridge.command("ffmpeg","-v","error","-nostdin","-n","-i",wave.path,"-c:a","flac",lossless.path)
    val audioSettings=settings.copy(container=Container.M4A,audio=AudioEncoder.AAC)
    val audioTrim=Trim(1007,4111)
    for(input in listOf(wave,lossless)) {
        val output=File(dir,"trim-${input.extension}.m4a")
        bridge.command(*(listOf("ffmpeg")+Planner.arguments(bridge.probe(input.path),audioTrim,audioSettings,input.path,output.path)).toTypedArray())
        checkOutput("${input.extension}-trim-m4a",input,output,audioTrim,audioSettings)
    }
    val flacTrim=File(dir,"trimmed.flac")
    bridge.command("ffmpeg","-v","error","-nostdin","-n","-i",wave.path,"-ss","1.007","-t","3.104","-c:a","flac",flacTrim.path)
    // Actual FLAC product output and sample-domain trimming.
    checkOutput("wav-trim-flac-stream",wave,flacTrim,audioTrim,audioSettings.copy(container=Container.FLAC,audio=AudioEncoder.FLAC))
    check(hash(wave)==waveHash);println("WAV SHA-256 retained: $waveHash")
    check(hash(original)==originalHash)
    println("Original SHA-256 retained: $originalHash")
}
