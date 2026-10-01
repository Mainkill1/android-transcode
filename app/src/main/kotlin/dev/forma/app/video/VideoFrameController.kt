package dev.forma.app.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import dev.forma.core.Source
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface VideoFrameState {
    data object Idle:VideoFrameState
    data class Loading(val sourceKey:String,val requestedMs:Long,val revision:Long):VideoFrameState
    data class Ready(val bitmap:Bitmap,val requestedMs:Long,val sourceKey:String,val revision:Long,
        val status:String):VideoFrameState
    data class Error(val sourceKey:String,val requestedMs:Long,val revision:Long,val message:String):VideoFrameState
}

interface VideoFrameExtractor {
    fun open(uri:String)
    suspend fun frame(timeUs:Long,width:Int,height:Int,scaled:Boolean):Bitmap?
    fun close()
}

private class RetrieverExtractor(private val context:Context):VideoFrameExtractor {
    private val retriever=MediaMetadataRetriever()
    override fun open(uri:String) { retriever.setDataSource(context,Uri.parse(uri)) }
    override suspend fun frame(timeUs:Long,width:Int,height:Int,scaled:Boolean):Bitmap? =
        if(scaled && Build.VERSION.SDK_INT>=27)
            retriever.getScaledFrameAtTime(timeUs,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,width,height)
        else retriever.getFrameAtTime(timeUs,MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    override fun close() { retriever.release() }
}

/** One active decoder and one published frame; a stale decode can never replace a newer request. */
class VideoFrameController(private val context:Context,private val scope:CoroutineScope,
    private val sdkInt:Int=Build.VERSION.SDK_INT,
    private val extractorFactory:()->VideoFrameExtractor={RetrieverExtractor(context)}) {
    private val serial=Mutex()
    private val generation=AtomicLong()
    private val mutable=MutableStateFlow<VideoFrameState>(VideoFrameState.Idle)
    val state=mutable.asStateFlow()
    private var extractor:VideoFrameExtractor?=null
    private var openUri:String?=null
    private var active:Job?=null
    private var releasing:Job?=null
    @Volatile private var closed=false

    fun request(source:Source,timeMs:Long,revision:Long) {
        if(closed) return
        val token=generation.incrementAndGet()
        active?.cancel()
        val requested=timeMs.coerceIn(0,source.durationMs.coerceAtLeast(0))
        mutable.value=VideoFrameState.Loading(source.uri,requested,revision)
        active=scope.launch {
            try {
                val bitmap=withContext(Dispatchers.IO) { serial.withLock {
                    currentCoroutineContext().ensureActive()
                    require(source.width>0 && source.height>0) { "Video dimensions are unavailable." }
                    val fullBytes=source.width.toLong()*source.height*4
                    if(sdkInt<27 && fullBytes>MAX_BITMAP_BYTES)
                        throw IOException("Quick frame needs too much memory on this Android version. Use Play preview.")
                    if(openUri!=source.uri) {
                        extractor?.close();extractor=null;openUri=null
                        extractor=extractorFactory().also { it.open(source.uri) }
                        openUri=source.uri
                    }
                    val (width,height)=targetSize(source.width,source.height)
                    val decoded=extractor!!.frame(requested*1000,width,height,sdkInt>=27)
                        ?: throw IOException("Android could not decode a quick frame at this time.")
                    currentCoroutineContext().ensureActive()
                    val limited=if(sdkInt<27 && (decoded.width>width || decoded.height>height))
                        Bitmap.createScaledBitmap(decoded,width,height,true).also { if(it!==decoded) decoded.recycle() }
                    else decoded
                    if(limited.allocationByteCount>MAX_BITMAP_BYTES) {
                        limited.recycle()
                        throw IOException("Quick frame exceeded the memory limit. Use Play preview.")
                    }
                    limited
                } }
                if(!closed && generation.get()==token)
                    mutable.value=VideoFrameState.Ready(bitmap,requested,source.uri,revision,
                        if(source.hdr) "Quick frame · HDR color is approximate" else "Quick frame · nearest decoded image")
                // Do not recycle a bitmap that Compose may still hold from an earlier state.
            } catch(cancel:CancellationException) { throw cancel }
            catch(error:Exception) {
                if(!closed && generation.get()==token)
                    mutable.value=VideoFrameState.Error(source.uri,requested,revision,error.message ?: "Quick frame is unavailable.")
            }
        }
    }

    fun close() {
        if(closed) return
        closed=true
        generation.incrementAndGet()
        active?.cancel()
        mutable.value=VideoFrameState.Idle
        releasing=scope.launch(Dispatchers.IO) { serial.withLock {
            extractor?.close();extractor=null;openUri=null
        } }
    }

    suspend fun closeAndJoin() {
        close()
        active?.cancelAndJoin()
        releasing?.join()
    }

    private fun targetSize(width:Int,height:Int):Pair<Int,Int> {
        val scale=min(1.0,min(960.0/width,540.0/height))
        return (width*scale).roundToInt().coerceAtLeast(1) to (height*scale).roundToInt().coerceAtLeast(1)
    }

    companion object { const val MAX_BITMAP_BYTES=12L*1024*1024 }
}
