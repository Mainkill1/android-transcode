@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui.image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import dev.forma.app.UiAction
import dev.forma.core.Capabilities
import dev.forma.core.image.*
@Composable fun ImageExportPanel(d:ImageEditDocument,info:ImageInfo,caps:Capabilities,action:(UiAction)->Unit){
    val p=d.output
    fun change(v:ImageOutputPolicy)=action(UiAction.ChangeImage(d.copy(output=v)))
    Text("Format")
    FlowRow{ImageFormat.entries.forEach{format->FilterChip(onClick={change(p.copy(format=format))},selected=p.format==format,label={Text(format.name)},enabled=format==ImageFormat.AUTO || format.encoder in caps.encoders)}}
    val requested=ImageJobSpec("preview",d,info);val format=ImagePlanner.resolveFormat(info,requested,caps)
    Text("${format.name} · ${if(format.encoder in caps.encoders)"Available" else "Unavailable in this build"}",style=MaterialTheme.typography.bodySmall)
    when(format){
        ImageFormat.JPEG->NumberField("JPEG quality",p.jpegQuality.toDouble(),1.0,100.0,true){change(p.copy(jpegQuality=it.toInt()))}
        ImageFormat.WEBP->{Toggle("Lossless WebP",p.lossless){change(p.copy(lossless=it))};if(!p.lossless)NumberField("WebP quality",p.webpQuality.toDouble(),1.0,100.0,true){change(p.copy(webpQuality=it.toInt()))}}
        ImageFormat.PNG->{Text("Lossless PNG encoding");NumberField("PNG compression effort",p.pngCompression.toDouble(),0.0,9.0,true){change(p.copy(pngCompression=it.toInt()))}}
        else->Unit
    }
    Text("Lossless encoding preserves rendered pixels; edits may change the source pixels.",style=MaterialTheme.typography.bodySmall)
    if(format==ImageFormat.JPEG){
        Toggle("Flatten alpha for JPEG",p.flatten!=null){change(p.copy(flatten=if(it)Rgba(255,255,255) else null))}
        if(p.flatten!=null)ColorField("JPEG background",p.flatten!!){if(it.alpha==255)change(p.copy(flatten=it))}
        else if(info.alpha==ImageAlpha.PRESENT)Text("Blocked for this image · choose an opaque background before JPEG export.",color=MaterialTheme.colorScheme.error)
    }
    Toggle("Use upload limit",p.targetBytes!=null){change(p.copy(targetBytes=if(it)10000000L else null))}
    p.targetBytes?.let{NumberField("Limit bytes",it.toDouble(),32000.0,2000000000.0,true){v->change(p.copy(targetBytes=v.toLong()))}}
    Text(if(p.targetBytes!=null)"Final file must be strictly below ${p.targetBytes} bytes. At most seven attempts." else "No size guarantee.",style=MaterialTheme.typography.bodySmall)
    Toggle("Allow resize to fit",p.allowResizeToFit){change(p.copy(allowResizeToFit=it))}
    Text("Retries retain format, crop, objects and alpha. Exact size changes require this consent.",style=MaterialTheme.typography.bodySmall)
    Text("Metadata · strip personal tags, thumbnails and edit history; normalize orientation and tag sRGB.",style=MaterialTheme.typography.bodySmall)
    val result=runCatching{ImageGeometry.resolve(info,d,ImageAttempt(0,format,90))}
    result.onSuccess {Text("Requested output · ${it.outputSize.width} × ${it.outputSize.height}")}
    result.exceptionOrNull()?.let{Text(it.message?:"Invalid geometry",color=MaterialTheme.colorScheme.error)}
    Text("Convert verifies a private copy. Save copy and Share are available from the queue after verification.",style=MaterialTheme.typography.bodySmall)
}
