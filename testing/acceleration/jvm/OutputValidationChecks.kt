package dev.forma.ffmpeg

import dev.forma.core.*
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*

object OutputValidationChecks {
    fun run() = runBlocking {
        val source = Source("original", "original", 3000, 640, 360, 1, 1)
        val settings = Settings(video = VideoEncoder.H264_HW, rateControl = RateControl.BITRATE,
            videoKbps = 1000, maxHeight = 360, fps = 30)
        val r = EncodeRequest(VideoFormat.H264, 640, 360, 30.0, 1_000_000)
        val d = EncodeDecision(EncodeBackend.MEDIACODEC, r.format.device, "vendor", BufferFormat.NV12,
            "test", configuration = r)
        var outputSource = source
        fun facts(s:Source)=OutputFacts(0,listOf(StreamFacts(StreamKind.VIDEO,0,s.durationMs*1000,90,s.width,s.height,s.hdr),
            StreamFacts(StreamKind.AUDIO,0,s.durationMs*1000,sampleRate=48000)).filter { if(it.kind==StreamKind.VIDEO) s.videoTracks>0 else s.audioTracks>0 })
        var exit = NativeResult(0, "")
        var decodeArguments = emptyList<String>()
        val bridge = object : FfmpegBridge {
            override suspend fun capabilities() = Capabilities()
            override suspend fun probe(localPath: String) = outputSource
            override suspend fun inspectStreams(localPath:String,countFrames:Boolean)=facts(if(countFrames) outputSource else source)
            override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult {
                decodeArguments = arguments; return exit
            }
        }
        suspend fun verify() = verifyEncodedOutput(bridge, source, Trim(), settings,
            File("/private/encoded.mp4"), PreparedAttempt(listOf("-i","/private/original.mp4","/private/encoded.mp4"), d))
        verify()
        check("-xerror" in decodeArguments && decodeArguments[decodeArguments.indexOf("-i") + 1] == "/private/encoded.mp4")
        for (bad in listOf(source.copy(width = 320), source.copy(height = 362), source.copy(audioTracks = 0),
                source.copy(videoTracks = 0), source.copy(durationMs = 2000), source.copy(hdr = true))) {
            outputSource = bad
            check(runCatching { verify() }.exceptionOrNull() is EncodedOutputRejected)
        }
        outputSource = source.copy(durationMs = 2800)
        check(runCatching { verify() }.exceptionOrNull() is EncodedOutputRejected) // Container tolerance cannot hide shortened streams.
        outputSource = source
        exit = NativeResult(1, "invalid encoded data", FailureKind.INVALID_INPUT)
        check(runCatching { verify() }.exceptionOrNull() is EncodedOutputRejected)
        exit = NativeResult(1, "disk", FailureKind.IO)
        check(runCatching { verify() }.exceptionOrNull() is IOException)
        exit = NativeResult(1, "stop", FailureKind.CANCELLED)
        check(runCatching { verify() }.exceptionOrNull() is CancellationException)
        exit = NativeResult(1, "unknown", FailureKind.UNKNOWN)
        val unknown = runCatching { verify() }.exceptionOrNull()
        check(unknown != null && unknown !is EncodedOutputRejected)
        println("Encoded output layout, geometry, duration, color and decode-failure checks passed")
    }
}
fun main() = OutputValidationChecks.run()
