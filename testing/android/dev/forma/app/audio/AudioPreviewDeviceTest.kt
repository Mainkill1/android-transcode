package dev.forma.app.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.ffmpeg.*
import dev.forma.ffmpeg.audio.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class AudioPreviewDeviceTest {
    @Test fun boundedPreviewUsesExportGraphAndPlaysFloatPcm():Unit=runBlocking(Dispatchers.IO) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val dir=File(context.cacheDir,"preview-test-${UUID.randomUUID()}").apply { check(mkdirs()) };val report=JSONObject()
        try {
            val bridge=ManagedFfmpegBridge(createFfmpegBridge())
            val input=File(dir,"long.wav");writeFloatWav(input,FloatArray(960000) { (.2*sin(2*PI*440*it/48000)).toFloat() },48000)
            val settings=Settings(container=Container.M4A,stereo=false,audioEdit=AudioEdit(nodes=listOf(AudioEffectNode("gain","gain",parameters=GainParameters(-6.0)))))
            val result=AudioPreviewRenderer(bridge).render(AudioPreviewRequest("test",input,bridge.probe(input.path),Trim(),settings,File(dir,"render")))
            check(result.identity=="test" && result.durationMs==10000L)
            check(result.waveform.peaks.size==512)
            val difference=result.waveform.rmsDb!!-result.originalWaveform.rmsDb!!
            check(abs(difference+6)<.1) { "Preview gain $difference" }
            val ready=CompletableDeferred<Unit>();var player:ExoPlayer?=null
            try {
                instrumentation.runOnMainSync {
                    player=ExoPlayer.Builder(context).build().apply {
                        addListener(object:Player.Listener {
                            override fun onPlaybackStateChanged(state:Int) { if(state==Player.STATE_READY)ready.complete(Unit) }
                            override fun onPlayerError(error:PlaybackException) { ready.completeExceptionally(error) }
                        });setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(result.rendered)));prepare()
                    }
                }
                withTimeout(10000) { ready.await() }
                instrumentation.runOnMainSync { check(player!!.duration==10000L);player!!.seekTo(5000);player!!.play() }
                delay(300)
                instrumentation.runOnMainSync { check(player!!.isPlaying && player!!.currentPosition>=5000);player!!.pause() }
            } finally { instrumentation.runOnMainSync { player?.release() } }
            report.put("passed",true).put("durationMs",result.durationMs).put("renderMs",result.renderMs).put("gainDb",difference)
        } catch(e:Throwable) { report.put("passed",false).put("error",e.toString());throw e }
        finally { File(context.filesDir,"native-readiness").apply { mkdirs() }.resolve("audio-preview.json").writeText(report.toString(2));dir.deleteRecursively() }
    }
}
