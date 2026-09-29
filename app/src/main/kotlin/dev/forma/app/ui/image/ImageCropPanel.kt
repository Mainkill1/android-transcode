@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui.image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forma.app.UiAction
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.core.image.*
import kotlin.math.*
@Composable fun ImageCropPanel(d:ImageEditDocument,info:ImageInfo,action:(UiAction)->Unit) {
    val upright=ImageGeometry.orientation(info).first;val w=upright.width;val h=upright.height
    fun crop(c:NormalizedCrop,ratio:Double?=d.cropAspectRatio){if(c.left<c.right && c.top<c.bottom)action(UiAction.ChangeImage(d.copy(crop=c,cropAspectRatio=ratio)))}
    fun ratio(value:Double) {
        val canonical=if(d.quarterTurns%2==1)1/value else value
        val width=min(w.toDouble(),h*canonical);val height=width/canonical
        crop(NormalizedCrop((w-width)/2/w,(h-height)/2/h,(w+width)/2/w,(h+height)/2/h),canonical)
    }
    Text("Crop · upright source pixels")
    FlowRow {listOf("Free","Original","1:1","4:3","3:2","16:9","9:16").forEach{label->TextButton(onClick={
        when(label) {
            "Free"->crop(d.crop,null)
            "Original"->{val canonical=w.toDouble()/h;ratio(if(d.quarterTurns%2==1)1/canonical else canonical)}
            else->{val parts=label.split(':');ratio(parts[0].toDouble()/parts[1].toDouble())}
        }
    }){Text(label)}}}
    Text(if(d.cropAspectRatio==null)"Free crop" else "Ratio locked · ${"%.3f".format(java.util.Locale.ROOT,if(d.quarterTurns%2==1)1/d.cropAspectRatio!! else d.cropAspectRatio)}",style=MaterialTheme.typography.bodySmall)
    NumberField("Custom ratio",(if(d.quarterTurns%2==1)(d.crop.bottom-d.crop.top)*h/((d.crop.right-d.crop.left)*w) else (d.crop.right-d.crop.left)*w/((d.crop.bottom-d.crop.top)*h)),.01,100.0){ratio(it)}
    NumberField("Left px",floor(d.crop.left*w),0.0,(w-1).toDouble(),true){crop(d.crop.copy(left=it/w))}
    NumberField("Top px",floor(d.crop.top*h),0.0,(h-1).toDouble(),true){crop(d.crop.copy(top=it/h))}
    NumberField("Right px",ceil(d.crop.right*w),1.0,w.toDouble(),true){crop(d.crop.copy(right=it/w))}
    NumberField("Bottom px",ceil(d.crop.bottom*h),1.0,h.toDouble(),true){crop(d.crop.copy(bottom=it/h))}
    FlowRow{TextButton(onClick={action(UiAction.ChangeImage(d.copy(quarterTurns=(d.quarterTurns+3)%4)))}){Text("Turn left")};TextButton(onClick={action(UiAction.ChangeImage(d.copy(quarterTurns=(d.quarterTurns+1)%4)))}){Text("Turn right")};TextButton(onClick={action(UiAction.ChangeImage(d.copy(flipHorizontal=!d.flipHorizontal)))}){Text(if(d.flipHorizontal)"Unflip H" else "Flip H")};TextButton(onClick={action(UiAction.ChangeImage(d.copy(flipVertical=!d.flipVertical)))}){Text(if(d.flipVertical)"Unflip V" else "Flip V")}}
    TextButton(onClick={action(UiAction.ChangeImage(d.copy(crop=NormalizedCrop(),cropAspectRatio=null,quarterTurns=0,flipHorizontal=false,flipVertical=false)))}){Text("Reset crop")}
    val p=d.output
    fun output(value:ImageOutputPolicy)=action(UiAction.ChangeImage(d.copy(output=value)))
    Text("Resize")
    FlowRow{ResizeMode.entries.forEach{mode->FilterChip(onClick={output(p.copy(resizeMode=mode,allowResizeToFit=false))},selected=p.resizeMode==mode,label={Text(when(mode){ResizeMode.ORIGINAL->"Original";ResizeMode.PIXELS->"Pixels";ResizeMode.PERCENT->"Percent";ResizeMode.LONGEST_EDGE->"Long edge"})})}}
    if(p.resizeMode==ResizeMode.PIXELS){NumberField("Width px",(p.width?:w).toDouble(),1.0,40000000.0,true){output(p.copy(width=it.toInt(),allowResizeToFit=false))};NumberField("Height px",(p.height?:h).toDouble(),1.0,40000000.0,true){output(ImageCropEditing.resizeHeight(p,it.toInt()))}}
    if(p.resizeMode==ResizeMode.PERCENT)NumberField("Percent",p.percent,.001,10000.0){output(p.copy(percent=it,allowResizeToFit=false))}
    if(p.resizeMode==ResizeMode.LONGEST_EDGE)NumberField("Longest edge px",p.longestEdge.toDouble(),1.0,40000000.0,true){output(p.copy(longestEdge=it.toInt(),allowResizeToFit=false))}
    Toggle("Aspect lock",p.aspectLock){output(p.copy(aspectLock=it))};Toggle("Allow upscale",p.allowUpscale){output(p.copy(allowUpscale=it))}
    Text("Canvas · padding never shrinks the image")
    Toggle("Add padding",p.canvasWidth!=null){output(p.copy(canvasWidth=if(it)w else null,canvasHeight=if(it)h else null))}
    if(p.canvasWidth!=null){NumberField("Canvas width px",p.canvasWidth!!.toDouble(),1.0,40000000.0,true){output(p.copy(canvasWidth=it.toInt()))};NumberField("Canvas height px",p.canvasHeight!!.toDouble(),1.0,40000000.0,true){output(p.copy(canvasHeight=it.toInt()))}
        FlowRow{CanvasAnchor.entries.forEach{anchor->FilterChip(onClick={output(p.copy(anchor=anchor))},selected=p.anchor==anchor,label={Text(anchor.name.lowercase().replace('_',' '))})}}
        ColorField("Padding",p.padding){output(p.copy(padding=it))}}
    TextButton(onClick={output(ImageOutputPolicy(format=p.format,jpegQuality=p.jpegQuality,webpQuality=p.webpQuality,lossless=p.lossless,pngCompression=p.pngCompression,targetBytes=p.targetBytes,flatten=p.flatten))}){Text("Reset size")}
}
@Composable internal fun Toggle(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(value,onChange,modifier=Modifier.sizeIn(minWidth=52.dp,minHeight=52.dp));Text(label)}}
