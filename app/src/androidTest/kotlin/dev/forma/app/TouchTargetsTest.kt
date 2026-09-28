package dev.forma.app

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.forma.app.ui.FormaTheme
import org.junit.Rule
import org.junit.Test

class TouchTargetsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun materialActionsReserveFingerSizedLayoutSpace() {
        compose.setContent {
            FormaTheme {
                Row {
                    IconButton(onClick = {}, modifier = Modifier.testTag("touch-icon")) { Text("+") }
                    TextButton(onClick = {}, modifier = Modifier.testTag("touch-text")) { Text("Edit") }
                }
            }
        }
        for (tag in listOf("touch-icon", "touch-text")) {
            compose.onNodeWithTag(tag).assertHeightIsAtLeast(52.dp).assertWidthIsAtLeast(52.dp)
        }
    }
}
