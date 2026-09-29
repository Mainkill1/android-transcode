@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui.image
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.forma.app.UiAction
import dev.forma.app.image.*
import dev.forma.app.ui.FormaButton as Button
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.core.Capabilities
import dev.forma.core.image.*

@Composable fun ImageEditorPanel(document:ImageEditDocument,info:ImageInfo,state:ImageEditorState,preview:ImagePreviewState,caps:Capabilities,action:(UiAction)->Unit) {
    var close by remember {mutableStateOf(false)}
    BackHandler(state.open){if(state.dirty)close=true else action(UiAction.ToggleImageEditor)}
    if(close)AlertDialog(onDismissRequest={close=false},title={Text("Close image draft?")},text={Text("Save this draft or discard its edits.")},confirmButton={TextButton(onClick={close=false;action(UiAction.SaveImageDraft)}){Text("Save draft")}},dismissButton={FlowRow{TextButton(onClick={close=false;action(UiAction.DiscardImageDraft)}){Text("Discard")};TextButton(onClick={close=false}){Text("Cancel")}}})
    Column(Modifier.fillMaxWidth().testTag("image-editor").onPreviewKeyEvent { e->
        if(e.type==KeyEventType.KeyDown && e.isCtrlPressed && e.key==Key.Z){action(if(e.isShiftPressed)UiAction.RedoImage else UiAction.UndoImage);true}
        else if(e.type==KeyEventType.KeyDown && e.isCtrlPressed && e.key==Key.Y){action(UiAction.RedoImage);true}else false
    },verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Image · ${info.width} × ${info.height} · ${info.bytes} bytes · ${info.format.name}",style=MaterialTheme.typography.titleSmall)
        Text("${info.bitDepth}-bit · ${info.alpha.name.lowercase()} alpha · EXIF ${info.orientation} · ${if(info.profile==ImageProfile.ASSUMED_SRGB)"Assumed sRGB" else "sRGB"}",style=MaterialTheme.typography.bodySmall)
        if(!state.open)Button(onClick={action(UiAction.ToggleImageEditor)}){Text("Edit image")}
        else {
            FlowRow {TextButton(onClick={if(state.dirty)close=true else action(UiAction.ToggleImageEditor)}){Text("Back")};TextButton(onClick={action(UiAction.UndoImage)},enabled=state.canUndo){Text("Undo")};TextButton(onClick={action(UiAction.RedoImage)},enabled=state.canRedo){Text("Redo")};TextButton(onClick={action(UiAction.ChangeImage(ImageEditDocument(source=document.source,revision=document.revision)))}){Text("Reset all")}}
            val canvas: @Composable () -> Unit={ImageCanvas(document,info,preview,state.tool,action)}
            val tools: @Composable () -> Unit={
                FlowRow {listOf("Crop","Adjust","Markup","Export").forEach{tool->FilterChip(onClick={action(UiAction.ImageTool(tool))},selected=state.tool==tool,label={Text(tool)},modifier=Modifier.heightIn(min=52.dp))}}
                Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    when(state.tool){"Crop"->ImageCropPanel(document,info,action);"Adjust"->ImageAdjustPanel(document,action);"Markup"->ImageMarkupPanel(document,action);else->ImageExportPanel(document,info,caps,action)}
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()){
                if(maxWidth>=840.dp)Row(horizontalArrangement=Arrangement.spacedBy(16.dp)){Column(Modifier.weight(1f)){canvas()};Column(Modifier.width(320.dp)){tools()}}
                else Column{canvas();tools()}
            }
            val errors=ImageValidation.validate(document)
            errors.forEach{Text(it.message,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            preview.error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(!caps.available)Text("Unavailable · ${caps.reason}",style=MaterialTheme.typography.bodySmall)
            var roadmap by remember {mutableStateOf(false)}
            TextButton(onClick={roadmap=!roadmap}){Text(if(roadmap)"Hide roadmap" else "More tools · Planned")}
            if(roadmap)Text("Planned: straighten, perspective, profile conversion, masks, layers, retouching, batch recipes, RAW, animation and AI. These require separate qualification.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable internal fun NumberField(label:String,value:Double,min:Double=-Double.MAX_VALUE,max:Double=Double.MAX_VALUE,integer:Boolean=false,onChange:(Double)->Unit) {
    var text by remember(value){mutableStateOf(if(integer)value.toLong().toString() else "%.3f".format(java.util.Locale.ROOT,value).trimEnd('0').trimEnd('.'))}
    var invalid by remember{mutableStateOf(false)}
    OutlinedTextField(text,{raw->text=raw;val n=raw.toDoubleOrNull();invalid=n==null || !n.isFinite() || n !in min..max || (integer && n%1!=0.0);if(!invalid)onChange(n!!)},label={Text(label)},isError=invalid,singleLine=true,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp))
}
@Composable internal fun ColorField(label:String,value:Rgba,onChange:(Rgba)->Unit) {
    var text by remember(value){mutableStateOf("%02X%02X%02X%02X".format(value.red,value.green,value.blue,value.alpha))}
    OutlinedTextField(text,{raw->text=raw;val n=raw.toLongOrNull(16);if(raw.length==8 && n!=null)onChange(Rgba(((n shr 24)and 255).toInt(),((n shr 16)and 255).toInt(),((n shr 8)and 255).toInt(),(n and 255).toInt()))},label={Text("$label RGBA hex")},supportingText={Text("RRGGBBAA · FF is opaque; 00 is transparent")},singleLine=true,modifier=Modifier.fillMaxWidth())
}
