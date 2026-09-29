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
    private class Bridge(var input:OutputFacts,var output:OutputFacts):FfmpegBridge {
        override suspend fun capabilities()=Capabilities(true)
        override suspend fun probe(localPath:String)=error("Container summaries cannot establish completeness")
        override suspend fun inspectStreams(localPath:String,countFrames:Boolean)=if(countFrames) output else input
        override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit)=NativeResult(0,"")
    }
    private suspend fun verify(bridge:Bridge,trim:Trim=Trim(),fps:Int=30)=verifyEncodedOutput(bridge,source,trim,Settings(fps=fps,maxHeight=240),
        File("encoded.mp4"),PreparedAttempt(listOf("-i","original","encoded.mp4"),EncodeDecision(EncodeBackend.SOFTWARE,"libx264",reason="test")))
    @Test fun aLongAudioTrackCannotHideShortenedVideo():Unit=runBlocking {
        val full=OutputFacts(0,listOf(video(),audio()))
        val bridge=Bridge(full,OutputFacts(0,listOf(video(1_000_000,30),audio())))
        assertTrue(runCatching { verify(bridge) }.exceptionOrNull() is EncodedOutputRejected)
    }
    @Test fun longVideoCannotHideShortAudioAndLostFramesCannotHideBehindCorrectClocks():Unit=runBlocking {
        val full=OutputFacts(0,listOf(video(),audio()))
        for(bad in listOf(OutputFacts(0,listOf(video(),audio(1_000_000))),OutputFacts(0,listOf(video(frames=149),audio())))) {
            assertTrue(runCatching { verify(Bridge(full,bad)) }.exceptionOrNull() is EncodedOutputRejected)
        }
        verify(Bridge(full,full))
    }
    @Test fun commonTrimOriginRetainsAudioOffsetAndEachOriginalStreamEnd():Unit=runBlocking {
        val delayed=OutputFacts(0,listOf(video(),audio(4_700_000,300_000)))
        verify(Bridge(delayed,delayed))
        assertTrue(runCatching { verify(Bridge(delayed,OutputFacts(0,listOf(video(),audio())))) }.exceptionOrNull() is EncodedOutputRejected)
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
        }.exceptionOrNull() is EncodedOutputRejected)
    }
    @Test fun sourceRateSoftwareAlsoRejectsUnknownAndShortenedStreams():Unit=runBlocking {
        val full=OutputFacts(0,listOf(video(),audio()))
        verify(Bridge(full,full),fps=0)
        assertTrue(runCatching { verify(Bridge(full,OutputFacts(0,listOf(video(1_000_000,30),audio()))),fps=0) }.exceptionOrNull() is EncodedOutputRejected)
        assertTrue(runCatching { verify(Bridge(full,OutputFacts(0,listOf(video().copy(decodedFrames=null),audio())))) }.exceptionOrNull() is EncodedOutputRejected)
        assertTrue(runCatching { verify(Bridge(full,OutputFacts(0,listOf(video().copy(durationUs=null),audio())))) }.exceptionOrNull() is EncodedOutputRejected)
        assertTrue(runCatching { verify(Bridge(full.copy(originUs=null),full)) }.isFailure)
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
}
