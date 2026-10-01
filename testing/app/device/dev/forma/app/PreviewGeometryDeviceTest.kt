package dev.forma.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.*
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PreviewGeometryDeviceTest {
    @Test fun rotatedMetadataCropMatchesExportPixels() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaNative")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(app.cacheDir,"geometry-${UUID.randomUUID()}").apply {mkdirs()}
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
