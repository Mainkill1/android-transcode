package dev.forma.app.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.settings.SettingsPanel
import dev.forma.core.settings.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Regression tests live only in the external androidTest source tree. */
class SettingsCompletionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resetIsPartOfTheScrollableSheetNotAnUnreachableFooter() {
        val state = mutableStateOf(SettingsDraft(SettingsDocument()).edit("ui.theme", SettingValue.Choice("dark")))
        compose.setContent { FormaTheme { SettingsPanel(state.value, PreferenceValues.EMPTY, false,
            onValuesChanged={ state.value = state.value.copy(values=it) }, onSave={}, onDiscard={}, onClose={}) } }
        compose.onNodeWithTag("settings-search").performTextInput("ui.theme")
        compose.onNodeWithTag("settings-row:ui.theme").performClick()
        compose.onNodeWithText("Use factory default").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(null, state.value.values["ui.theme"]) }
    }

    @Test fun longChoiceListCanBeSearchedWithoutApplyingAnUnselectedValue() {
        val state = mutableStateOf(SettingsDraft(SettingsDocument()))
        compose.setContent { FormaTheme { SettingsPanel(state.value, PreferenceValues.EMPTY, false,
            onValuesChanged={ state.value = state.value.copy(values=it) }, onSave={}, onDiscard={}, onClose={}) } }
        compose.onNodeWithTag("settings-search").performTextInput("video.frame_rate")
        compose.onNodeWithTag("settings-row:video.frame_rate").performClick()
        compose.onNodeWithTag("settings-choice-search").performTextInput("60")
        compose.runOnIdle { assertEquals(PreferenceValues.EMPTY, state.value.values) }
        compose.onNodeWithTag("settings-option:video.frame_rate:60").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SettingValue.Choice("60"), state.value.values["video.frame_rate"]) }
    }
}
