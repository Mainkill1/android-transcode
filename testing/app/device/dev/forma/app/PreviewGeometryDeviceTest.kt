package dev.forma.app

import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import dev.forma.app.video.VideoFrameController
import dev.forma.app.video.VideoFrameState
import dev.forma.core.*
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PreviewGeometryDeviceTest {
    @Test fun rotatedMetadataCropMatchesExportPixels() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaNative")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(app.filesDir,"imports/geometry-${UUID.randomUUID()}").apply {mkdirs()}
        val raw=File(directory,"raw.mp4")
        val rotated=File(directory,"rotated.mp4")
        val output=File(directory,"crop.mp4")
        val bridge=createFfmpegBridge()
        try {
            val generated=bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-y","-f","lavfi",
                "-i","testsrc2=s=160x90:r=24:d=2","-c:v","libx264","-pix_fmt","yuv420p",raw.path)) {}
            assertEquals(generated.diagnostics,0,generated.exitCode)
            val tagged=bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-y",
                "-display_rotation","90","-i",raw.path,"-c","copy",rotated.path)) {}
            assertEquals(tagged.diagnostics,0,tagged.exitCode)
            val source=bridge.probe(rotated.path)
            assertEquals(90,source.displayRotationDegrees)
            assertEquals(90 to 160,PreviewGeometry.displaySize(source.width,source.height,source.displayRotationDegrees))
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val frames=VideoFrameController(app,scope)
            try {
                val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",rotated)
                frames.request(source.copy(uri=uri.toString()),250,1)
                val quick=withTimeout(10_000) {frames.state.first {it is VideoFrameState.Ready || it is VideoFrameState.Error}}
                assertTrue(quick.toString(),quick is VideoFrameState.Ready)
            } finally {frames.closeAndJoin();scope.cancel()}
            val effects=ClipEffects(crop=CropRect(20,20,40,100))
            val settings=Settings(maxHeight=0,effects=effects)
            val args=bridge.prepare(source,Trim(0,1000),settings,rotated.path,output.path)
            val exported=bridge.execute(args) {}
            assertEquals(exported.diagnostics,0,exported.exitCode)
            val facts=bridge.probe(output.path)
            assertEquals(40,facts.width)
            assertEquals(100,facts.height)
        } finally {directory.deleteRecursively()}
    }
}
