package dev.forma.ffmpeg

import dev.forma.core.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ByteCapExportTest {
    private val source = Source("content://original", "original", 3000, 320, 240, 1, 1)
    private fun facts()=OutputFacts(0,listOf(StreamFacts(StreamKind.VIDEO,0,3_000_000,90,320,240),
        StreamFacts(StreamKind.AUDIO,0,3_000_000,sampleRate=48000)))
    private val settings = Settings(video = VideoEncoder.X264, fps = 30, maxHeight = 240)
    private inner class Bridge(val directory: File, val sizes: List<Int>) : FfmpegBridge {
        val prepared = mutableListOf<Pair<String, Settings>>()
        var exports = 0
        var decodeFailure = false
        override suspend fun inspectStreams(localPath:String,countFrames:Boolean)=facts()
        override suspend fun capabilities() = Capabilities(true)
        override suspend fun probe(localPath: String) = Source(localPath, "output", 3000, 320, 240, 1, 1)
        override suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> {
            prepared += input to settings
            return listOf("-n", "-i", input, "-c:v", "libx264", output)
        }
        override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult {
            if (arguments.last() == "-") return NativeResult(if (decodeFailure) 1 else 0, "", if (decodeFailure) FailureKind.INVALID_INPUT else FailureKind.UNKNOWN)
            val count = sizes[minOf(exports, sizes.lastIndex)]; exports++
            File(arguments.last()).writeBytes(ByteArray(count))
            return NativeResult(0, "")
        }
    }
    @Test fun oversizedVerifiedCandidateRereadsOriginalAndReducesBudget() = runBlocking {
        val dir = Files.createTempDirectory("cap-export-").toFile()
        try {
            val input = File(dir,"original").apply { writeText("source") }; val output = File(dir,"output.mp4")
            val bridge = Bridge(dir, listOf(100000, 99999))
            ByteCapExport.run(bridge, source, Trim(), settings, input, output, 100000)
            assertEquals(2, bridge.exports); assertEquals(99999L, output.length())
            assertEquals(listOf(input.path,input.path), bridge.prepared.map { it.first })
            assertTrue(bridge.prepared[1].second.videoKbps < bridge.prepared[0].second.videoKbps)
            assertEquals(settings, settings.copy()); assertEquals("source",input.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun exhaustedLimitAndFailedDecodeNeverLeaveAnArtifact() = runBlocking {
        val dir = Files.createTempDirectory("cap-reject-").toFile()
        try {
            val input = File(dir,"original").apply { writeText("source") };val output = File(dir,"output.mp4")
            val oversize = Bridge(dir,listOf(100001))
            assertTrue(runCatching { ByteCapExport.run(oversize,source,Trim(),settings,input,output,100000) }.isFailure)
            assertEquals(4,oversize.exports);assertFalse(output.exists())
            val invalid = Bridge(dir,listOf(40000)).apply { decodeFailure = true }
            assertTrue(runCatching { ByteCapExport.run(invalid,source,Trim(),settings,input,output,100000) }.isFailure)
            assertEquals(1,invalid.exports);assertFalse(output.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun cappedAutomaticRouteReservesItsSoftwareFallbackWithinFourAttempts() = runBlocking {
        val dir=Files.createTempDirectory("cap-auto-").toFile()
        try {
            val input=File(dir,"original").apply { writeText("source") };val output=File(dir,"output.mp4")
            val request=EncodeRequest(VideoFormat.H264,320,240,30.0,100000)
            val candidate=CodecCandidate("vendor",request.format,true,Support.YES,Support.YES,null,"",request)
            val routes=CodecTrials.plan(request,AccelerationMode.AUTO,setOf("h264_mediacodec","libx264"),listOf(candidate))
            var calls=0
            val bridge=object:FfmpegBridge {
                override suspend fun inspectStreams(localPath:String,countFrames:Boolean)=facts()
                override suspend fun capabilities()=Capabilities(true)
                override suspend fun probe(localPath:String)=source
                override suspend fun prepareAttempts(source:Source,trim:Trim,settings:Settings,input:String,output:String)=
                    routes.map { PreparedAttempt(listOf("-n","-i",input,"-c:v",it.encoder!!,output),it) }
                override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult {
                    if(arguments.last()=="-") return NativeResult(0,"")
                    calls++
                    if("h264_mediacodec" in arguments) return NativeResult(1,"codec init",FailureKind.CODEC_INITIALIZATION)
                    File(arguments.last()).writeBytes(ByteArray(40000));return NativeResult(0,"")
                }
            }
            val selected=ByteCapExport.run(bridge,source,Trim(),settings.copy(video=VideoEncoder.H264_AUTO),input,output,100000)
            assertEquals(EncodeBackend.SOFTWARE,selected.decision?.backend);assertEquals(4,calls)
        } finally { dir.deleteRecursively() }
    }
    @Test fun automaticOversizeAtHardwareMinimumTriesSoftwareButRequiredHardwareDoesNot() = runBlocking {
        val dir=Files.createTempDirectory("cap-floor-").toFile()
        try {
            val input=File(dir,"original").apply { writeText("source") };val output=File(dir,"output.mp4")
            val request=EncodeRequest(VideoFormat.H264,320,240,30.0,100000)
            val candidate=CodecCandidate("vendor",request.format,true,Support.YES,Support.YES,null,"",request)
            suspend fun run(mode: AccelerationMode, choice: VideoEncoder): Pair<PreparedAttempt,Int> {
                val routes=CodecTrials.plan(request,mode,setOf("h264_mediacodec","libx264"),listOf(candidate))
                var calls=0
                val bridge=object:FfmpegBridge {
                    override suspend fun inspectStreams(localPath:String,countFrames:Boolean)=facts()
                override suspend fun capabilities()=Capabilities(true)
                    override suspend fun probe(localPath:String)=source
                    override suspend fun prepareAttempts(source:Source,trim:Trim,settings:Settings,input:String,output:String)=
                        routes.map { PreparedAttempt(listOf("-n","-i",input,"-c:v",it.encoder!!,output),it) }
                    override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult {
                        if(arguments.last()=="-") return NativeResult(0,"")
                        calls++
                        File(arguments.last()).writeBytes(ByteArray(if("h264_mediacodec" in arguments) 400000 else 40000))
                        return NativeResult(0,"")
                    }
                }
                return ByteCapExport.run(bridge,source,Trim(),settings.copy(video=choice),input,output,100000) to calls
            }
            val result=run(AccelerationMode.AUTO,VideoEncoder.H264_AUTO)
            assertEquals(EncodeBackend.SOFTWARE,result.first.decision?.backend)
            assertTrue(result.second in 2..4);assertEquals(40000L,output.length());output.delete()
            assertTrue(runCatching { run(AccelerationMode.HARDWARE_REQUIRED,VideoEncoder.H264_HW) }.isFailure)
            assertFalse(output.exists());assertEquals("source",input.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun cancellationAtVerifiedCallbackDoesNotLeaveAnOutput() = runBlocking {
        val dir = Files.createTempDirectory("cap-cancel-").toFile()
        try {
            val input = File(dir,"original").apply { writeText("source") };val output = File(dir,"output.mp4")
            val bridge = Bridge(dir,listOf(40000));val worker=launch {
                ByteCapExport.run(bridge,source,Trim(),settings,input,output,100000,onAttempt={ if(it.status==AttemptStatus.VERIFIED) cancel() })
            };worker.join();assertTrue(worker.isCancelled);assertFalse(output.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun editedAudioDurationAndEveryAttemptRetainGraphHooks()=runBlocking {
        val dir=File(System.getProperty("java.io.tmpdir"),"cap-audio-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val input=File(dir,"original").apply { writeText("source") };val output=File(dir,"output.m4a")
            val audioSource=source.copy(videoTracks=0,durationMs=3000)
            val requested=Settings(container=Container.M4A,audioKbps=512,audioEdit=dev.forma.core.audio.AudioEdit(rate=dev.forma.core.audio.AudioRate(2,1)))
            val bridge=Bridge(dir,listOf(100000,99999));var transformed=0;var verified=0
            ByteCapExport.run(bridge,audioSource,Trim(),requested,input,output,100000,
                transformAttempt={ _,attempt -> transformed++;attempt.copy(arguments=attempt.arguments.dropLast(1)+listOf("-af","volume=0.5",output.path)) },
                verifyAttempt={ budget,attempt -> verified++;assertEquals(requested.audioEdit,budget.audioEdit);assertTrue("volume=0.5" in attempt.arguments) })
            assertEquals(2,transformed);assertEquals(2,verified)
            assertEquals(UploadFit.initial(requested,1500,true,100000).audioKbps,bridge.prepared.first().second.audioKbps)
            assertEquals(listOf(input.path,input.path),bridge.prepared.map { it.first })
            assertEquals(requested.audioEdit,bridge.prepared.last().second.audioEdit)
        }finally {dir.deleteRecursively()}
    }

    @Test fun cappedPcmAndFlacKeepTheirPolicyAndNeverAttemptBitrateRetries()=runBlocking {
        val dir=File(System.getProperty("java.io.tmpdir"),"cap-lossless-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val input=File(dir,"original").apply {writeText("source")};val output=File(dir,"output")
            for((container,encoder) in listOf(Container.WAV to AudioEncoder.PCM_S16LE,Container.FLAC to AudioEncoder.FLAC)) {
                val requested=Settings(container=container,audio=encoder)
                assertEquals(requested,UploadFit.initial(requested,3000,true,100000))
                assertNull(UploadFit.retry(requested,true,100000,100001))
                for(size in listOf(99999,100001)) {
                    val bridge=Bridge(dir,listOf(size))
                    val result=runCatching { ByteCapExport.run(bridge,source.copy(videoTracks=0),Trim(),requested,input,output,100000,verifyAttempt={ _,_-> }) }
                    assertEquals(size<100000,result.isSuccess);assertEquals(1,bridge.exports)
                    assertEquals(requested,bridge.prepared.single().second)
                    assertEquals(size<100000,output.exists());output.delete()
                }
            }
        }finally {dir.deleteRecursively()}
    }

}
