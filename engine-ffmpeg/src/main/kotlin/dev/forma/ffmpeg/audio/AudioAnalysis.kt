package dev.forma.ffmpeg.audio

import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.ffmpeg.FfmpegBridge
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

object AudioAnalysisIdentity {
    fun create(fingerprint:String,build:String,source:Source,trim:Trim,settings:Settings):String {
        val graph=AudioGraphPlanner.plan(source,trim,settings,forceProcessed=true)
        return digest(listOf(fingerprint,build,settings.audioTrack,source.audioStreams,trim,graph.identity).joinToString("|"))
    }
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun fingerprint(file:File):String = withContext(Dispatchers.IO) {
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
        file.inputStream().use { input -> while(true) { ensureActive();val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n) } }
        hash.digest().joinToString("") { "%02x".format(it) }
    }
}
data class AudioMeasurements(val integratedLufs:Double,val truePeakDb:Double,val rangeLu:Double,val thresholdLufs:Double,val offset:Double,val samplePeakDb:Double?=null) {
    fun normalizationFilter(policy:NormalizationPolicy):String {
        fun n(value:Double)=AudioGraphPlanner.number(value)
        return when(policy.mode) {
            NormalizationMode.OFF -> ""
            NormalizationMode.PEAK -> {
                val peak=samplePeakDb ?: error("Sample peak could not be measured.")
                require(peak.isFinite());"volume=${n(policy.peakDb-peak)}dB:precision=double"
            }
            NormalizationMode.LOUDNESS -> {
                require(!policy.preserveDynamics || truePeakDb + policy.integratedLufs-integratedLufs <= policy.truePeakDb) {
                    "That loudness would exceed the peak ceiling. Lower the target or allow dynamic normalization."
                }
                // FFmpeg treats measured_LRA=0 as unset and falls back to dynamic mode.
                if(policy.preserveDynamics && rangeLu==0.0) "volume=${n(policy.integratedLufs-integratedLufs)}dB:precision=double"
                else "loudnorm=I=${n(policy.integratedLufs)}:TP=${n(policy.truePeakDb)}:LRA=50:measured_I=${n(integratedLufs)}:measured_TP=${n(truePeakDb)}:measured_LRA=${n(rangeLu)}:measured_thresh=${n(thresholdLufs)}:offset=${n(offset)}:linear=true:print_format=json"
            }
        }
    }
    companion object {
        fun parseLoudness(log:String,mode:NormalizationMode=NormalizationMode.LOUDNESS):AudioMeasurementResult = try {
            val start=log.lastIndexOf('{');val end=log.indexOf('}',start)
            require(start>=0 && end>start)
            val json=JSONObject(log.substring(start,end+1))
            fun value(key:String)=json.getString(key).toDouble().also { require(it.isFinite()) }
            val peak=Regex("Peak level dB: ([^\\s]+)").findAll(log).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() }
            if(mode==NormalizationMode.PEAK) {
                require(peak!=null)
                AudioMeasurementResult.Peak(peak)
            } else AudioMeasurementResult.Measured(AudioMeasurements(value("input_i"),value("input_tp"),value("input_lra"),value("input_thresh"),value("target_offset"),peak))
        } catch (_: Exception) { AudioMeasurementResult.NotMeasurable("Silent, too short, or not measurable.") }
    }
}
sealed interface AudioMeasurementResult {
    data class Peak(val samplePeakDb:Double):AudioMeasurementResult
    data class Measured(val values:AudioMeasurements):AudioMeasurementResult
    data class NotMeasurable(val reason:String):AudioMeasurementResult
}
data class AudioAnalysisRequest(val identity:String,val input:File,val source:Source,val trim:Trim,val settings:Settings)
data class AudioAnalysisResult(val identity:String,val measurement:AudioMeasurementResult)

/** No lease acquisition here: the caller owns one run across every phase. */
class AudioAnalyzer(private val bridge:FfmpegBridge) {
    suspend fun analyze(request:AudioAnalysisRequest):AudioAnalysisResult {
        val r=request
        val policy=r.settings.audioEdit.output.normalization
        val args=bridge.prepareAudio(r.source,r.trim,r.settings,r.input.path,"-").toMutableList()
        val normalization="loudnorm=I=${AudioGraphPlanner.number(policy.integratedLufs)}:TP=${AudioGraphPlanner.number(policy.truePeakDb)}:LRA=50:print_format=json"
        val af=args.indexOf("-af");check(af>=0)
        args[af+1]+=",astats=reset=0,$normalization"
        args[args.indexOf("-loglevel")+1]="info"
        args[args.lastIndexOf("-f")+1]="null"
        val result=bridge.execute(args) {}
        check(result.exitCode==0) { "Audio analysis failed. ${result.diagnostics}" }
        currentCoroutineContext().ensureActive()
        return AudioAnalysisResult(r.identity,AudioMeasurements.parseLoudness(result.diagnostics,policy.mode))
    }
    companion object {
        fun appendFilter(arguments:List<String>,filter:String,rate:Int?):List<String> {
            if(filter.isEmpty())return arguments
            val args=arguments.toMutableList();val index=args.indexOf("-af")
            val value=filter + if(rate!=null) ",aresample=$rate" else ""
            if(index>=0)args[index+1]+=",$value" else args.addAll(args.size-1,listOf("-af",value))
            return args
        }
    }
}

fun AudioMeasurementResult.normalizationFilter(policy:NormalizationPolicy):String = when(this) {
    is AudioMeasurementResult.Measured -> values.normalizationFilter(policy)
    is AudioMeasurementResult.Peak -> {
        require(policy.mode==NormalizationMode.PEAK)
        "volume=${AudioGraphPlanner.number(policy.peakDb-samplePeakDb)}dB:precision=double"
    }
    is AudioMeasurementResult.NotMeasurable -> error(reason)
}
