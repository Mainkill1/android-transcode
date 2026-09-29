package dev.forma.app.audio
import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import dev.forma.core.audio.*
import org.junit.Assert.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forma.app.*
import dev.forma.app.ui.*
import dev.forma.core.*
import org.junit.Rule
import org.junit.Test
class AudioEditorComposeTest {
    @get:Rule val compose=createComposeRule()
    @Test fun selectedAudioHasClearEditorEntry() {
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready=true,sources=listOf(SourceEdit(Source("content://test/audio","tone.wav",10000,audioTracks=1))),editor=Editor(settings=Settings(container=Container.M4A))),emptyList(),null,{}) } }
        compose.onNodeWithTag("editor").performScrollToNode(hasTestTag("edit-audio"))
        compose.onNodeWithTag("edit-audio").assertIsDisplayed()
    }

    @Test fun numericEqAndBypassEditTheSameSnapshot() {
        val band=EqBand("b")
        var state by mutableStateOf(TranscodeUiState(ready=true,audioEditor=AudioEditorState(open=true),
            sources=listOf(SourceEdit(Source("content://test/audio","tone.wav",10000,audioTracks=1))),
            editor=Editor(settings=Settings(container=Container.M4A,audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("eq","eq",parameters=EqParameters(listOf(band)))))))))
        compose.setContent { FormaTheme { FormaScreen(state,emptyList(),null) { action -> if(action is UiAction.ChangeAudioEdit) state=state.copy(editor=state.editor.copy(settings=state.editor.settings.copy(audioEdit=action.edit))) } } }
        compose.onNodeWithTag("editor").performScrollToNode(hasTestTag("eq-frequency-b"))
        compose.onNodeWithTag("eq-frequency-b").performTextReplacement("2000")
        compose.onNodeWithTag("eq-frequency-b").performImeAction()
        compose.runOnIdle { assertEquals(2000.0,(state.editor.settings.audioEdit.nodes.single().parameters as EqParameters).bands.single().frequencyHz,.001) }
        compose.onNodeWithTag("editor").performScrollToNode(hasTestTag("bypass-eq"))
        compose.onNodeWithTag("bypass-eq").performClick()
        compose.runOnIdle { assertFalse(state.editor.settings.audioEdit.nodes.single().enabled) }
    }
}
