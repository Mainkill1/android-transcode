package dev.forma.ffmpeg

import dev.forma.core.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OutputCompletenessTest {
    private val source=Source("original","original",5000,320,240,1,1)
    private fun video(duration:Long=5_000_000,frames:Long=150,start:Long=0)=StreamFacts(StreamKind.VIDEO,start,duration,frames,320,240)
    private fun audio(duration:Long=5_000_000,start:Long=0)=StreamFacts(StreamKind.AUDIO,start,duration,sampleRate=48000)
    @Test fun main10OutputIsNotMistakenForQualifiedEightBitSdr() {
        val stream=mapOf("codec_type" to "video", "start_time" to "0", "duration" to "5",
            "width" to "320", "height" to "240", "pix_fmt" to "yuv420p10le",
            "color_transfer" to "bt709", "bits_per_raw_sample" to "10")
        assertTrue(OutputFactsReader.read("0",listOf(stream)).streams.single().hdr)
    }
    private class Bridge(var input:OutputFacts,var output:OutputFacts):FfmpegBridge {
        override suspend fun capabilities()=Capabilities(true)
        override suspend fun probe(localPath:String)=error("Container summaries cannot establish completeness")
        override suspend fun inspectStreams(localPath:String,countFrames:Boolean)=if(countFrames) output else input
        override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit)=NativeResult(0,"")
    }
    private suspend fun verify(bridge:Bridge,trim:Trim=Trim(),fps:Int=30)=verifyOutputStreams(bridge,source,trim,Settings(fps=fps,maxHeight=240),"original","encoded.mp4")
    @Test fun aLongAudioTrackCannotHideShortenedVideo():Unit=runBlocking {
        val full=OutputFacts(0,listOf(video(),audio()))
        val bridge=Bridge(full,OutputFacts(0,listOf(video(1_000_000,30),audio())))
        assertTrue(runCatching { verify(bridge) }.exceptionOrNull() is OutputCompletenessRejected)
    }
    @Test fun longVideoCannotHideShortAudioAndLostFramesCannotHideBehindCorrectClocks():Unit=runBlocking {
        val full=OutputFacts(0,listOf(video(),audio()))
        for(bad in listOf(OutputFacts(0,listOf(video(),audio(1_000_000))),OutputFacts(0,listOf(video(frames=149),audio())))) {
            assertTrue(runCatching { verify(Bridge(full,bad)) }.exceptionOrNull() is OutputCompletenessRejected)
        }
        verify(Bridge(full,full))
    }
    @Test fun commonTrimOriginRetainsAudioOffsetAndEachOriginalStreamEnd():Unit=runBlocking {
        val delayed=OutputFacts(0,listOf(video(),audio(4_700_000,300_000)))
        verify(Bridge(delayed,delayed))
        assertTrue(runCatching { verify(Bridge(delayed,OutputFacts(0,listOf(video(),audio())))) }.exceptionOrNull() is OutputCompletenessRejected)
        val unequal=OutputFacts(0,listOf(video(4_000_000,120),audio()))
        verify(Bridge(unequal,unequal)) // The original really has less video; its audio does not require invented pictures.
        val trimmed=OutputFacts(0,listOf(video(3_000_000,90),audio(3_000_000)))
        verify(Bridge(delayed,trimmed),Trim(1000,4000))
    }
    @Test fun fractionalFrameWindowAllowsOnlyItsFloorOrCeiling():Unit=runBlocking {
        val full=OutputFacts(0,listOf(video(),audio()))
        for(frames in listOf(93L,94L)) verify(Bridge(full,OutputFacts(0,listOf(video(3_105_000,frames),audio(3_105_000)))),Trim(1000,4105))
        for(frames in listOf(92L,95L)) assertTrue(runCatching {
            verify(Bridge(full,OutputFacts(0,listOf(video(3_105_000,frames),audio(3_105_000)))),Trim(1000,4105))
        }.exceptionOrNull() is OutputCompletenessRejected)
    }
    @Test fun sourceRateSoftwareAlsoRejectsUnknownAndShortenedStreams():Unit=runBlocking {
        val full=OutputFacts(0,listOf(video(),audio()))
        verify(Bridge(full,full),fps=0)
        assertTrue(runCatching { verify(Bridge(full,OutputFacts(0,listOf(video(1_000_000,30),audio()))),fps=0) }.exceptionOrNull() is OutputCompletenessRejected)
        assertTrue(runCatching { verify(Bridge(full,OutputFacts(0,listOf(video().copy(decodedFrames=null),audio())))) }.exceptionOrNull() is OutputCompletenessRejected)
        assertTrue(runCatching { verify(Bridge(full,OutputFacts(0,listOf(video().copy(durationUs=null),audio())))) }.exceptionOrNull() is OutputCompletenessRejected)
        assertTrue(runCatching { verify(Bridge(full.copy(originUs=null),full)) }.isFailure)
    }
    @Test fun processedAudioUsesGraphDurationAndZeroOrigin():Unit=runBlocking {
        val original=OutputFacts(0,listOf(audio()))
        val s=Settings(container=Container.M4A,audioEdit=dev.forma.core.audio.AudioEdit(rate=dev.forma.core.audio.AudioRate(2,1)))
        val audioSource=source.copy(videoTracks=0)
        val output=OutputFacts(0,listOf(audio(2_500_000)))
        verifyOutputStreams(Bridge(original,output),audioSource,Trim(),s,"original","encoded.m4a")
        assertTrue(runCatching { verifyOutputStreams(Bridge(original,original),audioSource,Trim(),s,"original","encoded.m4a") }.isFailure)
    }
    @Test fun attachedPicturesAreNotRetainedVideoTracks() {
        val facts=OutputFactsReader.read("0",listOf(mapOf("codec_type" to "video","attached_pic" to "1"),mapOf("codec_type" to "audio","start_time" to "0","duration" to "5")))
        assertEquals(1,facts.streams.size);assertEquals(StreamKind.AUDIO,facts.streams.single().kind)
    }
    @Test fun clocklessMediaRequiresObservedPresentationClockAndRetainsSampleDuration() {
        val fields=mapOf("codec_type" to "audio","time_base" to "1/44100","duration_ts" to "845568",
            "sample_rate" to "44100","observed_start_pts" to "0","observed_start_time" to "0.000000")
        val measured=OutputFactsReader.read(null,listOf(fields))
        assertEquals(0L,measured.originUs);assertEquals(0L,measured.streams.single().startUs)
        assertEquals(19_173_878L,measured.streams.single().durationUs)
        val absent=OutputFactsReader.read(null,listOf(fields-filterKeysForObservation()))
        assertNull(absent.originUs);assertNull(absent.streams.single().startUs)
        val invalid=OutputFactsReader.read(null,listOf(fields+mapOf("observed_start_pts" to "N/A","observed_start_time" to "NaN")))
        assertNull(invalid.originUs);assertNull(invalid.streams.single().startUs)
        val nonzero=OutputFactsReader.read(null,listOf(fields+mapOf("observed_start_pts" to "44100","observed_start_time" to "1.000000")))
        assertEquals(1_000_000L,nonzero.originUs)
    }
    private fun filterKeysForObservation()=setOf("observed_start_pts","observed_start_time")
    @Test fun ffprobeReaderRetainsRationalClocksAndMatroskaEndsWithoutCoercion() {
        val facts=OutputFactsReader.read("0",listOf(mapOf("codec_type" to "video","start_pts" to "300","duration_ts" to "4700",
            "time_base" to "1/1000","width" to "320","height" to "240","pix_fmt" to "yuv420p","nb_read_frames" to "141")))
        assertEquals(300_000L,facts.streams.single().startUs);assertEquals(4_700_000L,facts.streams.single().durationUs)
        assertEquals(141L,facts.streams.single().decodedFrames)
        val mkv=OutputFactsReader.read("0",listOf(mapOf("codec_type" to "audio","start_time" to "0.3","tag:DURATION" to "00:00:05.000000000","sample_rate" to "48000")))
        assertEquals(4_700_000L,mkv.streams.single().durationUs)
        val unknown=OutputFactsReader.read("N/A",listOf(mapOf("codec_type" to "video","duration" to "N/A","nb_read_frames" to "149.9")))
        assertNull(unknown.originUs);assertNull(unknown.streams.single().durationUs);assertNull(unknown.streams.single().decodedFrames)
    }
    @Test fun runtimeVerifierRetainsProcessedAudioRateAndAudioOnlyFormats():Unit=runBlocking {
        val original=OutputFacts(0,listOf(audio()))
        val audioSource=source.copy(videoTracks=0)
        val settings=Settings(container=Container.M4A,audioEdit=dev.forma.core.audio.AudioEdit(rate=dev.forma.core.audio.AudioRate(2,1)))
        val attempt=PreparedAttempt(listOf("-i","original","encoded.m4a"))
        verifyEncodedOutput(Bridge(original,OutputFacts(0,listOf(audio(2_500_000)))),audioSource,Trim(),settings,File("encoded.m4a"),attempt)
        assertTrue(runCatching { verifyEncodedOutput(Bridge(original,original),audioSource,Trim(),settings,File("encoded.m4a"),attempt) }.exceptionOrNull() is EncodedOutputRejected)
        verifyEncodedOutput(Bridge(original,original),audioSource,Trim(),Settings(container=Container.WAV,audio=AudioEncoder.PCM_S16LE),File("encoded.wav"),attempt)
    }
    @Test fun runtimeVerifierRetainsDeviceGeometryAndTypedDecodeFailures():Unit=runBlocking {
        val full=OutputFacts(0,listOf(video(),audio()))
        val expected=EncodeRequest(VideoFormat.H264,640,480,30.0,1000000)
        val attempt=PreparedAttempt(listOf("-i","original","encoded.mp4"),EncodeDecision(EncodeBackend.MEDIACODEC,"h264_mediacodec",reason="test",configuration=expected))
        assertTrue(runCatching { verifyEncodedOutput(Bridge(full,full),source,Trim(),Settings(fps=30),File("encoded.mp4"),attempt) }.exceptionOrNull() is EncodedOutputRejected)
        val failure=object:FfmpegBridge {
            override suspend fun capabilities()=Capabilities(true)
            override suspend fun probe(localPath:String)=source
            override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit)=NativeResult(1,"storage",FailureKind.IO)
        }
        assertTrue(runCatching { verifyEncodedOutput(failure,source,Trim(),Settings(),File("encoded.mp4"),attempt) }.exceptionOrNull() is java.io.IOException)
    }

    @Test fun movieNormalizationTargetsLabeledAudioWithoutAddingASecondSimpleFilter() {
        val args=listOf("-filter_complex","[0:a:0]atrim=start=1:end=3,volume=0.5[a0];[a0]concat=n=1:v=0:a=1[aout]","-map","[aout]","-c:a","aac","-f","ipod","output")
        val perClip=dev.forma.ffmpeg.audio.AudioAnalyzer.appendClipFilter(args,0,"volume=-6dB",48000)
        val final=dev.forma.ffmpeg.audio.AudioAnalyzer.appendFilter(perClip,"volume=-3dB",44100)
        assertFalse("-af" in final)
        val graph=final[final.indexOf("-filter_complex")+1]
        assertTrue(graph.contains("volume=0.5,volume=-6dB,aresample=48000[a0]"))
        assertTrue(graph.endsWith("[aout]volume=-3dB,aresample=44100[formaNormalizedAudio]"))
        assertEquals("[formaNormalizedAudio]",final[final.indexOf("-map")+1])
    }

}
