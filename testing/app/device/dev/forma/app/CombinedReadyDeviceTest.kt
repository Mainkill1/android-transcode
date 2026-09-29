package dev.forma.app

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.audio.readFloatWav
import dev.forma.app.audio.writeFloatWav
import dev.forma.app.data.*
import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.core.image.QueueJobSpec
import dev.forma.ffmpeg.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Cross-PR device regressions use the production exporter and private fixtures only. */
@RunWith(AndroidJUnit4::class)
class CombinedReadyDeviceTest {
    @Test fun clipAndAudioTimingDspSurviveCapsAndLosslessExports(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaNative") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode.lab")
        val bridge = ManagedFfmpegBridge(createFfmpegBridge())
        check(bridge.capabilities().available)
        val files = MediaFiles(context)
        val dir = File(context.filesDir, "imports/combined-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val input = File(dir, "source.wav")
        val outputs = mutableListOf<File>()
        try {
            writeFloatWav(input, FloatArray(192000) { (.1*sin(2*PI*440*it/48000)).toFloat() }, 48000)
            val digest = hash(input)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", input).toString()
            val source = bridge.probe(input.path).copy(uri = uri, name = input.name)
            val edit = AudioEdit(rate = AudioRate(2), nodes = listOf(AudioEffectNode("gain", "gain", parameters = GainParameters(-6.0))))
            val settings = Settings(container = Container.WAV, audio = AudioEncoder.PCM_F32LE, stereo = false,
                effects = ClipEffects(speedPercent = 200, volumePercent = 50), audioEdit = edit)
            val spec = JobSpec(UUID.randomUUID().toString(), source, Trim(1000,3000), settings, targetBytes = 128000)
            val restored = (JobCodec.decode(JobCodec.encode(listOf(QueueEntry(spec)))).single().spec as QueueJobSpec.Av).job
            check(restored == spec)
            val states = mutableListOf<JobState>()
            FfmpegTranscoder(files, bridge).run(restored, {states += it}, {})
            val wav = files.output(restored).also {outputs += it}
            check(states.last() == JobState.COMPLETED && wav.length() < 128000)
            val samples = readFloatWav(wav)
            check(abs(samples.size - 24000) <= 2) { "Combined timing produced ${samples.size} samples, expected 24000" }
            val center = samples.sliceArray(4000 until 20000)
            val peak = center.maxOf {abs(it.toDouble())}
            check(abs(20*log10(peak/.1) + 12.0206) < .25) { "Clip volume or audio gain was lost: $peak" }
            val attempts = mutableListOf<AttemptEvent>()
            val flac = spec.copy(id = UUID.randomUUID().toString(), settings = settings.copy(container=Container.FLAC,audio=AudioEncoder.FLAC))
            FfmpegTranscoder(files, bridge).run(flac, {}, {}, {attempts += it})
            val flacFile = files.output(flac).also {outputs += it}
            check(flacFile.isFile && flacFile.length() < 128000)
            check(abs(bridge.probe(flacFile.path).durationMs - 500) <= 2)
            val noise = File(dir,"noise.wav")
            val random = java.util.Random(42)
            writeFloatWav(noise,FloatArray(192000) {(random.nextDouble()*1.5-.75).toFloat()},48000)
            val noiseHash=hash(noise)
            val noiseSource=bridge.probe(noise.path).copy(uri=FileProvider.getUriForFile(context,"${context.packageName}.files",noise).toString())
            val oversizedFlac=flac.copy(source=noiseSource,trim=Trim(1000,3000),settings=flac.settings.copy(effects=ClipEffects(),audioEdit=AudioEdit()))
            for (valid in listOf(spec, oversizedFlac)) {
                val tiny = valid.copy(id=UUID.randomUUID().toString(), targetBytes=32000)
                val rejectedStates = mutableListOf<JobState>()
                var started=0
                val rendered=mutableListOf<RenderAttempt>()
                val error = runCatching { FfmpegTranscoder(files, bridge).run(tiny, {rejectedStates += it}, {},
                    {if(it.status==AttemptStatus.STARTED) started++}, {rendered += it}) }.exceptionOrNull()
                check(error != null && error !is CancellationException)
                check(started==1) {"Lossless oversize must encode exactly one real candidate: $started"}
                val verified=rendered.filter {it.verified}
                check(verified.size==1 && !verified.single().accepted && verified.single().bytes>=32000) {
                    "Expected one valid oversized lossless candidate: $rendered"
                }
                check(error!!.message.orEmpty().let { "bitrate" in it.lowercase() || "size" in it.lowercase() || "byte" in it.lowercase() })
                check(!files.output(tiny).exists() && JobState.COMPLETED !in rejectedStates)
            }
            check(hash(noise)==noiseHash)
            check(hash(input) == digest)
        } finally { outputs.forEach {it.delete()}; dir.deleteRecursively() }
    }

    @Test fun moviePreservesPerClipAudioEditorGainAndGlobalTiming(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaNative") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode.lab")
        val bridge = ManagedFfmpegBridge(createFfmpegBridge())
        check(bridge.capabilities().available)
        val files = MediaFiles(context)
        val dir = File(context.filesDir,"imports/combined-movie-${UUID.randomUUID()}").apply {check(mkdirs())}
        val input = File(dir,"source.wav")
        var output: File? = null
        try {
            writeFloatWav(input, FloatArray(96000) {(.1*sin(2*PI*440*it/48000)).toFloat()},48000)
            val digest=hash(input)
            val source=bridge.probe(input.path).copy(uri=FileProvider.getUriForFile(context,"${context.packageName}.files",input).toString())
            val gain=AudioEdit(nodes=listOf(AudioEffectNode("gain","gain",parameters=GainParameters(-12.0))))
            val clips=listOf(TimelineClip("quiet",source,settings=Settings(audioEdit=gain)), TimelineClip("normal",source))
            val project=MovieProject(sequence=SequenceSpec(EditTimeline(clips),CanvasSpec(64,64)),
                settings=Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,stereo=true,audioEdit=AudioEdit(rate=AudioRate(1,2))),targetBytes=null)
            val spec=project.toJob(UUID.randomUUID().toString())
            val saved=(JobCodec.decode(JobCodec.encode(listOf(QueueEntry(spec)))).single().spec as QueueJobSpec.Av).job
            check(saved.sequence == spec.sequence)
            FfmpegTranscoder(files,bridge).run(saved,{},{})
            output=files.output(saved)
            val samples=readFloatWav(output!!)
            check(abs(samples.size-768000)<=4) {"Movie sample count ${samples.size}"}
            fun rms(a:Int,b:Int)=sqrt(samples.sliceArray(a until b).sumOf {it.toDouble()*it}/(b-a))
            val db=20*log10(rms(96000,288000)/rms(480000,672000))
            check(abs(db+12)<.15) {"Per-clip audio gain was lost: $db dB"}
            check(hash(input)==digest)
        } finally {output?.delete();dir.deleteRecursively()}
    }
    @Test fun delayedAudioMovieRetainsItsAudibleTail(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaNative") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="dev.forma.transcode.lab")
        val bridge=ManagedFfmpegBridge(createFfmpegBridge());check(bridge.capabilities().available)
        val files=MediaFiles(context)
        val dir=File(context.filesDir,"imports/combined-delay-${UUID.randomUUID()}").apply {check(mkdirs())}
        val input=File(dir,"delayed.mkv");var output:File?=null
        try {
            val generated=bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-n",
                "-f","lavfi","-i","color=red:s=64x64:r=30:d=6","-itsoffset","2",
                "-f","lavfi","-i","sine=sample_rate=48000:duration=4",
                "-c:v","libx264","-c:a","pcm_f32le",input.path)) {}
            check(generated.exitCode==0) {generated.diagnostics}
            val digest=hash(input)
            val source=bridge.probe(input.path).copy(uri=FileProvider.getUriForFile(context,"${context.packageName}.files",input).toString())
            val gain=AudioEdit(nodes=listOf(AudioEffectNode("gain","gain",parameters=GainParameters(-6.0))))
            val project=MovieProject(sequence=SequenceSpec(EditTimeline(listOf(TimelineClip("delayed",source,settings=Settings(audioEdit=gain)))),CanvasSpec(64,64)),
                settings=Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE,stereo=true),targetBytes=null)
            val spec=project.toJob(UUID.randomUUID().toString())
            FfmpegTranscoder(files,bridge).run(spec,{},{})
            output=files.output(spec)
            val samples=readFloatWav(output!!)
            check(abs(samples.size-576000)<=4) {"Delayed movie sample count ${samples.size}"}
            fun rms(a:Int,b:Int)=sqrt(samples.sliceArray(a until b).sumOf {it.toDouble()*it}/(b-a))
            check(rms(48000,144000)<.00001) {"Intentional audio-leading gap was lost"}
            check(rms(528000,552000)>.02) {"Movie padded silence over the retained audio tail"}
            check(hash(input)==digest)
        } finally {output?.delete();dir.deleteRecursively()}
    }
    private fun hash(file:File)=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {"%02x".format(it)}
}
