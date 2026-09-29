package dev.forma.testing

import dev.forma.core.*
import dev.forma.core.audio.SourceAudioFacts
import dev.forma.ffmpeg.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/** Desktop qualification invokes the production session. It does not claim Android codec qualification. */
private class DesktopMovieBridge : FfmpegBridge {
    fun command(vararg arguments: String): String {
        val process=ProcessBuilder(*arguments).redirectErrorStream(true).start()
        val text=process.inputStream.bufferedReader().readText()
        check(process.waitFor()==0) { text.takeLast(6000) }
        return text
    }
    override suspend fun capabilities() = Capabilities(true,"Desktop FFmpeg fixture profile",
        FfmpegListing.encoders(command("ffmpeg","-hide_banner","-encoders")),
        FfmpegListing.muxers(command("ffmpeg","-hide_banner","-muxers")),
        FfmpegListing.filters(command("ffmpeg","-hide_banner","-filters")),command("ffmpeg","-version").lineSequence().first())
    override suspend fun probe(localPath: String): Source {
        val facts=command("ffprobe","-v","error","-show_entries","format=duration:stream=index,codec_type,width,height,sample_rate,channels,channel_layout,sample_fmt,duration,start_time,codec_name","-of","compact=p=0",localPath).lineSequence().toList()
        val videos=facts.filter { it.contains("codec_type=video") }
        val video=videos.firstOrNull()?.split('|')?.associate { it.substringBefore('=') to it.substringAfter('=') }.orEmpty()
        val firstVideoStart=video["start_time"]?.toDoubleOrNull() ?: 0.0
        val audio=facts.filter { it.contains("codec_type=audio") }.map { row ->
            val fields=row.split('|').associate { it.substringBefore('=') to it.substringAfter('=') }
            SourceAudioFacts(streamIndex=fields["index"]?.toIntOrNull() ?: 0,
                sampleRateHz=fields["sample_rate"]?.toIntOrNull(),channels=fields["channels"]?.toIntOrNull(),
                channelLayout=fields["channel_layout"],sampleFormat=fields["sample_fmt"],
                durationUs=((fields["duration"]?.toDoubleOrNull() ?: 0.0)*1_000_000).toLong(),
                codec=fields["codec_name"],timelineOffsetUs=fields["start_time"]?.toDoubleOrNull()?.let { ((it-firstVideoStart)*1_000_000).toLong() })
        }
        val duration=facts.first { it.startsWith("duration=") }.substringAfter('=').toDouble()
        return Source(localPath,File(localPath).name,(duration*1000).toLong(),video["width"]?.toInt()?:0,video["height"]?.toInt()?:0,
            videos.size,audio.size,bytes=File(localPath).length(),audioStreams=audio)
    }
    override suspend fun inspectStreams(localPath: String, countFrames: Boolean): OutputFacts {
        val rows=command(*(listOf("ffprobe","-v","error") + (if(countFrames) listOf("-count_frames") else emptyList()) +
            listOf("-show_entries","format=start_time:stream=index,codec_type,start_time,start_pts,duration,duration_ts,time_base,nb_read_frames,width,height,pix_fmt,color_transfer,bits_per_raw_sample,sample_rate:stream_tags=DURATION","-of","compact=p=0",localPath)).toTypedArray()).lineSequence().filter { it.isNotBlank() }.toList()
        fun fields(row:String)=row.split('|').associate { it.substringBefore('=') to it.substringAfter('=') }
        val origin=rows.lastOrNull { !it.contains("codec_type=") }?.let { fields(it)["start_time"] }
        val streams=rows.filter { it.contains("codec_type=") }.map(::fields)
        val first=OutputFactsReader.read(origin,streams)
        val measured=streams.mapIndexed { i,stream ->
            if(first.streams[i].kind!=StreamKind.OTHER && first.streams[i].startUs==null) {
                val packet=command("ffprobe","-v","error","-select_streams",requireNotNull(stream["index"]),"-read_intervals","%+#1",
                    "-show_packets","-show_entries","packet=pts,pts_time","-of","compact=p=0",localPath).lineSequence().firstOrNull { "pts=" in it }?.let(::fields).orEmpty()
                stream + mapOf("observed_start_pts" to packet["pts"].orEmpty(),"observed_start_time" to packet["pts_time"].orEmpty())
            } else stream
        }
        return OutputFactsReader.read(origin,measured)
    }
    override suspend fun execute(arguments: List<String>,onProgress:(Progress)->Unit): NativeResult {
        val process=ProcessBuilder(listOf("ffmpeg")+arguments).redirectErrorStream(true).start()
        val diagnostics=process.inputStream.bufferedReader().readText()
        return NativeResult(process.waitFor(),diagnostics.takeLast(6000))
    }
}
private fun hash(file: File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
fun main(args: Array<String>)=runBlocking {
    val directory=File(args.single()).apply { check(isDirectory || mkdirs()) }
    val desktop=DesktopMovieBridge();val bridge=ManagedFfmpegBridge(desktop,processors=4)
    val a=File(directory,"source-a.mp4");val b=File(directory,"source-b.mp4")
    desktop.command("ffmpeg","-v","error","-nostdin","-n","-f","lavfi","-i","color=red:size=640x360:rate=30:duration=3",
        "-itsoffset","0.3","-f","lavfi","-i","sine=frequency=440:sample_rate=48000:duration=3",
        "-c:v","libx264","-threads:v","2","-pix_fmt","yuv420p","-c:a","aac",a.path)
    desktop.command("ffmpeg","-v","error","-nostdin","-n","-f","lavfi","-i","color=blue:size=360x640:rate=25:duration=3",
        "-c:v","libx264","-threads:v","2","-pix_fmt","yuv420p","-an",b.path)
    val c=File(directory,"source-video-delay.mp4")
    desktop.command("ffmpeg","-v","error","-nostdin","-n","-itsoffset","0.3","-f","lavfi","-i","color=red:size=640x360:rate=30:duration=3",
        "-f","lavfi","-i","sine=frequency=440:sample_rate=48000:duration=3",
        "-c:v","libx264","-threads:v","2","-pix_fmt","yuv420p","-fps_mode","passthrough","-c:a","aac",c.path)
    val wav=File(directory,"source-clockless.wav")
    desktop.command("ffmpeg","-v","error","-nostdin","-n","-f","lavfi","-i","sine=sample_rate=44100:duration=3","-c:a","pcm_s16le",wav.path)
    val wh=hash(wav);val sw=bridge.probe(wav.path)
    val ah=hash(a);val bh=hash(b);val ch=hash(c);val sa=bridge.probe(a.path);val sb=bridge.probe(b.path);val sc=bridge.probe(c.path)
    fun project(speed: Int=100,transition: Long=0,audio: Boolean=false,cap: Long?=null)=MovieProject(
        sequence=SequenceSpec(EditTimeline(listOf(TimelineClip("a",sa,Trim(0,2000),Settings(effects=ClipEffects(speedPercent=speed))),
            TimelineClip("b",sb,Trim(0,2000)))),CanvasSpec(320,180,30),transition),
        settings=Settings(container=if(audio) Container.M4A else Container.MP4),targetBytes=cap)
    val cases=listOf("movie-cut" to project(),"movie-crossfade" to project(transition=500),"movie-speed" to project(speed=200),
        "movie-audio" to project(audio=true),
        "movie-webm" to project().copy(settings=Settings(container=Container.WEBM,video=VideoEncoder.VP9,audio=AudioEncoder.OPUS)),
        "movie-mkv" to project().copy(settings=Settings(container=Container.MKV)),
        "movie-wav-silent" to MovieProject(sequence=SequenceSpec(EditTimeline(listOf(
            TimelineClip("wav",sw,Trim(500,2000),Settings(container=Container.M4A)),TimelineClip("silent",sb,Trim(0,2000)))),CanvasSpec(320,180,30)),
            settings=Settings(container=Container.M4A),targetBytes=null),"movie-budget" to project(cap=150000),"movie-preview" to project(),
        "movie-video-delay" to project().copy(sequence=project().sequence.copy(timeline=EditTimeline(listOf(TimelineClip("a",sc,Trim(0,2000)),TimelineClip("b",sb,Trim(0,2000)))))))
    val rows=mutableListOf<String>()
    for((name,doc) in cases) {
        val job=if(name=="movie-preview") doc.previewJob(name) else doc.toJob(name)
        val output=File(directory,"$name.${job.settings.container.extension}")
        val attempts=mutableListOf<RenderAttempt>()
        val facts=FfmpegRenderSession(bridge).render(job,requireNotNull(job.sequence).timeline.clips.map { File(it.source.uri) },output,{},attempts::add)
        check(kotlin.math.abs(facts.durationMs-JobPlans.duration(job))<=100)
        var frames=0L
        if(job.settings.container!=Container.M4A) {
            frames=desktop.command("ffprobe","-v","error","-count_frames","-select_streams","v:0","-show_entries","stream=nb_read_frames","-of","csv=p=0",output.path).trim().toLong()
            val sequence=requireNotNull(job.sequence)
            check(frames==SequencePlanner.frames(sequence).sum()-SequencePlanner.overlapFrames(sequence)*(sequence.timeline.clips.size-1)) { "Wrong final frame count" }
        }
        job.targetBytes?.let { check(UploadFit.fits(output.length(),it)) }
        check(hash(a)==ah && hash(b)==bh && hash(c)==ch && hash(wav)==wh)
        attempts.forEach { attempt -> File(directory,"$name-${attempt.index}.argv").writeText(attempt.arguments.joinToString("\u0000")) }
        rows += listOf(name,output.name,facts.durationMs,frames,output.length(),attempts.size,hash(File(requireNotNull(job.sequence).timeline.clips.first().source.uri)),bh).joinToString("\t")
        println("PASS desktop production session/$name: ${facts.durationMs} ms, $frames frames, ${output.length()} bytes")
    }
    File(directory,"movies.tsv").writeText(rows.joinToString("\n"))
}
