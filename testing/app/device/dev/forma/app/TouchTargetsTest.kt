package dev.forma.app

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.forma.app.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TouchTargetsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appActionsHaveFingerSizedBoundsAndAcceptEdgeTaps() {
        var clicks = 0
        compose.setContent {
            FormaTheme {
                Column {
                    FormaIconButton(onClick = { clicks++ }, modifier = Modifier.testTag("touch-icon")) { Text("+") }
                    FormaTextButton(onClick = { clicks++ }, modifier = Modifier.testTag("touch-text")) { Text("Edit") }
                    FormaButton(onClick = { clicks++ }, modifier = Modifier.testTag("touch-primary")) { Text("Convert") }
                    FormaOutlinedButton(onClick = { clicks++ }, modifier = Modifier.testTag("touch-outline")) { Text("Queue") }
                }
            }
        }
        for (tag in listOf("touch-icon", "touch-text", "touch-primary", "touch-outline")) {
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(52.dp).assertWidthIsAtLeast(52.dp)
                .performTouchInput { click(Offset(1f, center.y)) }
        }
        compose.runOnIdle { assertEquals(4, clicks) }
    }
}
