package dev.forma.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
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
        var action:UiAction?=null
        var tool by mutableStateOf("Crop")
        compose.setContent { FormaTheme { FormaWorkspace(TranscodeUiState(ready=true,sources=listOf(edit),selectedUri=source.uri,videoTool=tool),
            emptyList(),dev.forma.app.work.RunState(),onAction={if(it is UiAction.VideoTool)tool=it.tool else action=it},progressContent={}) } }
        compose.onNodeWithText("Edit video").performClick()
        compose.onNodeWithTag("video-preview").assertExists()
        compose.onNodeWithText("Rotate").performClick()
        compose.onNodeWithText("Rotate right").performClick()
        compose.runOnIdle {
            val changed=action as UiAction.ChangeVideoEdit
            assertEquals(QuarterTurn.CLOCKWISE,changed.edit.effects.rotation)
            assertTrue(changed.commit)
        }
    }

    @Test fun cachedFrameShowsCropAndOneUndoableCommitPerGesture() {
        val frame=VideoFrameState.Ready(Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888),0,
            source.uri,0,"Quick frame",true)
        val actions=mutableListOf<UiAction>()
        var current by mutableStateOf(edit)
        compose.setContent { FormaTheme { VideoEditorWorkspace(current,TranscodeUiState(ready=true,sources=listOf(current),selectedUri=source.uri),
            frame,onAction={actions+=it;if(it is UiAction.ChangeVideoEdit)current=it.edit},onBack={}) } }
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
    }
}
