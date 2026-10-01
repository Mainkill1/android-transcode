package dev.forma.app

import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.MediaFiles
import dev.forma.app.video.*
import dev.forma.app.work.RunCoordinator
import dev.forma.core.*
import dev.forma.ffmpeg.*
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RenderedPreviewDeviceTest {
    private class FakeBridge(private val entered:CompletableDeferred<Unit>?=null,
        private val finish:CompletableDeferred<Unit>?=null): FfmpegBridge {
        var calls=0
        var args:List<String> = emptyList()
        override suspend fun capabilities()=Capabilities(available=true,encoders=setOf("libx264"),
            muxers=setOf("mp4"),filters=setOf("scale","fps"))
        override suspend fun probe(localPath:String)=Source("file://$localPath","rendered.mp4",5_000,640,360,1)
        override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult {
            calls++;args=arguments
            entered?.complete(Unit)
            finish?.await()
            File(arguments.last()).writeBytes(ByteArray(256){1})
            return NativeResult(0,"")
        }
    }
    @Test fun cancellationLeavesNoPrivatePreviewOrStagedInput() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val sourceFile=File(app.cacheDir,"cancel-preview-${UUID.randomUUID()}.mp4").apply {writeBytes(ByteArray(512){2})}
        val source=Source(sourceFile.toURI().toString(),"sample.mp4",20_000,640,360,1,bytes=sourceFile.length())
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val bridge=FakeBridge(entered,finish)
        val controller=RenderedPreviewController(app,MediaFiles(app,bridge),bridge,RunCoordinator(scope),scope)
        try {
            controller.request(SourceEdit(source),Settings(),10_000,1)
            withTimeout(10_000) {entered.await()}
            controller.invalidate()
            controller.cancelAndJoin()
            assertTrue(controller.state.value is VideoRenderState.Idle)
            assertTrue(File(app.cacheDir,"video-preview").listFiles().orEmpty().isEmpty())
            assertFalse(bridge.args.last().let(::File).exists())
        } finally {finish.complete(Unit);sourceFile.delete();scope.cancel()}
    }
    @Test fun previewIsPrivateBoundedAndWaitsForConversionSlot() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val sourceFile=File(app.cacheDir,"preview-source-${UUID.randomUUID()}.mp4").apply {writeBytes(ByteArray(512){2})}
        val source=Source(sourceFile.toURI().toString(),"sample.mp4",20_000,640,360,1,bytes=sourceFile.length())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val runs=RunCoordinator(scope)
        val bridge=FakeBridge()
        val controller=RenderedPreviewController(app,MediaFiles(app,bridge),bridge,runs,scope)
        val gate=CompletableDeferred<Unit>()
        val ticket=runs.start { gate.await() }!!
        try {
            controller.request(SourceEdit(source),Settings(),10_000,1)
            withTimeout(5_000) {controller.state.first {it is VideoRenderState.Waiting}}
            assertEquals(0,bridge.calls)
            gate.complete(Unit);ticket.job.join()
            val ready=withTimeout(10_000) {controller.state.first {it is VideoRenderState.Ready}} as VideoRenderState.Ready
            assertEquals(Trim(7_500,12_500),ready.window)
            assertTrue(ready.file.path.contains("video-preview"))
            assertTrue(ready.file.exists())
            assertTrue(bridge.args.contains("-t"))
            controller.invalidate()
            withTimeout(5_000) {while(ready.file.exists())delay(20)}
            assertTrue(controller.state.value is VideoRenderState.Stale)
            controller.cancelAndJoin()
            assertFalse(ready.file.exists())
        } finally {gate.complete(Unit);sourceFile.delete();scope.cancel()}
    }

    @Test fun foregroundConversionPreemptsPreviewAndWaitsForItsCleanup() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val sourceFile=File(app.cacheDir,"preempt-preview-${UUID.randomUUID()}.mp4").apply {writeBytes(ByteArray(512){2})}
        val source=Source(sourceFile.toURI().toString(),"sample.mp4",20_000,640,360,1,bytes=sourceFile.length())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val runs=RunCoordinator(scope)
        val entered=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val bridge=FakeBridge(entered,finish)
        val controller=RenderedPreviewController(app,MediaFiles(app,bridge),bridge,runs,scope)
        val conversionStarted=CompletableDeferred<Unit>()
        try {
            controller.request(SourceEdit(source),Settings(),10_000,1)
            withTimeout(10_000) {entered.await()}
            val ticket=runs.start {conversionStarted.complete(Unit)}!!
            withTimeout(10_000) {controller.state.first {it is VideoRenderState.Stale}}
            withTimeout(10_000) {conversionStarted.await();ticket.job.join()}
            assertTrue(File(app.cacheDir,"video-preview").listFiles().orEmpty().isEmpty())
            assertFalse(bridge.args.last().let(::File).exists())
        } finally {finish.complete(Unit);controller.cancelAndJoin();sourceFile.delete();scope.cancel()}
    }

    @Test fun nativeRenderedCropRotationMatchesExportGeometry() = runBlocking {
        assumeTrue("Run native fixture explicitly",InstrumentationRegistry.getArguments().getString("formaNative")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val sourceFile=File(app.cacheDir,"native-preview-${UUID.randomUUID()}.mp4")
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val bridge=ManagedFfmpegBridge(createFfmpegBridge())
        try {
            assumeTrue("Native crop preview requires FFmpeg", bridge.capabilities().available)
            val generated=bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-n",
                "-f","lavfi","-i","color=red:s=160x90:r=24:d=2",
                "-vf","drawbox=x=80:y=0:w=80:h=90:color=green:t=fill",
                "-c:v","libx264","-pix_fmt","yuv420p",sourceFile.path)) {}
            assertEquals(generated.diagnostics,0,generated.exitCode)
            val source=bridge.probe(sourceFile.path).copy(uri=sourceFile.toURI().toString(),name="sample.mp4",bytes=sourceFile.length())
            val controller=RenderedPreviewController(app,MediaFiles(app,bridge),bridge,RunCoordinator(scope),scope)
            val edit=SourceEdit(source,effects=ClipEffects(crop=CropRect(20,10,80,60),rotation=QuarterTurn.CLOCKWISE))
            controller.request(edit,Settings(),1_000,1)
            val ready=withTimeout(120_000) {controller.state.first {it is VideoRenderState.Ready || it is VideoRenderState.Error}}
            if(ready is VideoRenderState.Error)fail(ready.message)
            ready as VideoRenderState.Ready
            val actual=bridge.probe(ready.file.path)
            assertEquals(60,actual.width)
            assertEquals(80,actual.height)
            assertTrue(actual.durationMs in 1..2_500)
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(ready.file.path)
                val bitmap=checkNotNull(retriever.getFrameAtTime(500_000))
                assertEquals(60,bitmap.width);assertEquals(80,bitmap.height)
                val red=bitmap.getPixel(30,10);val green=bitmap.getPixel(30,70)
                assertTrue("Rotated top should come from the cropped red side",Color.red(red)>Color.green(red)*2)
                assertTrue("Rotated bottom should come from the cropped green side",Color.green(green)>Color.red(green)*2)
            } finally {retriever.release()}
            controller.cancelAndJoin()
        } finally {sourceFile.delete();scope.cancel()}
    }

    @Test fun boundedMovieWindowMatchesFullRenderAcrossDissolve() = runBlocking {
        assumeTrue("Run native fixture explicitly",InstrumentationRegistry.getArguments().getString("formaNative")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(app.filesDir,"imports/movie-window-${UUID.randomUUID()}").apply {mkdirs()}
        val red=File(folder,"red.mp4");val blue=File(folder,"blue.mp4")
        val full=File(folder,"full.mp4")
        val bridge=ManagedFfmpegBridge(createFfmpegBridge())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val controller=RenderedPreviewController(app,MediaFiles(app,bridge),bridge,RunCoordinator(scope),scope)
        try {
            for((file,color) in listOf(red to "red",blue to "blue")) {
                val encoded=bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-n",
                    "-f","lavfi","-i","color=$color:s=160x90:r=30:d=6",
                    "-c:v","libx264","-pix_fmt","yuv420p",file.path)) {}
                assertEquals(encoded.diagnostics,0,encoded.exitCode)
            }
            suspend fun imported(file:File)=bridge.probe(file.path).copy(uri=FileProvider.getUriForFile(app,
                "${app.packageName}.files",file).toString(),name=file.name,bytes=file.length())
            val first=imported(red);val second=imported(blue)
            val hashBefore=listOf(red,blue).map(::digest)
            val movie=MovieProject(sequence=SequenceSpec(EditTimeline(listOf(
                TimelineClip("red",first),TimelineClip("blue",second))),CanvasSpec(160,90,30),1_000),
                settings=Settings(audio=AudioEncoder.NONE),targetBytes=null)
            val fullArgs=bridge.prepareSequence(movie.sequence,movie.settings,listOf(red.path,blue.path),full.path)
            val fullResult=bridge.execute(fullArgs) {}
            assertEquals(fullResult.diagnostics,0,fullResult.exitCode)
            val stagedBefore=File(app.filesDir,"work").listFiles().orEmpty().map {it.name}.toSet()
            controller.requestSequence(movie,5_500,1)
            val state=withTimeout(120_000) {controller.state.first {it is VideoRenderState.Ready || it is VideoRenderState.Error}}
            if(state is VideoRenderState.Error)fail(state.message)
            val ready=state as VideoRenderState.Ready
            assertEquals(RenderedPreviewController.MOVIE_KEY,ready.sourceKey)
            assertEquals(movie,ready.movie)
            assertEquals(Trim(3_000,8_000),ready.window)
            assertEquals("A short preview must not copy whole source files into work storage",stagedBefore,
                File(app.filesDir,"work").listFiles().orEmpty().map {it.name}.toSet())
            val facts=bridge.probe(ready.file.path)
            assertTrue("Unexpected preview duration ${facts.durationMs}",facts.durationMs in 4_800..5_200)
            for((local,global) in listOf(1_500L to 4_500L,2_500L to 5_500L,3_500L to 6_500L)) {
                val observed=centerPixel(ready.file,local)
                val expected=centerPixel(full,global)
                for(channel in 0..2)assertTrue("Pixel mismatch at $local ms: ${observed.toList()} vs ${expected.toList()}",
                    kotlin.math.abs(observed[channel]-expected[channel])<=35)
            }
            val strict=bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-xerror",
                "-i",ready.file.path,"-map","0:v:0","-f","null","-")) {}
            assertEquals(strict.diagnostics,0,strict.exitCode)
            assertEquals(hashBefore,listOf(red,blue).map(::digest))
        } finally {controller.cancelAndJoin();folder.deleteRecursively();scope.cancel()}
    }

    private fun digest(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
    private fun centerPixel(file:File,timeMs:Long):IntArray {
        val retriever=MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val bitmap=checkNotNull(retriever.getFrameAtTime(timeMs*1_000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
            val color=bitmap.getPixel(bitmap.width/2,bitmap.height/2)
            return intArrayOf(Color.red(color),Color.green(color),Color.blue(color))
        } finally {retriever.release()}
    }
}
