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
            setOf("concat","trim","setpts","atrim","asetpts","aresample","aformat","apad","scale","pad","setsar","fps","tpad","format","settb","color","overlay"))
        val actions=mutableListOf<UiAction>()
        val ui=TranscodeUiState(ready=true,sources=listOf(SourceEdit(source)),movie=movie,capabilities=caps,
            selectedMovieClipId="a",movieCanUndo=true,movieCanRedo=true)
        compose.setContent { FormaTheme { Column(Modifier.verticalScroll(rememberScrollState())) { MovieControls(ui,emptyList(),RunState(),actions::add) } } }
        compose.onNodeWithContentDescription("Move clip 1 later").performScrollTo().performClick()
        compose.onAllNodesWithText("Remove clip")[0].performScrollTo().performClick()
        compose.onNodeWithTag("movie-split").performScrollTo().performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.onNodeWithTag("movie-undo").performScrollTo().performClick()
        compose.onNodeWithTag("movie-redo").performClick()
        compose.onNodeWithTag("movie-preview").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("movie-export").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(actions.contains(UiAction.MovieEdit(TimelineCommand.Move("a",1))))
            assertTrue(actions.contains(UiAction.MovieEdit(TimelineCommand.Remove("a"))))
            assertTrue(actions.any { it is UiAction.MovieEdit && it.command is TimelineCommand.Split })
            assertTrue(actions.containsAll(listOf(UiAction.MovieUndo,UiAction.MovieRedo,UiAction.MoviePreview,UiAction.MovieExport)))
        }
    }
}
