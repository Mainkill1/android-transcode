package dev.forma.app.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.settings.SettingsPanel
import dev.forma.core.settings.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsProvenanceUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun equalChoiceIsExplicitAndResetRestoresThePresetRatherThanFactory() {
        val app=PreferenceValues.EMPTY.with("video.frame_rate",SettingValue.Choice("30"))
        val preset=PreferenceValues.EMPTY.with("video.frame_rate",SettingValue.Choice("60"))
        val state=mutableStateOf(SettingsDraft(SettingsDocument(3)))
        compose.setContent { FormaTheme { SettingsPanel(state.value,app,true,
            onValuesChanged={state.value=state.value.copy(values=it)},onSave={},onDiscard={},onClose={},preset=preset) } }
        compose.onNodeWithTag("settings-search").performTextInput("video.frame_rate")
        compose.onNodeWithTag("settings-row:video.frame_rate").assertTextContains("Preset")
        compose.onNodeWithTag("settings-row:video.frame_rate").performClick()
        compose.onNodeWithTag("settings-option:video.frame_rate:60").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(SettingValue.Choice("60"),state.value.values["video.frame_rate"]) }
        compose.onNodeWithTag("settings-row:video.frame_rate").assertTextContains("This job override")
        compose.onNodeWithTag("settings-row:video.frame_rate").performClick()
        compose.onNodeWithText("Use inherited value").performScrollTo().performClick()
        compose.runOnIdle { assertNull(state.value.values["video.frame_rate"]) }
        compose.onNodeWithTag("settings-row:video.frame_rate").assertTextContains("Preset")
    }
}
