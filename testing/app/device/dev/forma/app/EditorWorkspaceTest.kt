package dev.forma.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.image.ImageFixtures
import dev.forma.core.image.ImageEditDocument
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import dev.forma.app.ui.*
import dev.forma.app.video.VideoFrameState
import dev.forma.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EditorWorkspaceTest {
    @get:Rule val compose=createComposeRule()
    private val source=Source("content://video/selected","Selected.mp4",10_000,640,360,1)
    private val edit=SourceEdit(source)

    @Test fun editButtonOpensFocusedVideoPreviewAndClearControls() {
        val actions=mutableListOf<UiAction>()
        var tool by mutableStateOf("Crop")
        compose.setContent { FormaTheme { FormaWorkspace(TranscodeUiState(ready=true,sources=listOf(edit),selectedUri=source.uri,videoTool=tool),
            emptyList(),dev.forma.app.work.RunState(),onAction={if(it is UiAction.VideoTool)tool=it.tool else actions+=it},progressContent={}) } }
        compose.onNodeWithText("Edit video").performClick()
        compose.onNodeWithTag("video-preview").assertExists()
        compose.onNodeWithText("Rotate").performClick()
        compose.onNodeWithText("Rotate right").performClick()
        compose.runOnIdle {
            val changed=actions.filterIsInstance<UiAction.ChangeVideoEdit>().last()
            assertEquals(QuarterTurn.CLOCKWISE,changed.edit.effects.rotation)
            assertTrue(changed.commit)
        }
    }

    @Test fun editImageOpensCanvasWithoutSecondEditTap() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val folder=File(app.filesDir,"imports/focused-image-${UUID.randomUUID()}")
        try {
            val (file,info)=ImageFixtures.png(app,folder)
            val image=ImageFixtures.source(app,file,info)
            val source=Source(image.uri,file.name,0,info.width,info.height,0,
                imageInfo=info)
            val selected=SourceEdit(source)
            val ui=TranscodeUiState(ready=true,sources=listOf(selected),selectedUri=source.uri,
                imageDocuments=mapOf(source.uri to ImageEditDocument(source=image)))
            compose.setContent {FormaTheme {FormaWorkspace(ui,emptyList(),dev.forma.app.work.RunState(),
                onAction={},progressContent={})}}
            compose.onAllNodesWithText("Edit image")[0].performClick()
            compose.onNodeWithTag("image-canvas").assertExists()
        } finally {folder.deleteRecursively()}
    }

    @Test fun cachedFrameShowsCropAndOneUndoableCommitPerGesture() {
        val frame=VideoFrameState.Ready(Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888),0,
            source.uri,0,"Quick frame",true)
        val actions=mutableListOf<UiAction>()
        var current by mutableStateOf(edit)
        val waiting=AtomicLong(0)
        val latencyMs=mutableListOf<Double>()
        compose.setContent { FormaTheme {
            SideEffect {val began=waiting.getAndSet(0);if(began>0)latencyMs+=(SystemClock.elapsedRealtimeNanos()-began)/1_000_000.0}
            VideoEditorWorkspace(current,TranscodeUiState(ready=true,sources=listOf(current),selectedUri=source.uri),
                frame,onAction={actions+=it;if(it is UiAction.ChangeVideoEdit) {
                    if(!it.commit)waiting.set(SystemClock.elapsedRealtimeNanos())
                    current=it.edit
                }},onBack={})
        } }
        compose.onNodeWithTag("video-preview").assertExists()
        compose.onNodeWithText("Quick frame").assertExists()
        compose.onNodeWithText("Crop").performClick()
        compose.onNodeWithTag("video-preview").performTouchInput {
            val fit=minOf(width/640f,height/360f)
            val corner=Offset((width-640*fit)/2,(height-360*fit)/2)
            swipe(start=corner,end=corner+Offset(80f,80f),durationMillis=400)
        }
        compose.runOnIdle {
            assertEquals(1,actions.count {it is UiAction.ChangeVideoEdit && it.commit})
            assertNotNull("actions=$actions",current.effects.crop)
            assertTrue(current.effects.crop!!.width<640)
        }
        repeat(11) {
            compose.onNodeWithTag("video-preview").performTouchInput {
                val fit=minOf(width/640f,height/360f)
                val crop=current.effects.crop ?: CropRect(0,0,640,360)
                val corner=Offset((width-640*fit)/2+crop.x*fit,(height-360*fit)/2+crop.y*fit)
                swipe(start=corner,end=corner+Offset(25f,25f),durationMillis=120)
            }
            compose.waitForIdle()
        }
        compose.runOnIdle {
            assertEquals(12,actions.count {it is UiAction.ChangeVideoEdit && it.commit})
            assertTrue("Too few visible crop updates were recorded: $latencyMs",latencyMs.size>=10)
            val p95=latencyMs.sorted()[(latencyMs.size*0.95).toInt().coerceAtMost(latencyMs.lastIndex)]
            Log.i("FormaGestureLatency","video crop state-to-compose p95Ms=$p95 samples=${latencyMs.size}")
            if(InstrumentationRegistry.getArguments().getString("formaPerformance")=="true")
                assertTrue("Crop state-to-compose p95 $p95 ms exceeds 150 ms",p95<=150.0)
        }
    }

    @Test fun backWaitsForDurableDraftResultBeforeLeaving() {
        var ui by mutableStateOf(TranscodeUiState(ready=true,sources=listOf(edit),selectedUri=source.uri,
            videoDraftDirty=true))
        var backs=0
        val actions=mutableListOf<UiAction>()
        compose.setContent {FormaTheme {VideoEditorWorkspace(edit,ui,VideoFrameState.Idle,
            onAction={actions+=it},onBack={backs++})}}
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Save draft").performClick()
        compose.runOnIdle {assertEquals(0,backs);assertTrue(actions.contains(UiAction.SaveVideoDraft))}
        compose.runOnIdle {ui=ui.copy(videoDraftBusy=true)}
        compose.onNodeWithText("Finishing draft change…").assertExists()
        compose.runOnIdle {ui=ui.copy(videoDraftBusy=false,videoDraftExitSerial=1,videoDraftExitResult="saved",videoDraftDirty=false)}
        compose.waitUntil(5_000) {backs==1}
    }

    @Test fun oddSizedRotatedVideoCropHandleRemainsDraggable() {
        val odd=Source("content://video/odd","odd.mp4",10_000,641,481,1)
        var current by mutableStateOf(SourceEdit(odd,effects=ClipEffects(
            crop=CropRect(0,0,640,480),rotation=QuarterTurn.CLOCKWISE)))
        val frame=VideoFrameState.Ready(Bitmap.createBitmap(481,641,Bitmap.Config.ARGB_8888),0,
            odd.uri,0,"Quick frame",true)
        compose.setContent {FormaTheme {VideoEditorWorkspace(current,
            TranscodeUiState(ready=true,sources=listOf(current),selectedUri=odd.uri),frame,
            onAction={if(it is UiAction.ChangeVideoEdit)current=it.edit},onBack={})}}
        compose.onNodeWithTag("video-preview").performTouchInput {
            val fit=minOf(width/481f,height/641f)
            val corner=Offset((width-481*fit)/2+fit,(height-641*fit)/2)
            swipe(start=corner,end=corner+Offset(45f,30f),durationMillis=350)
        }
        compose.runOnIdle {assertTrue("Crop handle did not move",current.effects.crop!=CropRect(0,0,640,480))}
    }

    @Test fun advancedTrimDragCommitsOnlyOnceOnRelease() {
        var current by mutableStateOf(edit)
        val actions=mutableListOf<UiAction.ChangeTrim>()
        compose.setContent {FormaTheme {AdvancedTrimControls(current) {action ->
            if(action is UiAction.ChangeTrim) {actions+=action;current=current.copy(trim=action.trim)}
        }}}
        compose.onNodeWithText("Trim selected file").performClick()
        compose.onNodeWithTag("advanced-trim-range").performTouchInput {
            swipe(start=Offset(12f,height/2f),end=Offset(width/3f,height/2f),durationMillis=400)
        }
        compose.runOnIdle {
            assertTrue("No provisional trim samples",actions.any {!it.commit})
            assertEquals("One drag must create one undo command",1,actions.count {it.commit})
            assertTrue(current.trim.startMs>0)
        }
    }
}
