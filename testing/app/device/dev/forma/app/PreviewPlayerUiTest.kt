package dev.forma.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.VideoPreviewPlayer
import dev.forma.app.video.VideoRenderState
import dev.forma.core.Trim
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

class PreviewPlayerUiTest {
    @get:Rule val compose=createComposeRule()

    @Test fun renderedVideoPlaysInsideEditorAndCanPause() {
        assumeTrue("Run native fixture explicitly",InstrumentationRegistry.getArguments().getString("formaNative")=="true")
        runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.cacheDir,"player-test-${UUID.randomUUID()}.mp4")
        try {
            val bridge=createFfmpegBridge()
            assumeTrue("Rendered player fixture requires FFmpeg", bridge.capabilities().available)
            val encoded=bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-n",
                "-f","lavfi","-i","testsrc2=s=160x90:r=24:d=2","-c:v","libx264","-pix_fmt","yuv420p",file.path)) {}
            assertEquals(encoded.diagnostics,0,encoded.exitCode)
            compose.setContent {FormaTheme {VideoPreviewPlayer(VideoRenderState.Ready(file,"test",1,Trim(0,2_000)))}}
            compose.onNodeWithTag("rendered-video-preview").assertExists()
            compose.waitUntil(15_000) {compose.onAllNodesWithText("Pause preview").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Pause preview").performClick()
            compose.onNodeWithText("Play preview").assertExists()
        } finally {file.delete()}
        }
    }
}
