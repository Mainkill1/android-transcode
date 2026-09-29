package dev.forma.ffmpeg.audio

import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.ffmpeg.FfmpegBridge
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlin.math.*

data class WaveformSummary(val peaks:List<Float>,val peakDb:Double?,val rmsDb:Double?)
data class AudioPreviewResult(val identity:String,val original:File,val rendered:File,val durationMs:Long,
    val originalWaveform:WaveformSummary,val waveform:WaveformSummary,val renderMs:Long)
data class AudioPreviewRequest(val identity:String,val input:File,val source:Source,val trim:Trim,val settings:Settings,val directory:File)

/** Exact creative processing from the selection start; stored PCM is bounded to ten seconds. */
class AudioPreviewRenderer(private val bridge:FfmpegBridge) {
    suspend fun render(request:AudioPreviewRequest):AudioPreviewResult=withContext(Dispatchers.IO) {
        val r=request;val started=android.os.SystemClock.elapsedRealtime()
        check(r.directory.isDirectory || r.directory.mkdirs())
        val duration=minOf(10000000L,AudioGraphPlanner.sourceFacts(r.source,r.trim,r.settings).durationUs)
        require(duration>0)
        val settings=r.settings
        val original=File(r.directory,"original.wav");val rendered=File(r.directory,"rendered.wav")
        val originalSettings=settings.copy(audioEdit=AudioEdit(output=settings.audioEdit.output.copy(normalization=NormalizationPolicy(),maxBytes=null),rate=settings.audioEdit.rate))
        suspend fun produce(value:Settings,file:File,normalize:Boolean) {
            var args=bridge.prepareAudio(r.source,r.trim,value,r.input.path,file.path)
            if(normalize && value.audioEdit.output.normalization.mode!=NormalizationMode.OFF) {
                val analyzed=AudioAnalyzer(bridge).analyze(AudioAnalysisRequest(r.identity,r.input,r.source,r.trim,value))
                val measured=analyzed.measurement
                args=AudioAnalyzer.appendFilter(args,measured.normalizationFilter(value.audioEdit.output.normalization),AudioGraphPlanner.plan(r.source,r.trim,value).sampleRateHz)
            }
            args=AudioAnalyzer.appendFilter(args,"atrim=end=${AudioGraphPlanner.number(duration/1000000.0)}",null)
            val result=bridge.execute(args) {};check(result.exitCode==0) { "Preview failed. ${result.diagnostics}" }
            currentCoroutineContext().ensureActive()
            check(file.length() in 1..(32L*1024*1024)) { "Preview exceeded its storage bound." }
        }
        produce(originalSettings,original,false);produce(settings,rendered,true)
        val facts=bridge.probe(rendered.path)
        check(facts.durationMs<=10050 && facts.durationMs>0)
        AudioPreviewResult(r.identity,original,rendered,facts.durationMs,waveform(original),waveform(rendered),android.os.SystemClock.elapsedRealtime()-started)
    }
    /** Read the bounded preview incrementally; retain only 512 peak tiles and summary statistics. */
    private fun waveform(file:File):WaveformSummary {
        RandomAccessFile(file,"r").use { input ->
            check(file.length()<=32L*1024*1024);input.seek(12)
            var bytes=0L
            while(input.filePointer+8<=input.length()) {
                val id=ByteArray(4);input.readFully(id);val size=Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                check(size<=input.length()-input.filePointer)
                if(String(id)=="data") { bytes=size;break }
                input.seek(input.filePointer+size+(size%2))
            }
            check(bytes>0 && bytes%4==0L);val count=bytes/4
            val peaks=FloatArray(512);var total=0.0;var peak=0.0;var index=0L
            val buffer=ByteArray(16384)
            while(index<count) {
                val n=minOf(buffer.size.toLong(),(count-index)*4).toInt();input.readFully(buffer,0,n)
                val samples=ByteBuffer.wrap(buffer,0,n).order(ByteOrder.LITTLE_ENDIAN)
                repeat(n/4) {
                    val value=samples.float;check(value.isFinite());val absolute=abs(value.toDouble())
                    peak=max(peak,absolute);total+=value.toDouble()*value
                    val tile=(index*512/count).toInt().coerceAtMost(511);peaks[tile]=max(peaks[tile],absolute.toFloat());index++
                }
            }
            return WaveformSummary(peaks.toList(),peak.takeIf { it>0 }?.let { 20*log10(it) },
                (total/count).takeIf { it>0 }?.let { 10*log10(it) })
        }
    }
}
