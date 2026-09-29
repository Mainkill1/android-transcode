@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui.image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import dev.forma.app.UiAction
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.core.image.*
import java.util.UUID
@Composable fun ImageMarkupPanel(d:ImageEditDocument,action:(UiAction)->Unit) {
    var selected by rememberSaveable(d.source.hash){mutableStateOf<String?>(null)}
    var recent by rememberSaveable {mutableStateOf(listOf("000000FF","FFFFFFFF","FF0000FF","00000000"))}
    fun update(list:List<ImageAnnotation>)=action(UiAction.ChangeImage(d.copy(annotations=list)))
    fun edit(o:ImageAnnotation)=update(d.annotations.map{if(it.id==o.id)o else it})
    Text("Add object · source-relative coordinates")
    FlowRow{AnnotationKind.entries.forEach{kind->TextButton(onClick={val o=ImageAnnotation(UUID.randomUUID().toString(),kind,text=if(kind==AnnotationKind.TEXT)"Text" else "");update(d.annotations+o);selected=o.id},enabled=d.annotations.size<128){Text(kind.name.lowercase().replaceFirstChar{it.uppercase()})}}}
    FlowRow{d.annotations.forEachIndexed{i,o->FilterChip(onClick={selected=o.id},selected=selected==o.id,label={Text("${i+1} ${o.kind.name.lowercase()}")})}}
    val o=d.annotations.firstOrNull{it.id==selected}
    if(o!=null){
        Column(Modifier.onPreviewKeyEvent {event->
            if(event.type!=KeyEventType.KeyDown)false else {val delta=.005;val dx=when(event.key){Key.DirectionLeft->-delta;Key.DirectionRight->delta;else->0.0};val dy=when(event.key){Key.DirectionUp->-delta;Key.DirectionDown->delta;else->0.0}
                if(dx==0.0 && dy==0.0)false else {val x=dx.coerceIn(-o.left,1-o.right);val y=dy.coerceIn(-o.top,1-o.bottom);edit(o.copy(left=o.left+x,right=o.right+x,top=o.top+y,bottom=o.bottom+y));true}
            }
        }){
            NumberField("Object left",o.left,0.0,1.0){if(o.kind in setOf(AnnotationKind.LINE,AnnotationKind.ARROW) || it<o.right)edit(o.copy(left=it))};NumberField("Object top",o.top,0.0,1.0){if(o.kind in setOf(AnnotationKind.LINE,AnnotationKind.ARROW) || it<o.bottom)edit(o.copy(top=it))}
            NumberField("Object right",o.right,0.0,1.0){if(o.kind in setOf(AnnotationKind.LINE,AnnotationKind.ARROW) || it>o.left)edit(o.copy(right=it))};NumberField("Object bottom",o.bottom,0.0,1.0){if(o.kind in setOf(AnnotationKind.LINE,AnnotationKind.ARROW) || it>o.top)edit(o.copy(bottom=it))}
            if(o.kind==AnnotationKind.TEXT){OutlinedTextField(o.text,{if(it.codePointCount(0,it.length)<=4096)edit(o.copy(text=it))},label={Text("Text")},modifier=Modifier.fillMaxWidth())
                NumberField("Text size · source px",o.textSize,1.0,2048.0){edit(o.copy(textSize=it))}
                FlowRow{TextAlignment.entries.forEach{a->FilterChip(onClick={edit(o.copy(alignment=a))},selected=o.alignment==a,label={Text(a.name.lowercase())})}}
                FlowRow{listOf("sans-serif","serif","monospace").forEach{font->FilterChip(onClick={edit(o.copy(font=font))},selected=o.font==font,label={Text(font)})}}
            }
            if(o.kind!=AnnotationKind.REDACTION){NumberField("Stroke width · source px",o.strokeWidth,0.0,256.0){edit(o.copy(strokeWidth=it))};NumberField("Object opacity",o.opacity,0.0,1.0){edit(o.copy(opacity=it))}}
            ColorField(if(o.kind==AnnotationKind.REDACTION)"Redaction color (always opaque)" else "Color",o.color){color->edit(o.copy(color=if(o.kind==AnnotationKind.REDACTION)color.copy(alpha=255) else color));val value="%02X%02X%02X%02X".format(color.red,color.green,color.blue,color.alpha);recent=(listOf(value)+recent).distinct().take(8)}
            FlowRow{recent.forEach{value->TextButton(onClick={val n=value.toLong(16);edit(o.copy(color=Rgba(((n shr 24)and 255).toInt(),((n shr 16)and 255).toInt(),((n shr 8)and 255).toInt(),(n and 255).toInt())))}){Text(value)}}}
            if(o.kind in setOf(AnnotationKind.RECTANGLE,AnnotationKind.ELLIPSE))ColorField("Fill",o.fill){edit(o.copy(fill=it))}
            FlowRow{TextButton(onClick={val i=d.annotations.indexOf(o);if(i>0){val l=d.annotations.toMutableList();java.util.Collections.swap(l,i,i-1);update(l)}}){Text("Down")};TextButton(onClick={val i=d.annotations.indexOf(o);if(i<d.annotations.lastIndex){val l=d.annotations.toMutableList();java.util.Collections.swap(l,i,i+1);update(l)}}){Text("Up")};TextButton(onClick={val copy=o.copy(id=UUID.randomUUID().toString());update(d.annotations+copy);selected=copy.id},enabled=d.annotations.size<128){Text("Duplicate")};TextButton(onClick={update(d.annotations.filterNot{it.id==o.id});selected=null}){Text("Delete")}}
        }
    }
    Text("Redaction is a final opaque replacement above all other objects. Crop hides objects without deleting them.",style=MaterialTheme.typography.bodySmall)
    TextButton(onClick={update(emptyList());selected=null}){Text("Reset markup")}
}
