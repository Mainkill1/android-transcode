package dev.forma.core

import kotlin.math.roundToLong

enum class TrimEdge { START, END }

/** Millisecond storage with Double precision only across the visible screen window. */
data class TimelineViewport(val durationMs:Long,val visibleStartMs:Long,val visibleDurationMs:Long,
    val playheadMs:Long,val trim:Trim) {
    init {
        require(durationMs>0 && visibleDurationMs in minimumWindow(durationMs)..durationMs)
        require(visibleStartMs in 0..durationMs-visibleDurationMs)
        require(playheadMs in 0..durationMs)
        require(trim.startMs>=0 && (trim.endMs ?: durationMs)<=durationMs &&
            (trim.endMs ?: durationMs)-trim.startMs>=MIN_KEEP_MS)
    }

    fun timeAt(x:Double,width:Double):Long {
        require(width>0 && width.isFinite() && x.isFinite())
        return (visibleStartMs+(x.coerceIn(0.0,width)/width*visibleDurationMs).roundToLong())
            .coerceIn(0,durationMs)
    }

    fun xAt(timeMs:Long,width:Double):Double {
        require(width>0 && width.isFinite())
        return (timeMs.coerceIn(0,durationMs)-visibleStartMs).toDouble()/visibleDurationMs*width
    }

    fun pinch(anchorX:Double,scale:Double,width:Double):TimelineViewport {
        require(scale>0 && scale.isFinite())
        val anchored=timeAt(anchorX,width)
        val next=(visibleDurationMs/scale).roundToLong().coerceIn(minimumWindow(durationMs),durationMs)
        val ratio=anchorX.coerceIn(0.0,width)/width
        val start=(anchored-(ratio*next).roundToLong()).coerceIn(0,durationMs-next)
        return copy(visibleStartMs=start,visibleDurationMs=next)
    }

    fun pan(deltaPx:Double,width:Double):TimelineViewport {
        require(width>0 && width.isFinite() && deltaPx.isFinite())
        val shift=(deltaPx/width*visibleDurationMs).roundToLong()
        return copy(visibleStartMs=(visibleStartMs-shift).coerceIn(0,durationMs-visibleDurationMs))
    }

    fun fit()=copy(visibleStartMs=0,visibleDurationMs=durationMs)

    fun zoomToSelection():TimelineViewport {
        val end=trim.endMs ?: durationMs
        val width=(end-trim.startMs).coerceAtLeast(minimumWindow(durationMs))
        val start=(trim.startMs-(width-(end-trim.startMs))/2).coerceIn(0,durationMs-width)
        return copy(visibleStartMs=start,visibleDurationMs=width)
    }

    fun movePlayhead(timeMs:Long)=copy(playheadMs=timeMs.coerceIn(0,durationMs))

    fun moveEdge(edge:TrimEdge,timeMs:Long):TimelineViewport {
        val candidate=timeMs.coerceIn(0,durationMs)
        val end=trim.endMs ?: durationMs
        val next=when(edge) {
            TrimEdge.START -> Trim(candidate,end)
            TrimEdge.END -> Trim(trim.startMs,candidate)
        }
        require((next.endMs ?: durationMs)-next.startMs>=MIN_KEEP_MS) {"Keep at least 50 ms."}
        return copy(trim=next,playheadMs=candidate)
    }

    /** Move the window by a small bounded step when a handle reaches its screen edge. */
    fun dragEdge(edge:TrimEdge,x:Double,width:Double,edgeZonePx:Double):TimelineViewport {
        require(edgeZonePx>=0 && edgeZonePx.isFinite())
        val nudge=(visibleDurationMs/10).coerceAtLeast(1)
        val shifted=when {
            x<=edgeZonePx -> copy(visibleStartMs=(visibleStartMs-nudge).coerceAtLeast(0))
            x>=width-edgeZonePx -> copy(visibleStartMs=(visibleStartMs+nudge).coerceAtMost(durationMs-visibleDurationMs))
            else -> this
        }
        return shifted.moveEdge(edge,shifted.timeAt(x,width))
    }

    companion object {
        const val MIN_KEEP_MS=50L
        fun minimumWindow(durationMs:Long)=minOf(1_000L,durationMs)
        fun fit(durationMs:Long,trim:Trim=Trim()):TimelineViewport {
            require(durationMs>=MIN_KEEP_MS)
            return TimelineViewport(durationMs,0,durationMs,trim.startMs,trim)
        }
    }
}

/** A whole bracket gesture turns into exactly one history command on release. */
class TrimGesture(private val clipId:String,private val original:Trim,private val durationMs:Long) {
    private var draft=original
    private var canceled=false
    fun move(edge:TrimEdge,timeMs:Long):TrimGesture {
        check(!canceled)
        draft=TimelineViewport.fit(durationMs,draft).moveEdge(edge,timeMs).trim
        return this
    }
    fun cancel():TrimGesture {canceled=true;return this}
    fun commit():TimelineCommand.TrimClip?=if(canceled || draft==original)null else TimelineCommand.TrimClip(clipId,draft)
}
