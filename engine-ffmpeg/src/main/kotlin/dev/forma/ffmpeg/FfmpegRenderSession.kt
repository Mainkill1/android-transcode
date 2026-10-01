package dev.forma.ffmpeg

import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.ffmpeg.audio.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** Render evidence complements route events; neither constitutes device qualification. */
data class RenderAttempt(val index:Int,val settings:Settings,val arguments:List<String>,val bytes:Long,
    val elapsedMs:Long,val verified:Boolean,val accepted:Boolean,val error:String?=null)

/** Every application and qualification render uses the same staged-original attempt pipeline. */
class FfmpegRenderSession(private val bridge:FfmpegBridge) {
    suspend fun render(requested:JobSpec,inputs:List<File>,output:File,onProgress:(Progress)->Unit,
        onAttempt:(RenderAttempt)->Unit={},onVerifying:suspend()->Unit={},onRoute:(AttemptEvent)->Unit={}):Source {
        val finalized=AtomicBoolean(false)
        try { return withContext(Dispatchers.IO) {
            val paths=inputs.map {it.canonicalFile};val destination=output.canonicalFile
            require(paths.isNotEmpty() && paths.all {it.isFile && it!=destination}) {"Readable, separate staged sources are required."}
            require(!destination.exists() && destination.parentFile?.isDirectory==true) {"Use a new output in a private working directory."}
            val caps=bridge.capabilities();check(caps.available){caps.reason}
            val clips=requested.sequence?.timeline?.clips
            require(paths.size==(clips?.size ?: 1)){"The staged sources do not match the clips."}
            val probes=mutableMapOf<String,Source>()
            suspend fun inspect(source:Source,index:Int):Source {
                val found=probes[paths[index].path] ?: bridge.probe(paths[index].path).also {probes[paths[index].path]=it}
                return found.copy(uri=source.uri,name=source.name)
            }
            val actual=if(clips==null)requested.copy(source=inspect(requested.source,0))else
                requested.copy(sequence=requested.sequence!!.copy(timeline=EditTimeline(clips.mapIndexed {i,c->c.copy(source=inspect(c.source,i))})))
            val problems=JobPlans.validate(actual,caps);require(problems.isEmpty()){problems.joinToString("\n")}
            val sequence=actual.sequence
            val base=if(sequence==null)actual.settings else actual.settings.copy(maxHeight=sequence.canvas.height,fps=sequence.canvas.fps)
            val duration=JobPlans.duration(actual)
            val candidate=File(destination.parentFile,"attempt-${UUID.randomUUID()}.${base.container.extension}")
            val analyzer=AudioAnalyzer(bridge)
            suspend fun normalization(source:Source,trim:Trim,settings:Settings,index:Int):String {
                val policy=settings.audioEdit.output.normalization
                if(policy.mode==NormalizationMode.OFF)return ""
                val identity=AudioAnalysisIdentity.create(AudioAnalysisIdentity.fingerprint(paths[index]),caps.build,source,trim,settings)
                val measured=analyzer.analyze(AudioAnalysisRequest(identity,paths[index],source,trim,settings))
                check(measured.identity==identity){"Audio analysis is stale. Try again."}
                return measured.measurement.normalizationFilter(policy)
            }
            val includedAudio=JobPlans.hasAudio(actual)
            val clipFilters=if(!includedAudio || sequence==null)emptyMap()else sequence.timeline.clips.mapIndexedNotNull {i,c ->
                if(c.source.audioTracks==0 || c.settings.audio==AudioEncoder.NONE)null else {
                    val s=SequencePlanner.clipSettings(c,base,sequence.canvas)
                    val filter=normalization(c.source,c.trim,s,i)
                    if(filter.isEmpty())null else i to filter
                }
            }.toMap()
            fun applyClipFilters(arguments:List<String>):List<String> = clipFilters.entries.fold(arguments) {args,(i,filter)->AudioAnalyzer.appendClipFilter(args,i,filter,48000)}
            val policy=base.audioEdit.output.normalization
            val globalFilter=if(!includedAudio)"" else if(sequence==null)normalization(actual.source,actual.trim,base,0)else if(policy.mode==NormalizationMode.OFF)"" else {
                val fingerprints=paths.map {AudioAnalysisIdentity.fingerprint(it)}
                val identity=listOf(fingerprints,caps.build,sequence,base.audioEdit,clipFilters).joinToString("|")
                val args=applyClipFilters(bridge.prepareSequenceAudio(sequence,base,paths.map {it.path},"-"))
                val measured=analyzer.analyzeArguments(identity,args,policy)
                check(measured.identity==identity){"Movie audio analysis is stale. Try again."}
                measured.measurement.normalizationFilter(policy)
            }
            suspend fun verifyNormalization(file:File,source:Source,trim:Trim,settings:Settings,policy:NormalizationPolicy) {
                val finalSettings=settings.copy(audioTrack=0,effects=ClipEffects(),audioEdit=AudioEdit(output=AudioOutputPolicy(channels=ChannelMode.SOURCE,normalization=policy)))
                val measured=analyzer.analyze(AudioAnalysisRequest("final",file,source,trim,finalSettings)).measurement
                if(policy.mode==NormalizationMode.LOUDNESS) {
                    val values=(measured as? AudioMeasurementResult.Measured)?.values ?: error("The encoded output could not be measured.")
                    check(kotlin.math.abs(values.integratedLufs-policy.integratedLufs)<=0.5){"The encoded output missed its loudness target."}
                    check(values.truePeakDb<=policy.truePeakDb+0.1){"The encoded output exceeded its true-peak ceiling."}
                }else check((measured as? AudioMeasurementResult.Peak)?.samplePeakDb?.let {kotlin.math.abs(it-policy.peakDb)<=0.1}==true){"The encoded output missed its sample-peak target."}
            }
            val normalizationPolicies=listOf(globalFilter to policy)+clipFilters.map {(index,filter)->filter to sequence!!.timeline.clips[index].settings.audioEdit.output.normalization}
            val adapter=object:FfmpegBridge by bridge {
                override suspend fun prepareAttempts(source:Source,trim:Trim,settings:Settings,input:String,output:String):List<PreparedAttempt> =
                    if(sequence==null)bridge.prepareAttempts(source,trim,settings,input,output)
                    else bridge.prepareSequenceAttempts(sequence,settings,paths.map {it.path},output)
            }
            var budget=base;var started=System.nanoTime();var facts:Source?=null
            val originals=if(sequence==null)listOf(bridge.inspectStreams(paths.single().path))else emptyList()
            try {
                ByteCapExport.run(adapter,actual.source.copy(audioTracks=if(JobPlans.hasAudio(actual))maxOf(1,actual.source.audioTracks)else 0),actual.trim,base,paths.first(),candidate,actual.targetBytes,
                    durationMs=duration,onProgress=onProgress,
                    transformAttempt={settings,attempt ->
                        budget=settings
                        var args=if(sequence==null)attempt.arguments else applyClipFilters(attempt.arguments)
                        val rate=if(!includedAudio)null else if(sequence==null)AudioGraphPlanner.plan(actual.source,actual.trim,settings).sampleRateHz else settings.audioEdit.output.sampleRateHz ?: 48000
                        args=AudioAnalyzer.appendFilter(args,globalFilter,rate)
                        args=AudioNormalizationGuard.withDiagnostics(args,normalizationPolicies)
                        attempt.copy(arguments=args)
                    },
                    executeAttempt={_,attempt,progress ->
                        val result=bridge.execute(attempt.arguments,progress)
                        if(result.exitCode==0)AudioNormalizationGuard.requireDynamics(result.diagnostics,normalizationPolicies)
                        result
                    },
                    verifyAttempt={settings,attempt ->
                        if(sequence==null)verifyEncodedOutput(bridge,actual.source,actual.trim,settings,candidate,attempt)
                        else {
                            val decoded=bridge.execute(listOf("-hide_banner","-nostdin","-v","error","-xerror","-i",candidate.path,"-map","0:v:0?","-map","0:a:0?","-f","null","-")){}
                            check(decoded.exitCode==0){"Movie output failed full decode verification. ${decoded.diagnostics}"}
                        }
                        val inspected=bridge.probe(candidate.path)
                        verifyMovieStreams(actual,settings,originals,bridge.inspectStreams(candidate.path,countFrames=true))
                        if(sequence!=null && !settings.container.audioOnly)check(inspected.width==sequence.canvas.width && inspected.height==sequence.canvas.height){"Output does not match the movie canvas."}
                        val checkSource=if(sequence==null)actual.source else SequencePlanner.mixedSource(sequence)
                        val checkTrim=if(sequence==null)actual.trim else Trim()
                        if(checkSource.audioStreams.isNotEmpty() || settings.audioEdit!=AudioEdit() || settings.container in setOf(Container.WAV,Container.FLAC)) {
                            val issues=AudioArtifactVerification.problems(checkSource,checkTrim,settings,inspected,candidate.length())
                            check(issues.isEmpty()){issues.joinToString("\n")}
                        }
                        if(includedAudio && policy.mode!=NormalizationMode.OFF)verifyNormalization(candidate,inspected,Trim(),settings,policy)
                        if(sequence!=null && clipFilters.isNotEmpty()) {
                            var offset=0L
                            val counts=SequencePlanner.frames(sequence)
                            sequence.timeline.clips.forEachIndexed {index,clip ->
                                val from=offset*1000/sequence.canvas.fps
                                offset+=counts[index]
                                if(index in clipFilters)verifyNormalization(candidate,inspected,Trim(from,offset*1000/sequence.canvas.fps),settings,clip.settings.audioEdit.output.normalization)
                            }
                        }
                        facts=inspected
                    },
                    onAttempt={event ->
                        if(event.status==AttemptStatus.STARTED)started=System.nanoTime()
                        onRoute(event)
                        if(event.status==AttemptStatus.VERIFIED || event.status==AttemptStatus.FAILED || event.status==AttemptStatus.REJECTED) {
                            val verified=event.status==AttemptStatus.VERIFIED
                            val accepted=verified && (actual.targetBytes==null || UploadFit.fits(candidate.length(),actual.targetBytes!!))
                            onAttempt(RenderAttempt(event.number,budget,event.attempt.arguments,candidate.length(),(System.nanoTime()-started)/1000000,verified,accepted,event.reason.takeIf {it.isNotBlank()}))
                        }
                    })
                onVerifying();currentCoroutineContext().ensureActive()
                check(candidate.renameTo(destination)){"The verified render could not be finalized."}
                finalized.set(true)
                requireNotNull(facts).copy(uri=destination.path,name=destination.name,bytes=destination.length())
            }finally {candidate.delete()}
        }}catch(error:Throwable){if(finalized.get())output.delete();throw error}
    }
}
