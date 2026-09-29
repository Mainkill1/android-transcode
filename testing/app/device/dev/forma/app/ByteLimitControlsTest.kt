package dev.forma.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forma.app.ui.FormaScreen
import dev.forma.app.ui.FormaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ByteLimitControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun defaultTenMegabytesAndExplicitManualModeAreVisibleBeforeImport() {
        val actions=mutableListOf<UiAction>()
        compose.setContent { FormaTheme { FormaScreen(TranscodeUiState(ready=true),emptyList(),null,actions::add) } }
        compose.onNodeWithTag("size-limit").assertIsDisplayed().performClick()
        compose.onNodeWithText("No size limit").performClick()
        assertEquals(UiAction.SetTargetBytes(null),actions.last())
        assertEquals(10000000L,TranscodeUiState().targetBytes)
    }
}
