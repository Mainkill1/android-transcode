package dev.forma.app.ui.image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forma.app.UiAction
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.core.image.*
@Composable fun ImageAdjustPanel(d:ImageEditDocument,action:(UiAction)->Unit){
    val a=d.adjustments
    AdjustControl("Brightness",a.brightness,-1.0,1.0,0.0,"Display-referred offset",{a.copy(brightness=it)},d,action)
    AdjustControl("Contrast",a.contrast,0.0,2.0,1.0,"Midpoint 0.5",{a.copy(contrast=it)},d,action)
    AdjustControl("Saturation",a.saturation,0.0,3.0,1.0,"0 is sRGB luminance grayscale",{a.copy(saturation=it)},d,action)
    AdjustControl("Gamma",a.gamma,.1,3.0,1.0,"Power 1 / gamma",{a.copy(gamma=it)},d,action)
    AdjustControl("Blur",a.blurSigma,0.0,20.0,0.0,"Gaussian sigma · output pixels",{a.copy(blurSigma=it)},d,action)
    AdjustControl("Sharpen",a.sharpenAmount,0.0,2.0,0.0,"Unsharp amount · 0 bypasses",{a.copy(sharpenAmount=it)},d,action)
    NumberField("Sharpen radius px",a.sharpenRadius.toDouble(),1.0,5.0,true){action(UiAction.ChangeImage(d.copy(adjustments=a.copy(sharpenRadius=it.toInt()))))}
    TextButton(onClick={action(UiAction.ChangeImage(d.copy(adjustments=ImageAdjustments())))}){Text("Reset adjust")}
}
@Composable private fun AdjustControl(label:String,value:Double,min:Double,max:Double,neutral:Double,help:String,change:(Double)->ImageAdjustments,d:ImageEditDocument,action:(UiAction)->Unit){
    Text("$label · neutral $neutral")
    Slider(value.toFloat(),{action(UiAction.ChangeImage(d.copy(adjustments=change(it.toDouble())),false))},valueRange=min.toFloat()..max.toFloat(),onValueChangeFinished={action(UiAction.ChangeImage(d,true))},modifier=Modifier.heightIn(min=52.dp))
    NumberField(label,value,min,max){action(UiAction.ChangeImage(d.copy(adjustments=change(it))))}
    Text(help,style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={action(UiAction.ChangeImage(d.copy(adjustments=change(neutral))))}){Text("Reset $label")}
}
