package dev.forma.app.image
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.forma.app.*
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.image.ImageEditorPanel
import dev.forma.core.*
import dev.forma.core.image.*
import org.junit.Rule
import org.junit.Test
class ImageEditorUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun nativeImageToolsHaveNumericCropAndNoVideoControls() {
        val info=ImageInfo(101,77,ImageFormat.PNG,alpha=ImageAlpha.PRESENT,hash="a".repeat(64),bytes=100)
        val doc=ImageEditDocument(source=ImageSource("content://one","one.png",info.hash,100))
        compose.setContent { FormaTheme { ImageEditorPanel(doc,info,ImageEditorState(open=true),ImagePreviewState(),Capabilities(),{}) } }
        compose.onNodeWithText("Crop").assertExists();compose.onNodeWithText("Left px").assertExists()
        compose.onNodeWithText("Fit").assertExists();compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithText("FPS").assertDoesNotExist();compose.onNodeWithText("Trim").assertDoesNotExist()
    }
}
