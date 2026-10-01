package dev.forma.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.VideoTimeline
import dev.forma.core.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

class VideoTimelineTest {
    @get:Rule val compose=createComposeRule()
    private val source=Source("content://timeline/video","long.mp4",10_800_000,640,360,1)

    @Test fun filmstripSeekAndZoomKeepIndependentTrimAndMillisecondTimes() {
        val initial=Trim(3_600_000,3_605_000)
        var seek=initial.startMs
        val trims=mutableListOf<Trim>()
        compose.setContent {FormaTheme {VideoTimeline(source,initial,seek,
            onSeek={seek=it},onTrim={trims+=it},loadFrames={_,times->List(times.size){null}})}}
        compose.onNodeWithText("Zoom to selection").performClick()
        compose.onNodeWithText("Start 1:00:00.000",substring=true).assertExists()
        compose.onNodeWithText("End 1:00:05.000",substring=true).assertExists()
        compose.onNodeWithTag("video-timeline-filmstrip").performTouchInput {click(Offset(width/2f,height/2f))}
        compose.runOnIdle {assertTrue(seek in initial.startMs..initial.endMs!!);assertTrue(trims.isEmpty())}
        compose.onNodeWithText("Fit timeline").performClick()
        compose.onNodeWithText("Exact times").performClick()
        compose.onNodeWithText("Start (ms)").assertExists()
        compose.onNodeWithText("End (ms)").assertExists()
        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("3600001")
        compose.onNodeWithText("Apply times").performClick()
        compose.runOnIdle {assertEquals(3_600_001L,trims.single().startMs)}
    }

    @Test fun separatedBracketsCommitOneTrimPerDrag() {
        val short=source.copy(durationMs=10_000)
        var trim=Trim(1_000,8_000)
        val changes=mutableListOf<Trim>()
        compose.setContent {FormaTheme {VideoTimeline(short,trim,1_000,
            onSeek={},onTrim={changes+=it;trim=it},loadFrames={_,times->List(times.size){null}})}}
        compose.onNodeWithTag("timeline-start-handle").assertIsDisplayed().performTouchInput {
            swipe(start=center,end=center+Offset(70f,0f),durationMillis=350)
        }
        compose.runOnIdle {assertEquals(1,changes.size);assertTrue(changes.single().startMs>1_000)}
        compose.onNodeWithTag("timeline-end-handle").assertIsDisplayed()
    }

    @Test fun pinchZoomsTheVisibleWindowAndFitRestoresIt() {
        val short=source.copy(durationMs=10_000)
        compose.setContent {FormaTheme {VideoTimeline(short,Trim(),0,
            onSeek={},onTrim={},loadFrames={_,times->List(times.size){null}})}}
        compose.onNodeWithText("Window 0:00:00.000 – 0:00:10.000").assertExists()
        compose.onNodeWithTag("video-timeline-filmstrip").performTouchInput {
            down(0,Offset(width*.30f,height/2f))
            down(1,Offset(width*.70f,height/2f))
            moveTo(0,Offset(width*.10f,height/2f))
            moveTo(1,Offset(width*.90f,height/2f))
            up(0);up(1)
        }
        compose.onNodeWithText("Window 0:00:00.000 – 0:00:10.000").assertDoesNotExist()
        compose.onNodeWithText("Fit timeline").performClick()
        compose.onNodeWithText("Window 0:00:00.000 – 0:00:10.000").assertExists()
    }

    @Test fun visibleWindowLoadsRealBoundedFilmstripFramesOnNativeDevice() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FormaApplication
        val bridge=app.graph.bridge
        assumeTrue("Native fixture requires arm64-v8a FFmpeg",Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        assumeTrue("Native media fixture unavailable",bridge.capabilities().available)
        val fixture=File(app.filesDir,"imports/timeline-${UUID.randomUUID()}.mp4")
        fixture.parentFile!!.mkdirs()
        try {
            val generated=bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-n",
                "-f","lavfi","-i","testsrc2=s=160x90:r=24:d=2","-c:v","libx264",
                "-pix_fmt","yuv420p",fixture.path)) {}
            assertEquals(generated.diagnostics,0,generated.exitCode)
            val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",fixture)
            val media=bridge.probe(fixture.path).copy(uri=uri.toString(),name=fixture.name)
            compose.setContent {FormaTheme {VideoTimeline(media,Trim(),0,onSeek={},onTrim={})}}
            compose.waitUntil(15_000) {compose.onAllNodesWithContentDescription("Video filmstrip, 7 frames").fetchSemanticsNodes().isNotEmpty()}
        } finally {fixture.delete()}
    }
}
