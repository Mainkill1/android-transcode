package dev.forma.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.FormaScreen
import dev.forma.app.ui.MovieControls
import dev.forma.app.work.RunState
import dev.forma.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovieControlsTest {
    @get:Rule val compose = createComposeRule()
    private fun tapVisible(tag: String) {
        val button = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
        val bounds = button.getUnclippedBoundsInRoot()
        val viewport = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("$tag bounds $bounds must be inside viewport $viewport", bounds.left >= viewport.left &&
            bounds.top >= viewport.top && bounds.right <= viewport.right && bounds.bottom <= viewport.bottom)
        button.performClick()
    }
    @Test fun movieControlsAreAccessibleAndNeverClaimMissingNativePreview() {
        val source=Source("content://a","A.mp4",6000,640,360,1,1)
        val actions=mutableListOf<UiAction>()
        val movie=MovieProject(sequence=SequenceSpec(EditTimeline(listOf(TimelineClip("a",source)))))
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready=true,sources=listOf(SourceEdit(source)),movie=movie),emptyList(),null,actions::add) } }
        compose.onNodeWithTag("editor").performScrollToNode(hasTestTag("open-movie"))
        compose.onNodeWithTag("open-movie").performClick()
        compose.onNodeWithTag("movie-append").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(actions.any { it == UiAction.MovieAppendSelected }) }
        compose.onNodeWithTag("movie-preview").assertIsNotEnabled()
        compose.onNodeWithTag("movie-export").assertIsNotEnabled()
    }
    @Test fun movieInspectorExposesOrderedEditsAndRealQueueActions() {
        val source=Source("content://a","A.mp4",6000,640,360,1,1)
        val clips=listOf(TimelineClip("a",source),TimelineClip("b",source.copy(uri="content://b",name="B.mp4")))
        val movie=MovieProject(sequence=SequenceSpec(EditTimeline(clips)))
        val caps=Capabilities(true,"",setOf("libx264","aac"),setOf("mp4"),
            setOf("concat","trim","setpts","atrim","asetpts","aresample","aformat","apad","scale","pad","setsar","fps","tpad","format","settb","color","overlay","xfade"))
        val actions=mutableListOf<UiAction>()
        val ui=TranscodeUiState(ready=true,sources=listOf(SourceEdit(source)),movie=movie,capabilities=caps,
            selectedMovieClipId="a",movieCanUndo=true,movieCanRedo=true)
        compose.setContent { FormaTheme { Column(Modifier.verticalScroll(rememberScrollState())) { MovieControls(ui,emptyList(),RunState(),actions::add) } } }
        compose.onNodeWithContentDescription("Move clip 1 later").performScrollTo().performClick()
        compose.onAllNodesWithText("Remove clip")[0].performScrollTo().performClick()
        compose.onNodeWithTag("movie-split").performScrollTo().performClick()
        compose.onNodeWithText("Apply").performClick()
        tapVisible("movie-undo")
        tapVisible("movie-redo")
        tapVisible("movie-preview")
        tapVisible("movie-export")
        compose.runOnIdle {
            assertTrue(actions.contains(UiAction.MovieEdit(TimelineCommand.Move("a",1))))
            assertTrue(actions.contains(UiAction.MovieEdit(TimelineCommand.Remove("a"))))
            assertTrue(actions.any { it is UiAction.MovieEdit && it.command is TimelineCommand.Split })
            assertTrue("Expected undo, redo, preview and export; captured $actions", actions.containsAll(listOf(UiAction.MovieUndo,UiAction.MovieRedo,UiAction.MoviePreview,UiAction.MovieExport)))
        }
    }

    @Test fun selectedClipPreviewUsesMovieTimeAfterPreviousClipAndTransition() {
        val first=Source("content://a","A.mp4",6_000,640,360,1)
        val second=first.copy(uri="content://b",name="B.mp4")
        val movie=MovieProject(sequence=SequenceSpec(EditTimeline(listOf(
            TimelineClip("a",first),TimelineClip("b",second,Trim(1_000,6_000)))),
            CanvasSpec(1280,720,30),500))
        val caps=Capabilities(true,"",setOf("libx264","aac"),setOf("mp4"),
            setOf("concat","trim","setpts","atrim","asetpts","aresample","aformat","apad","scale","pad","setsar","fps","tpad","format","settb","color","overlay","xfade"))
        val actions=mutableListOf<UiAction>()
        val ui=TranscodeUiState(ready=true,movie=movie,capabilities=caps,selectedMovieClipId="b")
        compose.setContent { FormaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            MovieControls(ui,emptyList(),RunState(),actions::add)
        } } }
        compose.onNodeWithTag("movie-play-window").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {assertTrue(actions.contains(UiAction.MoviePreviewAt(5_500)))}
    }
}
