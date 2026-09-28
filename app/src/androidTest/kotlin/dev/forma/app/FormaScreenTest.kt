package dev.forma.app

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forma.app.ui.FormaScreen
import dev.forma.app.ui.FormaTheme
import dev.forma.core.Editor
import dev.forma.core.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FormaScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultsToSimpleAndNeverConvertsWithoutNative() {
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready = true), emptyList(), null, {}) } }
        compose.onNodeWithTag("editor").performScrollToNode(hasText("What would you like to do?"))
        compose.onNodeWithText("What would you like to do?").assertExists()
        compose.onNodeWithText("Video & format").assertDoesNotExist()
        compose.onNodeWithTag("convert").assertIsNotEnabled()
    }
    @Test fun expandingAdvancedPreservesCustomSettings() {
        val original = Settings(crf = 17)
        var state by mutableStateOf(TranscodeUiState(editor = Editor(settings = original, custom = true), ready = true))
        compose.setContent { FormaTheme { FormaScreen(state, emptyList(), null) { action ->
            if (action == UiAction.ToggleAdvanced) state = state.copy(editor = state.editor.copy(advanced = !state.editor.advanced))
        } } }
        compose.onNodeWithTag("editor").performScrollToNode(hasTestTag("mode-toggle"))
        compose.onNodeWithTag("mode-toggle").performClick()
        compose.runOnIdle { assertEquals(original, state.editor.settings); assertEquals(true, state.editor.advanced) }
        compose.onNodeWithTag("editor").performScrollToNode(hasTestTag("mode-toggle"))
        compose.onNodeWithTag("mode-toggle").performClick()
        compose.runOnIdle { assertEquals(original, state.editor.settings); assertEquals(false, state.editor.advanced) }
    }
}
