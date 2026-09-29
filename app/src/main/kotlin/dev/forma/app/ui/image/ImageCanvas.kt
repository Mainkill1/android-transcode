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

private data class CropDrag(val gesture:ImageCropGesture,val origin:Offset,val unitsPerPixelX:Double,val unitsPerPixelY:Double)
private data class DisplayedImage(val bitmap:Bitmap,val path:String,val geometry:ImageGeometryResult?)

@Composable fun ImageCanvas(d:ImageEditDocument,info:ImageInfo,preview:ImagePreviewState,tool:String,action:(UiAction)->Unit) {
    val context=LocalContext.current
    var original by remember {mutableStateOf(false)};var split by remember {mutableStateOf(false)}
    var zoom by remember {mutableFloatStateOf(1f)};var pan by remember {mutableStateOf(Offset.Zero)}
    var background by remember {mutableStateOf("Checkerboard")};var custom by remember {mutableStateOf(Rgba(200,200,200))};var grid by remember {mutableStateOf(false)}
    val source by produceState<Bitmap?>(null,d.source.uri){value=withContext(Dispatchers.IO){runCatching{ImageDisplayAdapter.original(context,d.source.uri,info).bitmap}.getOrNull()}}
    val editedFrame by produceState<DisplayedImage?>(null,preview.path,preview.region,preview.geometry){value=preview.path?.let{ImageDisplayDecoding.decode {
        val r=preview.region
        val decoded=if(r==null)BitmapFactory.decodeFile(it)else {
            val decoder=BitmapRegionDecoder.newInstance(it,false)
            try {decoder.decodeRegion(Rect(r.left,r.top,r.left+r.width,r.top+r.height),BitmapFactory.Options().apply{inPreferredConfig=Bitmap.Config.ARGB_8888})}finally{decoder.recycle()}
        }
        decoded?.let{b->DisplayedImage(b,it,preview.geometry)}
    }}}
    val edited=editedFrame?.bitmap
    // Compose and render-thread display lists can retain published bitmaps after
    // recomposition. Let GC release them when those references are gone.
    val bitmap=if(original || edited==null)source else edited
    val requestedGeometry=remember(d,info){runCatching{ImageGeometry.resolve(info,d,ImageAttempt(0,ImageFormat.PNG,90))}.getOrNull()}
    val geometry=if(original || edited==null)requestedGeometry else editedFrame?.geometry ?:requestedGeometry
    val currentDocument by rememberUpdatedState(d)
    val currentGeometry by rememberUpdatedState(geometry)
    val currentBitmap by rememberUpdatedState(bitmap)
    val currentOriginal by rememberUpdatedState(original || edited==null)
    val currentActual by rememberUpdatedState(preview.actualPixels)
    var dimensions by remember {mutableStateOf(IntSize.Zero)}
    fun displayScale():Float {
        val b=currentBitmap ?:return 1f
        if(dimensions.width==0)return 1f
        val fit=min(dimensions.width.toFloat()/b.width,dimensions.height.toFloat()/b.height)
        return if(currentActual && !currentOriginal)zoom else fit*zoom
    }
    fun cropScreenCorners():List<Offset> {
        val g=currentGeometry ?:return emptyList();val b=currentBitmap ?:return emptyList();val c=currentDocument.crop;val scale=displayScale()
        val start=Offset((dimensions.width-b.width*scale)/2+pan.x,(dimensions.height-b.height*scale)/2+pan.y)
        return listOf(c.left to c.top,c.right to c.top,c.right to c.bottom,c.left to c.bottom).map{(x,y)->
            val canonical=ImagePoint(x*g.orientedSize.width,y*g.orientedSize.height)
            val p=if(currentOriginal)canonical else g.sourceToOutput.map(canonical)
            val size=if(currentOriginal)g.orientedSize else g.outputSize
            start+Offset((p.x/size.width*b.width*scale).toFloat(),(p.y/size.height*b.height*scale).toFloat())
        }
    }
    val density=androidx.compose.ui.platform.LocalDensity.current
    val hitRadius=with(density){28.dp.toPx()}
    var dragCorner by remember {mutableStateOf<Int?>(null)}
    var dragBase by remember {mutableStateOf<NormalizedCrop?>(null)}
    var dragMapping by remember {mutableStateOf<CropDrag?>(null)}
    Column(Modifier.fillMaxWidth()){
        Text(if(original || edited==null)"Original" else if(dragCorner!=null || editedFrame?.path!=preview.path)"Updating" else preview.status,Modifier.semantics{liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.labelLarge)
        Canvas(Modifier.fillMaxWidth().height(300.dp).testTag("image-canvas").semantics{contentDescription="Image canvas. Pinch to zoom and drag to pan. Crop corners also have exact numeric fields."}
            .pointerInput(tool,preview.actualPixels){
                if(tool=="Crop" && !preview.actualPixels)detectDragGestures(onDragStart={point->
                    val corners=cropScreenCorners()
                    dragCorner=corners.indices.minByOrNull{(corners[it]-point).getDistanceSquared()}?.takeIf{(corners[it]-point).getDistance()<=hitRadius}
                    dragBase=if(dragCorner!=null)currentDocument.crop else null
                    val g=currentGeometry;val b=currentBitmap;val s=displayScale()
                    dragMapping=if(dragCorner!=null && g!=null && b!=null){
                        val units=if(currentOriginal)g.orientedSize else g.outputSize
                        CropDrag(ImageCropGesture(g,currentDocument.crop,dragCorner!!,currentDocument.cropAspectRatio,currentOriginal),Offset((dimensions.width-b.width*s)/2+pan.x,(dimensions.height-b.height*s)/2+pan.y),units.width.toDouble()/(b.width*s),units.height.toDouble()/(b.height*s))
                    }else null
                },onDragEnd={if(dragCorner!=null)action(UiAction.ChangeImage(currentDocument,true));dragCorner=null;dragBase=null;dragMapping=null},onDragCancel={
                    dragBase?.let{action(UiAction.ChangeImage(currentDocument.copy(crop=it),true))};dragCorner=null;dragBase=null;dragMapping=null
                },onDrag={change,delta->
                    val mapping=dragMapping
                    if(mapping!=null){
                        val pixel=change.position-mapping.origin
                        val crop=mapping.gesture.drag(ImagePoint(pixel.x*mapping.unitsPerPixelX,pixel.y*mapping.unitsPerPixelY))
                        action(UiAction.ChangeImage(currentDocument.copy(crop=crop),false));change.consume()
                    }else {pan+=delta;change.consume()}
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
                if(split && !preview.actualPixels && source!=null && !source!!.isRecycled){clipRect(right=size.width/2){drawImage(source!!.asImageBitmap(),dstOffset=IntOffset(start.x.toInt(),start.y.toInt()),dstSize=IntSize((bitmap.width*s).toInt().coerceAtLeast(1),(bitmap.height*s).toInt().coerceAtLeast(1)))};drawLine(Color.White,Offset(size.width/2,0f),Offset(size.width/2,size.height),2f)}
                if(grid)for(i in 1..2){drawLine(Color.White.copy(alpha=.6f),Offset(start.x+bitmap.width*s*i/3,start.y),Offset(start.x+bitmap.width*s*i/3,start.y+bitmap.height*s),1f);drawLine(Color.White.copy(alpha=.6f),Offset(start.x,start.y+bitmap.height*s*i/3),Offset(start.x+bitmap.width*s,start.y+bitmap.height*s*i/3),1f)}
                if(tool=="Crop" && geometry!=null && !preview.actualPixels){
                    val corners=cropScreenCorners();if(corners.size==4){for(i in corners.indices)drawLine(Color(0xff55bbee),corners[i],corners[(i+1)%4],2f);corners.forEach{drawCircle(Color(0xff55bbee),10.dp.toPx(),it)}}
                }
            }
        }
        FlowRow{
            TextButton(onClick={zoom=1f;pan=Offset.Zero;action(UiAction.RenderImage(false))}){Text("Fit")}
            TextButton(onClick={zoom=1f;pan=Offset.Zero;action(UiAction.RenderImage(true))}){Text("100%")}
            TextButton(onClick={zoom=(zoom/1.25f).coerceAtLeast(.1f)}){Text("−")};TextButton(onClick={zoom=(zoom*1.25f).coerceAtMost(16f)}){Text("+")}
            TextButton(onClick={original=!original}){Text("Original")};TextButton(onClick={},modifier=Modifier.pointerInput(Unit){detectTapGestures(onPress={original=true;tryAwaitRelease();original=false})}){Text("Hold Original")}
            TextButton(onClick={split=!split},enabled=!preview.actualPixels){Text(if(split)"Edited only" else "Split")}
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
        if(split && !preview.actualPixels)Text("Split · Original left / edited Preview right",style=MaterialTheme.typography.bodySmall)
        NumberField("Zoom multiplier",zoom.toDouble(),.1,16.0){zoom=it.toFloat()}
        Toggle("Thirds grid",grid){grid=it}
        FlowRow{listOf("Checkerboard","Light","Dark","Custom").forEach{value->FilterChip(modifier=Modifier.heightIn(min=52.dp),onClick={background=value},selected=background==value,label={Text(value)})}}
        if(background=="Custom")ColorField("Preview background",custom){custom=it}
        Text("Preview background is display-only. JPEG background is set in Export.",style=MaterialTheme.typography.bodySmall)
    }
}
