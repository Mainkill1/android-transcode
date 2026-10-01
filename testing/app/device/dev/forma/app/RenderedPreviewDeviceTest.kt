package dev.forma.app

import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.MediaFiles
import dev.forma.app.video.*
import dev.forma.app.work.RunCoordinator
import dev.forma.core.*
import dev.forma.ffmpeg.*
import java.io.File
import java.util.UUID
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
}
