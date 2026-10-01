@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.forma.app.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.forma.core.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Source-time filmstrip; local bracket changes become one committed trim on release. */
@Composable internal fun VideoTimeline(source:Source,trim:Trim,playheadMs:Long,
    onSeek:(Long)->Unit,onTrim:(Trim)->Unit,modifier:Modifier=Modifier,
    loadFrames:(suspend (Source,List<Long>)->List<Bitmap?>)?=null) {
    if(source.durationMs<TimelineViewport.MIN_KEEP_MS) {
        Text("This video is too short to trim.")
        return
    }
    val context=LocalContext.current
    val density=LocalDensity.current
    var viewport by remember(source.uri) {mutableStateOf(TimelineViewport.fit(source.durationMs,trim))}
    var dragging by remember(source.uri) {mutableStateOf(false)}
    var exact by remember(source.uri) {mutableStateOf(false)}
    var widthPx by remember(source.uri) {mutableIntStateOf(0)}
    var frames by remember(source.uri) {mutableStateOf<List<Bitmap?>>(emptyList())}
    var frameError by remember(source.uri) {mutableStateOf(false)}
    val latestSeek by rememberUpdatedState(onSeek)
    val latestTrim by rememberUpdatedState(onTrim)
    LaunchedEffect(source.uri,trim) {
        if(!dragging && viewport.trim!=trim)viewport=viewport.copy(trim=trim)
    }
    LaunchedEffect(source.uri,playheadMs) {
        if(!dragging && viewport.playheadMs!=playheadMs)viewport=viewport.movePlayhead(playheadMs)
    }
    LaunchedEffect(source.uri,viewport.visibleStartMs,viewport.visibleDurationMs) {
        frames=emptyList();frameError=false
        delay(90)
        val times=(0 until 7).map {viewport.visibleStartMs+viewport.visibleDurationMs*it/6}
        try {
            frames=withContext(Dispatchers.IO) {
                (loadFrames ?: {s,t->decodeTimelineFrames(context,s,t)})(source,times)
            }.take(7)
            ensureActive()
            frameError=frames.all {it==null}
        } catch(cancel:CancellationException) {throw cancel}
        catch(_:Exception) {frames=emptyList();frameError=true}
    }
    Column(modifier.fillMaxWidth().testTag("video-timeline"),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Start ${TimelineTimecode.format(viewport.trim.startMs)}",style=MaterialTheme.typography.labelMedium)
            Text("End ${TimelineTimecode.format(viewport.trim.endMs ?: source.durationMs)}",style=MaterialTheme.typography.labelMedium)
            Text("Kept ${TimelineTimecode.format((viewport.trim.endMs ?: source.durationMs)-viewport.trim.startMs)}",
                style=MaterialTheme.typography.labelMedium)
        }
        Text("Window ${TimelineTimecode.format(viewport.visibleStartMs)} – ${TimelineTimecode.format(viewport.visibleStartMs+viewport.visibleDurationMs)}",
            style=MaterialTheme.typography.labelSmall)
        Box(Modifier.fillMaxWidth().height(48.dp).onSizeChanged {widthPx=it.width}) {
            TimelineHandle("Start",TrimEdge.START,viewport,widthPx,source.durationMs,density,
                onGesture={dragging=it},onUpdate={next->viewport=next;latestSeek(next.playheadMs)},
                onCommit={latestTrim(it);viewport=viewport.copy(trim=trim)})
        }
        Box(Modifier.fillMaxWidth().height(94.dp).testTag("video-timeline-filmstrip")
            .semantics {contentDescription="Video filmstrip, ${frames.count {it!=null}} frames"}
            .onSizeChanged {widthPx=it.width}
            .pointerInput(source.uri) {detectTransformGestures {centroid,pan,zoom,_ ->
                viewport=viewport.pinch(centroid.x.toDouble(),zoom.toDouble(),size.width.toDouble())
                    .pan(pan.x.toDouble(),size.width.toDouble())
            }}
            .pointerInput(source.uri) {detectTapGestures {point ->
                latestSeek(viewport.timeAt(point.x.toDouble(),size.width.toDouble()))
            }}) {
            Row(Modifier.fillMaxSize()) {
                repeat(7) {index ->
                    val bitmap=frames.getOrNull(index)
                    Box(Modifier.weight(1f).fillMaxHeight().background(if(index%2==0)Color(0xff252a31) else Color(0xff30353d)),
                        contentAlignment=Alignment.Center) {
                        if(bitmap!=null)Image(bitmap.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                        else Text("·",color=Color.LightGray)
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val windowWidth=size.width.toDouble()
                val start=viewport.xAt(viewport.trim.startMs,windowWidth).toFloat().coerceIn(0f,size.width)
                val end=viewport.xAt(viewport.trim.endMs ?: source.durationMs,windowWidth).toFloat().coerceIn(0f,size.width)
                drawRect(Color.Black.copy(alpha=.55f),size=androidx.compose.ui.geometry.Size(start,size.height))
                drawRect(Color.Black.copy(alpha=.55f),topLeft=Offset(end,0f),
                    size=androidx.compose.ui.geometry.Size((size.width-end).coerceAtLeast(0f),size.height))
                val play=viewport.xAt(playheadMs,windowWidth).toFloat()
                if(play in 0f..size.width)drawLine(Color(0xfff8d56b),Offset(play,0f),Offset(play,size.height),3.dp.toPx())
            }
        }
        Box(Modifier.fillMaxWidth().height(48.dp).onSizeChanged {widthPx=it.width}) {
            TimelineHandle("End",TrimEdge.END,viewport,widthPx,source.durationMs,density,
                onGesture={dragging=it},onUpdate={next->viewport=next;latestSeek(next.playheadMs)},
                onCommit={latestTrim(it);viewport=viewport.copy(trim=trim)})
        }
        if(frameError)Text("Frames unavailable; time controls still work.",style=MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            FormaTextButton(onClick={viewport=viewport.zoomToSelection()}) {Text("Zoom to selection")}
            FormaTextButton(onClick={viewport=viewport.fit()}) {Text("Fit timeline")}
            FormaTextButton(onClick={exact=true}) {Text("Exact times")}
        }
    }
    if(exact) ExactTimelineTimes(viewport.trim,source.durationMs,onDismiss={exact=false},onApply={value ->
        latestTrim(value);latestSeek(value.startMs)
        viewport=viewport.copy(trim=trim,playheadMs=value.startMs)
        exact=false
    })
}

@Composable private fun TimelineHandle(label:String,edge:TrimEdge,viewport:TimelineViewport,widthPx:Int,
    durationMs:Long,density:androidx.compose.ui.unit.Density,onGesture:(Boolean)->Unit,
    onUpdate:(TimelineViewport)->Unit,onCommit:(Trim)->Unit) {
    val handlePx=with(density) {48.dp.roundToPx()}
    val limit=(widthPx-handlePx).coerceAtLeast(0)
    val time=if(edge==TrimEdge.START)viewport.trim.startMs else viewport.trim.endMs ?: durationMs
    val left=(viewport.xAt(time,widthPx.coerceAtLeast(1).toDouble()).roundToInt()-handlePx/2).coerceIn(0,limit)
    var original by remember {mutableStateOf<Trim?>(null)}
    var draft by remember {mutableStateOf<TimelineViewport?>(null)}
    var position by remember {mutableDoubleStateOf(0.0)}
    val latestViewport by rememberUpdatedState(viewport)
    val currentTime by rememberUpdatedState(time)
    Box(Modifier.offset {IntOffset(left,0)}.size(48.dp).testTag("timeline-${label.lowercase()}-handle")
        .semantics {
            contentDescription="$label bracket"
            stateDescription=TimelineTimecode.format(time)
            customActions=listOf(
                CustomAccessibilityAction("$label forward 1 millisecond") {
                    runCatching {latestViewport.moveEdge(edge,time+1)}.getOrNull()?.let {onUpdate(it);onCommit(it.trim)}!=null
                },
                CustomAccessibilityAction("$label back 1 millisecond") {
                    runCatching {latestViewport.moveEdge(edge,time-1)}.getOrNull()?.let {onUpdate(it);onCommit(it.trim)}!=null
                })
        }
        .pointerInput(edge,widthPx) {detectDragGestures(onDragStart={
            original=latestViewport.trim
            draft=latestViewport
            position=latestViewport.xAt(currentTime,widthPx.coerceAtLeast(1).toDouble())
            onGesture(true)
        },onDrag={change,drag ->
            position+=drag.x
            runCatching {(draft ?: latestViewport).dragEdge(edge,position,widthPx.coerceAtLeast(1).toDouble(),48.dp.toPx().toDouble())}
                .getOrNull()?.let {draft=it;onUpdate(it)}
            change.consume()
        },onDragEnd={
            val before=original
            val final=draft?.trim
            if(before!=null && final!=null && final!=before)onCommit(final)
            original=null;draft=null;onGesture(false)
        },onDragCancel={
            original?.let {onUpdate((draft ?: latestViewport).copy(trim=it,playheadMs=if(edge==TrimEdge.START)it.startMs else it.endMs ?: durationMs))}
            original=null;draft=null;onGesture(false)
        })},contentAlignment=Alignment.Center) {
        Text(if(edge==TrimEdge.START)"[" else "]",style=MaterialTheme.typography.headlineMedium,
            color=MaterialTheme.colorScheme.primary)
    }
}

@Composable private fun ExactTimelineTimes(trim:Trim,durationMs:Long,onDismiss:()->Unit,onApply:(Trim)->Unit) {
    var start by remember {mutableStateOf(trim.startMs.toString())}
    var end by remember {mutableStateOf((trim.endMs ?: durationMs).toString())}
    val a=start.toLongOrNull();val b=end.toLongOrNull()
    val valid=a!=null && b!=null && a>=0 && b<=durationMs && b-a>=TimelineViewport.MIN_KEEP_MS
    AlertDialog(onDismissRequest=onDismiss,title={Text("Exact trim times")},text={Column {
        OutlinedTextField(start,{start=it.take(15)},label={Text("Start (ms)")},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
        OutlinedTextField(end,{end=it.take(15)},label={Text("End (ms)")},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
    }},confirmButton={FormaTextButton(onClick={onApply(Trim(a!!,b!!))},enabled=valid){Text("Apply times")}},
        dismissButton={FormaTextButton(onClick=onDismiss){Text("Cancel")}})
}

private suspend fun decodeTimelineFrames(context:android.content.Context,source:Source,times:List<Long>):List<Bitmap?> {
    if(Build.VERSION.SDK_INT<27 && source.width.toLong()*source.height*4>12L*1024*1024)
        return List(times.size){null}
    val retriever=MediaMetadataRetriever()
    try {
        retriever.setDataSource(context,Uri.parse(source.uri))
        val display=PreviewGeometry.displaySize(source.width,source.height,source.displayRotationDegrees)
            ?: (source.width to source.height)
        val scale=min(160.0/display.first,90.0/display.second).coerceAtMost(1.0)
        val targetW=(display.first*scale).roundToInt().coerceAtLeast(1)
        val targetH=(display.second*scale).roundToInt().coerceAtLeast(1)
        return times.map {ms ->
            currentCoroutineContext().ensureActive()
            try {
                val frame=if(Build.VERSION.SDK_INT>=27)
                    retriever.getScaledFrameAtTime(ms*1_000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,targetW,targetH)
                else retriever.getFrameAtTime(ms*1_000,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let {decoded ->
                    if(decoded.allocationByteCount>12L*1024*1024) {decoded.recycle();null}
                    else {
                        val raw=source.width.toDouble()/source.height
                        val shown=display.first.toDouble()/display.second
                        val ratio=decoded.width.toDouble()/decoded.height
                        val needsTurn=source.displayRotationDegrees!=0 && abs(ratio-raw)<abs(ratio-shown)
                        val turned=if(needsTurn)Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,
                            Matrix().apply {postRotate(-(source.displayRotationDegrees ?: 0).toFloat())},true)
                        else decoded
                        if(turned!==decoded)decoded.recycle()
                        turned
                    }
                }
            } catch(cancel:CancellationException) {throw cancel}
            catch(_:Exception) {null}
        }
    } finally {retriever.release()}
}
