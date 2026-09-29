package dev.forma.app.ui.image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forma.app.UiAction
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.core.image.*
import dev.forma.core.Capabilities
@Composable fun ImageAdjustPanel(d:ImageEditDocument,caps:Capabilities,action:(UiAction)->Unit){
    val a=d.adjustments
    fun available(vararg filters:String)=caps.available && filters.all{it in caps.filters}
    AdjustControl("Brightness",a.brightness,-1.0,1.0,0.0,"Display-referred offset",{a.copy(brightness=it)},d,action,available("lutrgb"))
    AdjustControl("Contrast",a.contrast,0.0,2.0,1.0,"Midpoint 0.5",{a.copy(contrast=it)},d,action,available("lutrgb"))
    AdjustControl("Saturation",a.saturation,0.0,3.0,1.0,"0 is sRGB luminance grayscale",{a.copy(saturation=it)},d,action,available("colorchannelmixer"))
    AdjustControl("Gamma",a.gamma,.1,3.0,1.0,"Power 1 / gamma",{a.copy(gamma=it)},d,action,available("lutrgb"))
    AdjustControl("Blur",a.blurSigma,0.0,20.0,0.0,"Gaussian sigma · output pixels",{a.copy(blurSigma=it)},d,action,available("gblur","premultiply","unpremultiply"))
    AdjustControl("Sharpen",a.sharpenAmount,0.0,2.0,0.0,"Gaussian unsharp amount · 0 bypasses",{a.copy(sharpenAmount=it)},d,action,available("split","blend","gblur","premultiply","unpremultiply"))
    NumberField("Sharpen radius px",a.sharpenRadius.toDouble(),1.0,5.0,true,enabled=available("split","blend","gblur","premultiply","unpremultiply")){action(UiAction.ChangeImage(d.copy(adjustments=a.copy(sharpenRadius=it.toInt()))))}
    Text("Radius is Gaussian sigma in final-output pixels.",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={action(UiAction.ChangeImage(d.copy(adjustments=ImageAdjustments())))}){Text("Reset adjust")}
}
@Composable private fun AdjustControl(label:String,value:Double,min:Double,max:Double,neutral:Double,help:String,change:(Double)->ImageAdjustments,d:ImageEditDocument,action:(UiAction)->Unit,enabled:Boolean){
    Text("$label · neutral $neutral")
    Slider(value.toFloat(),{action(UiAction.ChangeImage(d.copy(adjustments=change(it.toDouble())),false))},enabled=enabled,valueRange=min.toFloat()..max.toFloat(),onValueChangeFinished={action(UiAction.ChangeImage(d,true))},modifier=Modifier.heightIn(min=52.dp))
    NumberField(label,value,min,max,enabled=enabled){action(UiAction.ChangeImage(d.copy(adjustments=change(it))))}
    if(!enabled)Text("Unavailable in this build",style=MaterialTheme.typography.bodySmall)
    Text(help,style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={action(UiAction.ChangeImage(d.copy(adjustments=change(neutral))))}){Text("Reset $label")}
}
