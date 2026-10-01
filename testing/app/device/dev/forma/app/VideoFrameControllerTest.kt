package dev.forma.app

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.video.*
import dev.forma.core.Source
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class VideoFrameControllerTest {
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val source=Source("content://test/a","a.mp4",2000,640,360,1)

    @Test fun rapidSourceSwitchCannotPublishOldFrame() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val frames=mutableListOf<String>()
        val controller=VideoFrameController(app,scope,34,extractorFactory={object:VideoFrameExtractor {
            override fun open(uri:String) {frames+=uri}
            override suspend fun frame(timeUs:Long,width:Int,height:Int,scaled:Boolean):Bitmap? {
                if(frames.last()==source.uri) {entered.complete(Unit);release.await()}
                return Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
            }
            override fun close() {}
        }})
        try {
            controller.request(source,200,1)
            entered.await()
            controller.request(source.copy(uri="content://test/b",name="b.mp4"),400,2)
            release.complete(Unit)
            val ready=withTimeout(5_000) {controller.state.first {it is VideoFrameState.Ready && it.revision==2L}} as VideoFrameState.Ready
            assertEquals("content://test/b",ready.sourceKey)
            assertEquals(400L,ready.requestedMs)
        } finally {controller.closeAndJoin();scope.cancel()}
    }

    @Test fun api26ChecksBudgetBeforeFullFrameDecode() = runBlocking {
        var decoded=false
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val controller=VideoFrameController(app,scope,26,extractorFactory={object:VideoFrameExtractor {
            override fun open(uri:String) {}
            override suspend fun frame(timeUs:Long,width:Int,height:Int,scaled:Boolean):Bitmap? {
                decoded=true;return Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
            }
            override fun close() {}
        }})
        try {
            controller.request(source.copy(width=4000,height=3000),100,1)
            val error=withTimeout(5_000) {controller.state.first {it is VideoFrameState.Error}} as VideoFrameState.Error
            assertTrue(error.message.contains("memory"))
            assertFalse(decoded)
        } finally {controller.closeAndJoin();scope.cancel()}
    }

    @Test fun api27UsesScaledNearestFrameAndReportsRequestedTime() = runBlocking {
        var wasScaled=false
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val controller=VideoFrameController(app,scope,27,extractorFactory={object:VideoFrameExtractor {
            override fun open(uri:String) {}
            override suspend fun frame(timeUs:Long,width:Int,height:Int,scaled:Boolean):Bitmap? {
                wasScaled=scaled
                assertTrue(width<=960 && height<=540)
                return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            }
            override fun close() {}
        }})
        try {
            controller.request(source,350,7)
            val ready=withTimeout(5_000) {controller.state.first {it is VideoFrameState.Ready}} as VideoFrameState.Ready
            assertTrue(wasScaled)
            assertEquals(350L,ready.requestedMs)
            assertTrue(ready.status.contains("Quick frame"))
        } finally {controller.closeAndJoin();scope.cancel()}
    }

    @Test fun retrieverClosesAfterViewModelScopeIsCancelled() = runBlocking {
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        var closed=false
        val controller=VideoFrameController(app,owner,34,extractorFactory={object:VideoFrameExtractor {
            override fun open(uri:String) {}
            override suspend fun frame(timeUs:Long,width:Int,height:Int,scaled:Boolean)=
                Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
            override fun close() {closed=true}
        }})
        controller.request(source,0,1)
        withTimeout(5_000) {controller.state.first {it is VideoFrameState.Ready}}
        owner.cancel()
        withTimeout(5_000) {controller.closeAndJoin()}
        assertTrue("Native frame extractor leaked after owner scope cancellation",closed)
    }
}
