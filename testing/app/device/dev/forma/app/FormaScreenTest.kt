package dev.forma.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forma.app.ui.*
import dev.forma.app.data.LiveProgress
import dev.forma.app.data.DeliveryCopyProgress
import dev.forma.app.work.*
import dev.forma.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FormaScreenTest {
    @Test fun saveProgressReportsCopiedBytesSeparately() {
        compose.setContent { FormaTheme { DeliveryProgressView(DeliveryCopyProgress("save",1_048_576,4_194_304)) } }
        compose.onNodeWithTag("save-copy-progress").assertExists()
        compose.onNodeWithText("1 of 4 MB copied").assertExists()
    }
    @get:Rule val compose = createComposeRule()
    private val source = Source("content://test/video", "Sample.mp4", 10000, 640, 360, 1, 1)
    @Test fun finishedDeliveryShowsClearLocationAndRetrySave() {
        val destination=SaveDestination.FormaLibrary(MediaCategory.VIDEO)
        val failed=QueueEntry(JobSpec("failed-save",source,Trim(),Settings()),JobState.COMPLETED,
            delivery=Delivery(destination,DeliveryReceipt.Failed("Storage full",null)))
        var action:UiAction?=null
        compose.setContent { FormaTheme { FormaWorkspace(TranscodeUiState(ready=true),listOf(failed),RunState(),
            initiallyQueue=true,onAction={ action=it },progressContent={}) } }
        compose.onNodeWithTag("finished-list").performClick()
        compose.onNodeWithText("Converted; save failed",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Movies/Forma",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Retry save").performClick()
        compose.runOnIdle {assertEquals(UiAction.RetrySave("failed-save"),action)}
    }
    @Test fun finishedCardsDistinguishSavedSavingAndPrivateResults() {
        val destination=SaveDestination.FormaLibrary(MediaCategory.VIDEO)
        val saved=QueueEntry(JobSpec("saved",source.copy(name="Saved.mp4"),Trim(),Settings()),JobState.COMPLETED,
            delivery=Delivery(destination,DeliveryReceipt.Saved("content://media/external/video/media/1","Saved_forma.mp4",10,"0".repeat(64))))
        val saving=QueueEntry(JobSpec("saving",source.copy(name="Saving.mp4"),Trim(),Settings()),JobState.COMPLETED,
            delivery=Delivery(destination,DeliveryReceipt.Waiting))
        val legacy=QueueEntry(JobSpec("legacy",source.copy(name="Legacy.mp4"),Trim(),Settings()),JobState.COMPLETED)
        compose.setContent { FormaTheme { FormaWorkspace(TranscodeUiState(ready=true),listOf(saved,saving,legacy),RunState(),
            initiallyQueue=true,onAction={},progressContent={}) } }
        compose.onNodeWithTag("finished-list").performClick()
        compose.onNodeWithText("Saved to Movies/Forma",substring=true).assertExists()
        compose.onNodeWithText("Saving to Movies/Forma…").assertExists()
        compose.onNodeWithTag("editor").performScrollToNode(hasText("Private in Forma"))
        compose.onNodeWithText("Private in Forma").assertExists()
        compose.onNodeWithTag("editor").performScrollToNode(hasText("Saved.mp4"))
        compose.onNodeWithText("View saved file").assertExists()
    }

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
    @Test fun progressDisplaysCompactPercentageSpeedEtaAndBatteryLabels() {
        val job = QueueEntry(JobSpec("active", source, Trim(), Settings()), JobState.RUNNING)
        compose.setContent { FormaTheme { ProgressView(job, LiveProgress("active", Progress(2500, 2.0))) } }
        compose.onNodeWithText("25%").assertExists()
        compose.onNodeWithText("2.0×").assertExists()
        compose.onNodeWithText("~0:04").assertExists()
        compose.onNodeWithText("ETA").assertExists()
        compose.onNodeWithText("Battery draw").assertExists()
    }
    @Test fun noNativeBuildCannotConvertSelectedMedia() {
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready = true, sources = listOf(SourceEdit(source))), emptyList(), null, {}) } }
        compose.onNodeWithTag("convert").assertIsNotEnabled()
    }
    @Test fun convertShowsWhereEachSelectedKindWillSave() {
        var state by mutableStateOf(TranscodeUiState(ready=true,sources=listOf(SourceEdit(source))))
        compose.setContent { FormaTheme { FormaScreen(state,emptyList(),null,{}) } }
        compose.onNodeWithText("Saves to Movies/Forma").assertExists()
        compose.runOnIdle { state=state.copy(destinationMode="custom",chosenFolderLabel="My exports") }
        compose.onNodeWithText("Saves to My exports").assertExists()
    }
    @Test fun leftShelfOpensTheQueueWithoutResettingTheEditor() {
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready = true), emptyList(), null, {}) } }
        compose.onNodeWithTag("open-shelf").performClick()
        compose.onNodeWithText("Queue (0)").performClick()
        compose.onNodeWithText("Queue is empty").assertExists()
        compose.onNodeWithTag("finished-list").performClick()
        compose.onNodeWithText("No finished conversions yet").assertExists()
    }
    @Test fun failedConversionIsEasyToFindAndShowsItsReason() {
        val completed = (1..18).map { QueueEntry(JobSpec("done-$it", source, Trim(), Settings()), JobState.COMPLETED) }
        val reason = "HDR/high-bit-depth or an unqualified pixel format needs a tested color pipeline."
        val failed = QueueEntry(JobSpec("failed", source, Trim(), Settings()), JobState.FAILED, reason)
        compose.setContent { FormaTheme { FormaWorkspace(
            TranscodeUiState(ready = true, message = "A conversion failed. Review the item before starting remaining jobs."),
            completed + failed, RunState(RunMode.IDLE), onAction = {}, progressContent = {}) } }
        compose.onNodeWithText("View failed job").assertIsDisplayed().performClick()
        compose.onNodeWithTag("finished-list").assertIsDisplayed()
        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithText("Add retry to queue").assertIsDisplayed()
    }
    @Test fun savedEncoderAndResultAreShownInSeparateLists() {
        val pending = QueueEntry(JobSpec("pending", source.copy(name = "Waiting.mp4"), Trim(), Settings(video = VideoEncoder.X264)))
        val done = QueueEntry(JobSpec("done", source.copy(name = "Done.mp4"), Trim(), Settings(video = VideoEncoder.H265_HW, rateControl = RateControl.BITRATE, fps = 30)), JobState.COMPLETED)
        compose.setContent { FormaTheme { FormaWorkspace(TranscodeUiState(ready = true), listOf(pending, done), RunState(),
            initiallyQueue = true, onAction = {}, progressContent = {}) } }
        compose.onNodeWithText("Waiting.mp4").assertExists()
        compose.onNodeWithText("Done.mp4").assertDoesNotExist()
        compose.onNodeWithText("H.264 · software", substring = true).assertExists()
        compose.onNodeWithTag("finished-list").performClick()
        compose.onNodeWithText("Done.mp4").assertExists()
        compose.onNodeWithText("Waiting.mp4").assertDoesNotExist()
        compose.onNodeWithText("H.265 · device", substring = true).assertExists()
    }
    @Test fun preservedFutureAudioJobRemainsVisibleInFinished() {
        val opaque = dev.forma.core.audio.AudioEdit(schemaVersion=99)
        val job=QueueEntry(JobSpec("future",source,Trim(),Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,audioEdit=opaque)),JobState.FAILED)
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready=true),listOf(job),null,{}) } }
        compose.onNodeWithTag("open-shelf").performClick()
        compose.onNodeWithText("Finished (1)").performClick()
        compose.onNodeWithText("WAV · Duration unavailable · PCM_F32LE").assertExists()
    }

}
