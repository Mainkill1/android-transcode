package dev.forma.app.image
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.*
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.image.ImageEditorPanel
import dev.forma.core.*
import dev.forma.core.image.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
class ImageEditorUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun nativeImageToolsHaveNumericCropAndNoVideoControls() {
        val info=ImageInfo(101,77,ImageFormat.PNG,alpha=ImageAlpha.PRESENT,hash="a".repeat(64),bytes=100)
        val doc=ImageEditDocument(source=ImageSource("content://one","one.png",info.hash,100))
        compose.setContent { FormaTheme { ImageEditorPanel(doc,info,ImageEditorState(open=true),ImagePreviewState(),Capabilities(),action={}) } }
        compose.onNodeWithText("Crop").assertExists();compose.onNodeWithText("Left px").assertExists()
        compose.onNodeWithText("Fit").assertExists();compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithText("FPS").assertDoesNotExist();compose.onNodeWithText("Trim").assertDoesNotExist()
    }
    @Test fun publishedBitmapsSurviveDecodeEditorAndPreviewReplacement() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val owned=File(context.filesDir,"imports/image-ui-${UUID.randomUUID()}")
        val fixtures=listOf(ImageFixtures.png(context,File(owned,"one"),101,77),ImageFixtures.png(context,File(owned,"two"),97,71))
        var index by mutableStateOf(0);var open by mutableStateOf(false)
        var document by mutableStateOf(ImageEditDocument(source=ImageFixtures.source(context,fixtures[0].first,fixtures[0].second)))
        var preview by mutableStateOf(ImagePreviewState())
        try {
            compose.setContent { FormaTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                ImageEditorPanel(document,fixtures[index].second,ImageEditorState(open=open),preview,Capabilities()) { action ->
                    when(action){UiAction.ToggleImageEditor->open=!open;is UiAction.ChangeImage->document=action.document;else->Unit}
                }
            } } }
            repeat(4) {
                compose.waitUntil(10000){compose.onAllNodesWithContentDescription("Original image preview").fetchSemanticsNodes().isNotEmpty()}
                // Loading the first real bitmap disposed the null-key effect and used to recycle
                // the newly published bitmap before BitmapPainter could draw it.
                compose.onNodeWithContentDescription("Original image preview").captureToImage()
                compose.onNodeWithText("Edit image").performScrollTo().performClick()
                compose.onNodeWithTag("image-canvas").captureToImage()
                val (file,info)=fixtures[index]
                compose.runOnIdle { preview=ImagePreviewState(path=file.path,status="Preview",geometry=ImageGeometry.resolve(info,document,ImageAttempt(0,ImageFormat.PNG,90))) }
                compose.waitUntil(10000){compose.onAllNodesWithText("Preview").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("image-canvas").captureToImage()
                compose.runOnIdle { document=document.copy(crop=NormalizedCrop(.1,.1,.9,.9));preview=ImagePreviewState() }
                compose.waitForIdle();compose.onNodeWithTag("image-canvas").captureToImage()
                compose.onNodeWithText("Back").performScrollTo().performClick()
                compose.runOnIdle {
                    index=1-index;document=ImageEditDocument(source=ImageFixtures.source(context,fixtures[index].first,fixtures[index].second))
                }
            }
        } finally { owned.deleteRecursively() }
    }
}
