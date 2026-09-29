package dev.forma.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forma.app.ui.*
import dev.forma.app.data.LiveProgress
import dev.forma.app.work.*
import dev.forma.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FormaScreenTest {
    @get:Rule val compose = createComposeRule()
    private val source = Source("content://test/video", "Sample.mp4", 10000, 640, 360, 1, 1)
    @Test fun emptyHomeOnlyAsksForMedia() {
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready = true), emptyList(), null, {}) } }
        compose.onNodeWithTag("select-media").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("What would you like to do?").assertDoesNotExist()
        compose.onNodeWithText("Video & format").assertDoesNotExist()
        compose.onNodeWithTag("convert").assertDoesNotExist()
    }
    @Test fun expandingAdvancedPreservesCustomSettings() {
        val original = Settings(crf = 17)
        var state by mutableStateOf(TranscodeUiState(editor = Editor(settings = original, custom = true), sources = listOf(SourceEdit(source)), ready = true))
        compose.setContent { FormaTheme { FormaScreen(state, emptyList(), null) { action ->
            if (action == UiAction.ToggleAdvanced) state = state.copy(editor = state.editor.copy(advanced = !state.editor.advanced))
        } } }
        repeat(2) {
            compose.onNodeWithTag("editor").performScrollToNode(hasTestTag("mode-toggle"))
            compose.onNodeWithTag("mode-toggle").performClick()
            compose.runOnIdle { assertEquals(original, state.editor.settings); assertEquals(it == 0, state.editor.advanced) }
        }
    }
    @Test fun stopIsAvailableDuringImportAndQueueMutation() {
        var stopped = false
        val state = TranscodeUiState(ready = true, busy = true, fileTask = FileTask("Reading file 2 of 20"))
        compose.setContent { FormaTheme { FormaWorkspace(state, emptyList(), RunState(RunMode.RUNNING, 1),
            onAction = { if (it == UiAction.StopQueue) stopped = true }, progressContent = {}) } }
        compose.onNodeWithTag("stop-queue").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }
    @Test fun progressDoesNotRecomposeTheWorkspaceOwner() {
        val job = QueueEntry(JobSpec("active", source, Trim(), Settings()), JobState.RUNNING)
        var progress by mutableStateOf<LiveProgress?>(null)
        var ownerCompositions = 0
        compose.setContent { FormaTheme {
            SideEffect { ownerCompositions++ }
            FormaWorkspace(TranscodeUiState(ready = true), listOf(job), RunState(RunMode.RUNNING, 1),
                onAction = {}, progressContent = { entry -> ProgressView(entry, progress) })
        } }
        compose.waitForIdle()
        val initial = ownerCompositions
        repeat(10) { tick ->
            compose.runOnIdle { progress = LiveProgress("active", Progress((tick + 1) * 100L)) }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals(initial, ownerCompositions) }
        compose.onNodeWithTag("live-progress").assertExists()
    }
    @Test fun noNativeBuildCannotConvertSelectedMedia() {
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready = true, sources = listOf(SourceEdit(source))), emptyList(), null, {}) } }
        compose.onNodeWithTag("convert").assertIsNotEnabled()
    }
    @Test fun leftShelfOpensTheQueueWithoutResettingTheEditor() {
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready = true), emptyList(), null, {}) } }
        compose.onNodeWithTag("open-shelf").performClick()
        compose.onNodeWithText("Queue · 0").performClick()
        compose.onNodeWithText("Your queue").assertIsDisplayed()
        compose.onNodeWithText("No jobs yet. Select media to prepare your first conversion.").assertExists()
    }
}
