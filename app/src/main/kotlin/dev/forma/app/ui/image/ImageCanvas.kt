@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui.image
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import dev.forma.app.UiAction
import dev.forma.app.image.*
import dev.forma.app.ui.FormaTextButton as TextButton
import dev.forma.core.image.*
import kotlinx.coroutines.*
import kotlin.math.*

@Composable fun ImageCanvas(d:ImageEditDocument,info:ImageInfo,preview:ImagePreviewState,tool:String,action:(UiAction)->Unit) {
    val context=LocalContext.current
    var original by remember {mutableStateOf(false)};var split by remember {mutableStateOf(false)}
    var zoom by remember {mutableFloatStateOf(1f)};var pan by remember {mutableStateOf(Offset.Zero)}
    var background by remember {mutableStateOf("Checkerboard")};var custom by remember {mutableStateOf(Rgba(200,200,200))};var grid by remember {mutableStateOf(false)}
    val source by produceState<Bitmap?>(null,d.source.uri){value=withContext(Dispatchers.IO){runCatching{ImageDisplayAdapter.original(context,d.source.uri,info).bitmap}.getOrNull()}}
    val edited by produceState<Bitmap?>(null,preview.path,preview.region){value=preview.path?.let{withContext(Dispatchers.IO){
        val r=preview.region
        if(r==null)BitmapFactory.decodeFile(it)else {
            val decoder=BitmapRegionDecoder.newInstance(it,false)
            try {decoder.decodeRegion(Rect(r.left,r.top,r.left+r.width,r.top+r.height),BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888})}finally{decoder.recycle()}
        }
    }}}
    DisposableEffect(source){onDispose{source?.recycle()}}
    DisposableEffect(edited){onDispose{edited?.recycle()}}
    val bitmap=if(original || edited==null)source else edited
    val geometry=remember(d,info){runCatching{ImageGeometry.resolve(info,d,ImageAttempt(0,ImageFormat.PNG,90))}.getOrNull()}
    val currentDocument by rememberUpdatedState(d)
    val currentGeometry by rememberUpdatedState(geometry)
    var dimensions by remember {mutableStateOf(IntSize.Zero)}
    fun displayScale():Float {
        val b=bitmap ?:return 1f
        if(dimensions.width==0)return 1f
        val fit=min(dimensions.width.toFloat()/b.width,dimensions.height.toFloat()/b.height)
        return if(preview.actualPixels && !original)zoom else fit*zoom
    }
    fun sourceAt(screen:Offset):ImagePoint? {
        val g=currentGeometry ?:return null;val b=bitmap ?:return null;val s=displayScale()
        val px=(screen.x-(dimensions.width-b.width*s)/2-pan.x)/s
        val py=(screen.y-(dimensions.height-b.height*s)/2-pan.y)/s
        return g.outputToSource.map(ImagePoint((px*g.outputSize.width/b.width).toDouble(),(py*g.outputSize.height/b.height).toDouble()))
    }
    var dragCorner by remember {mutableStateOf<Int?>(null)}
    var dragBase by remember {mutableStateOf<NormalizedCrop?>(null)}
    Column(Modifier.fillMaxWidth()){
        Text(if(original || edited==null)"Original" else preview.status,Modifier.semantics{liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(300.dp).testTag("image-canvas").semantics{contentDescription="Image canvas. Pinch to zoom and drag to pan. Crop corners also have exact numeric fields."}
            .pointerInput(tool,preview.actualPixels){
                if(tool=="Crop" && !preview.actualPixels)detectDragGestures(onDragStart={point->
                    val p=sourceAt(point);val g=currentGeometry
                    if(p!=null && g!=null){val cx=p.x/g.orientedSize.width;val cy=p.y/g.orientedSize.height;val c=currentDocument.crop
                        val corners=listOf(c.left to c.top,c.right to c.top,c.right to c.bottom,c.left to c.bottom)
                        val nearest=corners.indices.minByOrNull{(corners[it].first-cx).pow(2)+(corners[it].second-cy).pow(2)}
                        dragCorner=nearest;dragBase=c
                    }
                },onDragEnd={dragCorner=null;dragBase=null;action(UiAction.ChangeImage(currentDocument,true))},onDragCancel={dragCorner=null;dragBase=null},onDrag={change,delta->
                    val corner=dragCorner;val p=sourceAt(change.position);val g=currentGeometry
                    if(corner!=null && p!=null && g!=null){val x=(p.x/g.orientedSize.width).coerceIn(0.0,1.0);val y=(p.y/g.orientedSize.height).coerceIn(0.0,1.0);val c=currentDocument.crop
                        val crop=when(corner){0->c.copy(left=min(x,c.right-.001),top=min(y,c.bottom-.001));1->c.copy(right=max(x,c.left+.001),top=min(y,c.bottom-.001));2->c.copy(right=max(x,c.left+.001),bottom=max(y,c.top+.001));else->c.copy(left=min(x,c.right-.001),bottom=max(y,c.top+.001))}
                        action(UiAction.ChangeImage(currentDocument.copy(crop=crop),false));change.consume()
                    }else pan+=delta
                })else detectTransformGestures{_,delta,factor,_->zoom=(zoom*factor).coerceIn(.1f,16f);pan+=delta}
            }
            .pointerInput(tool){if(tool=="Crop")detectTransformGestures{_,delta,factor,_->if(factor!=1f){zoom=(zoom*factor).coerceIn(.1f,16f);pan+=delta}}}
        ){
            dimensions=IntSize(size.width.toInt(),size.height.toInt())
            val bg=when(background){"Light"->Color.White;"Dark"->Color(0xff202020);"Custom"->Color(custom.red/255f,custom.green/255f,custom.blue/255f,1f);else->Color.LightGray}
            drawRect(bg)
            if(background=="Checkerboard"){val step=16.dp.toPx();for(x in 0..(size.width/step).toInt())for(y in 0..(size.height/step).toInt())if((x+y)%2==0)drawRect(Color.Gray,Offset(x*step,y*step),androidx.compose.ui.geometry.Size(step,step))}
            if(bitmap!=null && !bitmap.isRecycled){
                val s=displayScale();val start=Offset((size.width-bitmap.width*s)/2+pan.x,(size.height-bitmap.height*s)/2+pan.y)
                drawImage(bitmap.asImageBitmap(),dstOffset=IntOffset(start.x.toInt(),start.y.toInt()),dstSize=IntSize((bitmap.width*s).toInt().coerceAtLeast(1),(bitmap.height*s).toInt().coerceAtLeast(1)))
                if(split && source!=null && !source!!.isRecycled){clipRect(right=size.width/2){drawImage(source!!.asImageBitmap(),dstOffset=IntOffset(start.x.toInt(),start.y.toInt()),dstSize=IntSize((bitmap.width*s).toInt().coerceAtLeast(1),(bitmap.height*s).toInt().coerceAtLeast(1)))};drawLine(Color.White,Offset(size.width/2,0f),Offset(size.width/2,size.height),2f)}
                if(grid)for(i in 1..2){drawLine(Color.White.copy(alpha=.6f),Offset(start.x+bitmap.width*s*i/3,start.y),Offset(start.x+bitmap.width*s*i/3,start.y+bitmap.height*s),1f);drawLine(Color.White.copy(alpha=.6f),Offset(start.x,start.y+bitmap.height*s*i/3),Offset(start.x+bitmap.width*s,start.y+bitmap.height*s*i/3),1f)}
                if(tool=="Crop" && geometry!=null && !preview.actualPixels){drawRect(Color(0xff55bbee),start,androidx.compose.ui.geometry.Size(bitmap.width*s,bitmap.height*s),style=Stroke(2f));for(x in listOf(0f,bitmap.width*s))for(y in listOf(0f,bitmap.height*s))drawCircle(Color(0xff55bbee),10.dp.toPx(),start+Offset(x,y))}
            }
        }
        FlowRow{
            TextButton(onClick={zoom=1f;pan=Offset.Zero;action(UiAction.RenderImage(false))}){Text("Fit")}
            TextButton(onClick={zoom=1f;pan=Offset.Zero;action(UiAction.RenderImage(true))}){Text("100%")}
            TextButton(onClick={zoom=(zoom/1.25f).coerceAtLeast(.1f)}){Text("−")};TextButton(onClick={zoom=(zoom*1.25f).coerceAtMost(16f)}){Text("+")}
            TextButton(onClick={original=!original},modifier=Modifier.pointerInput(Unit){detectTapGestures(onPress={original=true;tryAwaitRelease();original=false})}){Text("Hold Original")}
            TextButton(onClick={split=!split}){Text(if(split)"Edited only" else "Split")}
        }
        preview.region?.let{r->
            Text("Actual output pixels · ${r.left}, ${r.top} · ${r.width} × ${r.height}",style=MaterialTheme.typography.bodySmall)
            FlowRow {
                TextButton(onClick={action(UiAction.RenderImage(true,r.left-r.width/2,r.top+r.height/2))}){Text("Inspect left")}
                TextButton(onClick={action(UiAction.RenderImage(true,r.left+r.width*3/2,r.top+r.height/2))}){Text("Inspect right")}
                TextButton(onClick={action(UiAction.RenderImage(true,r.left+r.width/2,r.top-r.height/2))}){Text("Inspect up")}
                TextButton(onClick={action(UiAction.RenderImage(true,r.left+r.width/2,r.top+r.height*3/2))}){Text("Inspect down")}
            }
        }
        if(split)Text("Split · Original left / edited Preview right",style=MaterialTheme.typography.bodySmall)
        NumberField("Zoom multiplier",zoom.toDouble(),.1,16.0){zoom=it.toFloat()}
        Toggle("Thirds grid",grid){grid=it}
        FlowRow{listOf("Checkerboard","Light","Dark","Custom").forEach{value->FilterChip(onClick={background=value},selected=background==value,label={Text(value)})}}
        if(background=="Custom")ColorField("Preview background",custom){custom=it}
        Text("Preview background is display-only. JPEG background is set in Export.",style=MaterialTheme.typography.bodySmall)
    }
}
