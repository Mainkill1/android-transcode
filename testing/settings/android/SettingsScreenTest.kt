package dev.forma.app.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.settings.SettingsPanel
import dev.forma.app.ui.settings.SettingsRunControls
import dev.forma.core.settings.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun themeChangesAreDraftedAndDiscardRestoresSavedValue() {
        val state = mutableStateOf(SettingsDraft(SettingsDocument()))
        compose.setContent {
            FormaTheme { SettingsPanel(state.value, PreferenceValues.EMPTY, false,
                onValuesChanged={ state.value = state.value.copy(values=it) }, onSave={},
                onDiscard={ state.value = SettingsDraft(state.value.saved) }, onClose={}) }
        }
        compose.onNodeWithTag("settings-search").performTextInput("ui.theme")
        compose.onNodeWithTag("settings-row:ui.theme").performClick()
        compose.onNodeWithTag("settings-option:ui.theme:dark").performClick()
        compose.runOnIdle { assertEquals(SettingValue.Choice("dark"), state.value.values["ui.theme"]) }
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertEquals(PreferenceValues.EMPTY, state.value.values) }
    }

    @Test fun stopRemainsActionableWhileSettingsAreBusy() {
        var stops = 0
        compose.setContent { FormaTheme { SettingsPanel(SettingsDraft(SettingsDocument()), PreferenceValues.EMPTY, false,
            onValuesChanged={}, onSave={}, onDiscard={}, onClose={}, busy=true,
            runControls={ SettingsRunControls(running=true, stopping=false, onStop={ stops++ }) }) } }
        compose.onNodeWithTag("settings-stop").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, stops) }
    }

    @Test fun windowBackRequestReturnsToCategoriesBeforeClosing() {
        val request = mutableStateOf(0)
        var closed = false
        compose.setContent { FormaTheme { SettingsPanel(SettingsDraft(SettingsDocument()), PreferenceValues.EMPTY, false,
            onValuesChanged={}, onSave={}, onDiscard={}, onClose={ closed=true }, backRequest=request.value) } }
        compose.onNodeWithText("Video & picture").performClick()
        compose.onNodeWithTag("settings-row:video.codec").assertExists()
        compose.runOnIdle { request.value++ }
        compose.onNodeWithTag("settings-row:video.codec").assertDoesNotExist()
        compose.runOnIdle { assertEquals(false, closed) }
    }

    @Test fun liveBatteryActionCanBeDraftedWhileAutomaticRestartStaysPlanned() {
        val state = mutableStateOf(SettingsDraft(SettingsDocument()))
        compose.setContent { FormaTheme { SettingsPanel(state.value, PreferenceValues.EMPTY, false,
            onValuesChanged={ state.value = state.value.copy(values=it) }, onSave={}, onDiscard={}, onClose={}) } }

        compose.onNodeWithTag("settings-search").performTextInput("power.low_action")
        compose.onNodeWithTag("settings-row:power.low_action").performClick()
        compose.onNodeWithTag("settings-option:power.low_action:stop").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(SettingValue.Choice("stop"), state.value.values["power.low_action"])
        }

        compose.onNodeWithTag("settings-search").performTextClearance()
        compose.onNodeWithTag("settings-search").performTextInput("power.auto_continue")
        compose.onNodeWithTag("settings-row:power.auto_continue").performClick()
        compose.onNodeWithText("Planned — not active in exports.").assertIsDisplayed()
    }

    @Test fun choosingCustomSaveFolderLaunchesPickerBeforeChangingDefault() {
        val state=mutableStateOf(SettingsDraft(SettingsDocument()))
        var pickerCalls=0
        compose.setContent { FormaTheme { SettingsPanel(state.value,PreferenceValues.EMPTY,false,
            onValuesChanged={ state.value=state.value.copy(values=it) },onSave={},onDiscard={},onClose={},
            onChooseSaveFolder={ pickerCalls++ }) } }
        compose.onNodeWithTag("settings-search").performTextInput("export.destination")
        compose.onNodeWithTag("settings-row:export.destination").performClick()
        compose.onNodeWithTag("settings-option:export.destination:custom").performClick()
        compose.runOnIdle {
            assertEquals(1,pickerCalls)
            assertEquals(null,state.value.values["export.destination"])
        }
    }
}
