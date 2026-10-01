@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui

import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.forma.app.TranscodeUiState
import dev.forma.app.UiAction
import dev.forma.app.video.VideoFrameState
import dev.forma.app.video.VideoRenderState
import dev.forma.core.*
import kotlin.math.min
import kotlin.math.roundToInt

@Composable fun EditorWorkspace(ui:TranscodeUiState,frame:VideoFrameState,render:VideoRenderState,
    onAction:(UiAction)->Unit,onBack:()->Unit) {
    val edit=ui.selected ?: return
    val imageInfo=edit.source.imageInfo
    val imageDocument=ui.imageDocument
    if(imageInfo!=null && imageDocument!=null) {
        dev.forma.app.ui.image.ImageEditorPanel(imageDocument,imageInfo,
            ui.imageEditor,ui.imagePreview,ui.capabilities,focused=true,onClose=onBack,action=onAction)
    } else if(edit.source.videoTracks>0) VideoEditorWorkspace(edit,ui,frame,render,onAction,onBack)
    else Text("Choose a video or image to edit.")
}

@Composable fun VideoEditorWorkspace(edit:SourceEdit,ui:TranscodeUiState,frame:VideoFrameState,
    render:VideoRenderState=VideoRenderState.Idle,onAction:(UiAction)->Unit,onBack:()->Unit) {
    var close by remember {mutableStateOf(false)}
    BackHandler {if(ui.videoDraftDirty)close=true else onBack()}
    var playhead by rememberSaveable(edit.source.uri) {mutableLongStateOf(edit.trim.startMs)}
    var cropNumbers by remember {mutableStateOf(false)}
    LaunchedEffect(edit.source.uri,playhead,ui.videoRevision) {onAction(UiAction.SeekQuickVideo(playhead))}
    if(close) AlertDialog(onDismissRequest={close=false},title={Text("Leave this edit?")},
        text={Text("Keep the draft or discard these edits.")},
        confirmButton={FormaTextButton(onClick={onAction(UiAction.SaveVideoDraft);close=false;onBack()}){Text("Save draft")}},
        dismissButton={Row {FormaTextButton(onClick={onAction(UiAction.DiscardVideoDraft);close=false;onBack()}){Text("Discard")}
            FormaTextButton(onClick={close=false}){Text("Keep editing")}}})
    Column(Modifier.fillMaxSize().testTag("video-editor"),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row {FormaTextButton(onClick={if(ui.videoDraftDirty)close=true else onBack()}){Text("Back")}
            Spacer(Modifier.weight(1f))
            FormaTextButton(onClick={onAction(UiAction.UndoVideo)},enabled=ui.videoCanUndo){Text("Undo")}
            FormaTextButton(onClick={onAction(UiAction.RedoVideo)},enabled=ui.videoCanRedo){Text("Redo")}}
        Text(edit.source.name,style=MaterialTheme.typography.titleMedium,maxLines=1)
        val playable=(render as? VideoRenderState.Ready)?.takeIf {
            it.sourceKey==edit.source.uri && it.revision==ui.videoRevision
        }
        if(playable!=null) Column {
            VideoPreviewPlayer(playable)
            FormaTextButton(onClick={onAction(UiAction.CloseVideoPreview)}) {Text("Edit frame")}
        }
        else VideoPreviewCanvas(edit,frame,ui.videoTool,onAction)
        val renderStatus=when(render) {
            is VideoRenderState.Waiting -> "Waiting for conversion to finish"
            is VideoRenderState.Rendering -> "Rendering five-second preview"
            is VideoRenderState.Error -> render.message
            VideoRenderState.Stale -> "Preview needs updating"
            else -> null
        }
        if(renderStatus!=null)Text(renderStatus,style=MaterialTheme.typography.labelSmall)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Trim","Crop","Rotate").forEach { tool ->
                FilterChip(selected=ui.videoTool==tool,onClick={onAction(UiAction.VideoTool(tool))},label={Text(tool)},
                    enabled=tool!="Crop" || PreviewGeometry.displaySize(edit.source.width,edit.source.height,
                        edit.source.displayRotationDegrees)!=null)
            }
        }
        FormaOutlinedButton(onClick={onAction(UiAction.RenderVideoPreview(playhead))},
            enabled=ui.capabilities.available && render !is VideoRenderState.Rendering && render !is VideoRenderState.Waiting,
            modifier=Modifier.fillMaxWidth().testTag("play-video-preview")) {Text("Play preview · 5 seconds")}
        when(ui.videoTool) {
            "Trim" -> {
                Text("Start ${mediaTime(edit.trim.startMs)} · End ${mediaTime(edit.trim.endMs ?: edit.source.durationMs)}")
                Slider(value=playhead.toFloat(),onValueChange={playhead=it.toLong()},
                    valueRange=0f..edit.source.durationMs.coerceAtLeast(1).toFloat(),modifier=Modifier.testTag("video-playhead"))
                Text("Drag to find a frame. Exact trim times are below.",style=MaterialTheme.typography.bodySmall)
                Row {FormaTextButton(onClick={onAction(UiAction.ChangeVideoEdit(edit.copy(trim=edit.trim.copy(startMs=playhead))))},
                    enabled=playhead<(edit.trim.endMs ?: edit.source.durationMs)){Text("Set start here")}
                    FormaTextButton(onClick={onAction(UiAction.ChangeVideoEdit(edit.copy(trim=edit.trim.copy(endMs=playhead))))},
                        enabled=playhead>edit.trim.startMs){Text("Set end here")}}
            }
            "Crop" -> Row {
                FormaTextButton(onClick={cropNumbers=true}){Text("Enter crop numbers")}
                FormaTextButton(onClick={onAction(UiAction.ChangeVideoEdit(edit.copy(effects=edit.effects.copy(crop=null))))}){Text("Remove crop")}
            }
            "Rotate" -> Row {
                val nextRotation=when(edit.effects.rotation) {
                    QuarterTurn.NONE->QuarterTurn.CLOCKWISE
                    QuarterTurn.CLOCKWISE->QuarterTurn.HALF
                    QuarterTurn.HALF->QuarterTurn.COUNTERCLOCKWISE
                    QuarterTurn.COUNTERCLOCKWISE->QuarterTurn.NONE
                }
                FormaTextButton(onClick={onAction(UiAction.ChangeVideoEdit(edit.copy(
                    effects=edit.effects.copy(rotation=nextRotation))))}){Text("Rotate right")}
                FormaTextButton(onClick={onAction(UiAction.ChangeVideoEdit(edit.copy(effects=edit.effects.copy(
                    flipHorizontal=!edit.effects.flipHorizontal))))}){Text("Mirror")}
            }
        }
        Row {FormaTextButton(onClick={onAction(UiAction.ChangeVideoEdit(edit.copy(effects=ClipEffects(),trim=Trim())))}){Text("Reset edits")}
            Spacer(Modifier.weight(1f))
            FormaButton(onClick={onAction(UiAction.Convert)},enabled=ui.ready && !ui.validating && ui.problems.isEmpty()) {Text("Convert edited video")}}
        }
    }
    if(cropNumbers) VideoCropNumbers(edit,onDismiss={cropNumbers=false},onApply={crop ->
        cropNumbers=false;onAction(UiAction.ChangeVideoEdit(edit.copy(effects=edit.effects.copy(crop=crop))))
    })
}

@Composable private fun VideoCropNumbers(edit:SourceEdit,onDismiss:()->Unit,onApply:(CropRect)->Unit) {
    val size=PreviewGeometry.displaySize(edit.source.width,edit.source.height,edit.source.displayRotationDegrees)
    val current=edit.effects.crop ?: size?.let {CropRect(0,0,it.first/2*2,it.second/2*2)} ?: CropRect(0,0,0,0)
    var x by remember {mutableStateOf(current.x.toString())};var y by remember {mutableStateOf(current.y.toString())}
    var width by remember {mutableStateOf(current.width.toString())};var height by remember {mutableStateOf(current.height.toString())}
    val rect=listOf(x,y,width,height).map(String::toIntOrNull).takeIf {it.all {n->n!=null}}?.let {CropRect(it[0]!!,it[1]!!,it[2]!!,it[3]!!)}
    val valid=size!=null && rect!=null && PreviewGeometry.validExportCrop(rect,size.first,size.second)
    AlertDialog(onDismissRequest=onDismiss,title={Text("Crop picture")},text={Column {
        Text(size?.let {"Displayed image: ${it.first} × ${it.second} pixels"} ?: "Crop is unavailable for this display transform.")
        OutlinedTextField(x,{x=it},label={Text("Left")},singleLine=true)
        OutlinedTextField(y,{y=it},label={Text("Top")},singleLine=true)
        OutlinedTextField(width,{width=it},label={Text("Width")},singleLine=true)
        OutlinedTextField(height,{height=it},label={Text("Height")},singleLine=true)
    }},confirmButton={FormaTextButton(onClick={rect?.let(onApply)},enabled=valid){Text("Apply crop")}},
        dismissButton={FormaTextButton(onClick=onDismiss){Text("Cancel")}})
}

@Composable private fun VideoPreviewCanvas(edit:SourceEdit,frame:VideoFrameState,tool:String,onAction:(UiAction)->Unit) {
    val ready=(frame as? VideoFrameState.Ready)?.takeIf {it.sourceKey==edit.source.uri}
    val status=when {
        ready!=null -> ready.status
        frame is VideoFrameState.Loading -> "Updating frame"
        frame is VideoFrameState.Error -> frame.message
        else -> "Choose a frame"
    }
    val base=PreviewGeometry.displaySize(edit.source.width,edit.source.height,edit.source.displayRotationDegrees)
    val final=base?.let {if(edit.effects.rotation in setOf(QuarterTurn.CLOCKWISE,QuarterTurn.COUNTERCLOCKWISE)) it.second to it.first else it}
    val full=base?.let {CropRect(0,0,it.first/2*2,it.second/2*2)}
    val crop=edit.effects.crop ?: full
    val overlay=if(crop!=null) PreviewGeometry.displayRect(edit.source.width,edit.source.height,
        edit.source.displayRotationDegrees,edit.effects,crop) as? PreviewCrop.Valid else null
    var dragCorner by remember {mutableStateOf<Int?>(null)}
    var gestureCrop by remember {mutableStateOf<CropRect?>(null)}
    var gestureEdit by remember {mutableStateOf<SourceEdit?>(null)}
    var gestureBaseline by remember {mutableStateOf<SourceEdit?>(null)}
    val currentEdit by rememberUpdatedState(edit)
    val currentOverlay by rememberUpdatedState(overlay)
    val currentFinal by rememberUpdatedState(final)
    Column {
        Text(status,style=MaterialTheme.typography.labelMedium)
        if(base==null) Text("Crop is unavailable for this video orientation.",style=MaterialTheme.typography.bodySmall)
        else if(edit.effects.crop!=null && overlay==null) Text("Crop cannot match the exported frame. Enter valid even-pixel coordinates.",
            color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        Canvas(Modifier.fillMaxWidth().height(320.dp).testTag("video-preview").pointerInput(tool,final) {
            if(tool!="Crop" || final==null) return@pointerInput
            detectDragGestures(onDragStart={ point ->
                val f=currentFinal ?:return@detectDragGestures
                val r=currentOverlay?.crop ?:return@detectDragGestures
                val scale=min(size.width.toFloat()/f.first,size.height.toFloat()/f.second)
                val left=(size.width-f.first*scale)/2;val top=(size.height-f.second*scale)/2
                val corners=listOf(Offset(r.x.toFloat(),r.y.toFloat()),Offset((r.x+r.width).toFloat(),r.y.toFloat()),
                    Offset((r.x+r.width).toFloat(),(r.y+r.height).toFloat()),Offset(r.x.toFloat(),(r.y+r.height).toFloat()))
                dragCorner=corners.indices.minByOrNull {((corners[it].x*scale+left-point.x)*(corners[it].x*scale+left-point.x)+
                    (corners[it].y*scale+top-point.y)*(corners[it].y*scale+top-point.y))}?.takeIf {index ->
                    val c=corners[index];(Offset(c.x*scale+left,c.y*scale+top)-point).getDistance()<48.dp.toPx()
                }
                gestureCrop=if(dragCorner!=null)r else null
                gestureBaseline=if(dragCorner!=null)currentEdit else null
                gestureEdit=null
            },onDrag={change,_ ->
                val corner=dragCorner ?:return@detectDragGestures
                val f=currentFinal ?:return@detectDragGestures
                val r=gestureCrop ?:return@detectDragGestures
                val scale=min(size.width.toFloat()/f.first,size.height.toFloat()/f.second)
                val left=(size.width-f.first*scale)/2;val top=(size.height-f.second*scale)/2
                val x=((((change.position.x-left)/scale).roundToInt().coerceIn(0,f.first))/2)*2
                val y=((((change.position.y-top)/scale).roundToInt().coerceIn(0,f.second))/2)*2
                val right=r.x+r.width;val bottom=r.y+r.height
                val next=when(corner) {
                    0->CropRect(x,y,right-x,bottom-y)
                    1->CropRect(r.x,y,x-r.x,bottom-y)
                    2->CropRect(r.x,r.y,x-r.x,y-r.y)
                    else->CropRect(x,r.y,right-x,y-r.y)
                }
                val mapped=PreviewGeometry.mapCrop(currentEdit.source.width,currentEdit.source.height,
                    currentEdit.source.displayRotationDegrees,currentEdit.effects,next)
                if(mapped is PreviewCrop.Valid) {
                    gestureCrop=next
                    val changed=currentEdit.copy(effects=currentEdit.effects.copy(crop=mapped.crop))
                    gestureEdit=changed
                    onAction(UiAction.ChangeVideoEdit(changed,false))
                }
                change.consume()
            },onDragEnd={
                gestureEdit?.let {onAction(UiAction.ChangeVideoEdit(it,true))}
                dragCorner=null;gestureCrop=null;gestureEdit=null;gestureBaseline=null
            },onDragCancel={
                gestureBaseline?.let {onAction(UiAction.ChangeVideoEdit(it,true))}
                dragCorner=null;gestureCrop=null;gestureEdit=null;gestureBaseline=null
            })
        }) {
            drawRect(Color(0xff171a20))
            if(ready!=null && final!=null && base!=null) {
                val bitmap=ready.bitmap
                val fit=min(size.width/final.first,size.height/final.second)
                drawIntoCanvas {canvas ->
                    val matrix=Matrix().apply {
                        postTranslate(-bitmap.width/2f,-bitmap.height/2f)
                        val raw=if(ready.orientationApplied) base else edit.source.width to edit.source.height
                        postScale(raw.first.toFloat()/bitmap.width,raw.second.toFloat()/bitmap.height)
                        if(!ready.orientationApplied) postRotate(-(edit.source.displayRotationDegrees ?: 0).toFloat())
                        postRotate(when(edit.effects.rotation) {QuarterTurn.NONE->0f;QuarterTurn.CLOCKWISE->90f;
                            QuarterTurn.HALF->180f;QuarterTurn.COUNTERCLOCKWISE->270f})
                        postScale(if(edit.effects.flipHorizontal)-1f else 1f,if(edit.effects.flipVertical)-1f else 1f)
                        postScale(fit,fit);postTranslate(size.width/2,size.height/2)
                    }
                    canvas.nativeCanvas.drawBitmap(bitmap,matrix,Paint(Paint.FILTER_BITMAP_FLAG))
                }
                overlay?.let {mapped ->
                    val r=mapped.crop;val left=(size.width-final.first*fit)/2+r.x*fit;val top=(size.height-final.second*fit)/2+r.y*fit
                    val right=left+r.width*fit;val bottom=top+r.height*fit
                    drawRect(Color.Black.copy(alpha=.45f),Offset(0f,0f),androidx.compose.ui.geometry.Size(size.width,top))
                    drawRect(Color.Black.copy(alpha=.45f),Offset(0f,bottom),androidx.compose.ui.geometry.Size(size.width,size.height-bottom))
                    drawRect(Color.Black.copy(alpha=.45f),Offset(0f,top),androidx.compose.ui.geometry.Size(left,bottom-top))
                    drawRect(Color.Black.copy(alpha=.45f),Offset(right,top),androidx.compose.ui.geometry.Size(size.width-right,bottom-top))
                    val points=listOf(Offset(left,top),Offset(right,top),Offset(right,bottom),Offset(left,bottom))
                    for(i in points.indices)drawLine(Color.Cyan,points[i],points[(i+1)%4],3f)
                    points.forEach {drawCircle(Color.Cyan,9.dp.toPx(),it)}
                }
            }
        }
    }
}
