package dev.forma.app.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.*
import dev.forma.core.audio.*
import dev.forma.ffmpeg.createFfmpegBridge
import dev.forma.ffmpeg.FfmpegBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import kotlin.math.*

/** Test-only independent PCM oracle; production prepare/export and packaged native engine. */
@RunWith(AndroidJUnit4::class)
class AudioDspDeviceTest {
    @Test fun packagedSignalContracts(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue("Run explicitly with -e formaNative true on a native Android build.",
            InstrumentationRegistry.getArguments().getString("formaNative") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.cacheDir, "audio-dsp-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val report = JSONObject().put("schema", 1).put("device", android.os.Build.MODEL)
        val measurements = JSONArray()
        try {
            val bridge = createFfmpegBridge()
            val caps = bridge.capabilities()
            check(caps.available) { caps.reason }
            report.put("nativeBuild", caps.build)
            suspend fun render(input: File, edit: AudioEdit, name: String, trim: Trim = Trim()): FloatArray {
                val source = bridge.probe(input.path)
                val settings = Settings(container = Container.WAV, audio = AudioEncoder.PCM_F32LE, stereo = false, audioEdit = edit)
                check(Planner.validate(source, trim, settings, caps).isEmpty())
                val output = File(dir, "$name.wav")
                val args = bridge.prepare(source, trim, settings, input.path, output.path)
                val result = bridge.execute(args) {}
                check(result.exitCode == 0) { "$name: ${result.diagnostics}" }
                val decoded = bridge.execute(listOf("-v", "error", "-xerror", "-i", output.path, "-f", "null", "-")) {}
                check(decoded.exitCode == 0) { decoded.diagnostics }
                return readFloatWav(output)
            }
            for (rate in listOf(44100, 48000)) {
                val data = FloatArray(rate * 2 + 1) { (0.2 * sin(2 * PI * 1000 * it / rate)).toFloat() }
                val input = File(dir, "音源 $rate.wav"); writeFloatWav(input, data, rate)
                val facts = bridge.probe(input.path).audioStreams.single()
                check(facts.sampleRateHz == rate && facts.totalSamples == data.size.toLong()) { "Probe lost sample clock: $facts" }
                val neutral = render(input, AudioEdit(), "neutral$rate")
                check(neutral.size == data.size) { "Neutral length ${neutral.size} != ${data.size}" }
                val nullPeak = neutral.indices.maxOf { abs(neutral[it] - data[it]).toDouble() }
                check(nullPeak <= 1e-7) { "Neutral null error $nullPeak" }
                val gain = render(input, AudioEdit(nodes = listOf(AudioEffectNode("gain", "gain", parameters = GainParameters(-6.0)))), "gain$rate")
                val gainDb = 20 * log10(rms(gain, rate / 4) / rms(data, rate / 4))
                check(abs(gainDb + 6) <= .1) { "Gain $gainDb dB" }
                val eq = render(input, AudioEdit(nodes = listOf(AudioEffectNode("eq", "eq", parameters = EqParameters(listOf(EqBand("bell", gainDb = 6.0)))))), "eq$rate")
                val eqDb = 20 * log10(rms(eq, rate / 4) / rms(data, rate / 4))
                check(abs(eqDb - 6) <= .5) { "EQ $eqDb dB" }
                val fade = render(input, AudioEdit(nodes = listOf(AudioEffectNode("fade", "fades", parameters = FadeParameters(500000, 500000)))), "fade$rate")
                check(abs(fade[0]) < 1e-7 && abs(fade.last()) < 1e-4)
                val compressed = render(input, AudioEdit(nodes = listOf(AudioEffectNode("compress", "compressor", parameters = CompressorParameters(thresholdDb = -24.0, ratio = 4.0)))), "compress$rate")
                check(rms(compressed, rate) < rms(data, rate) * .7) { "Compressor did not reduce sustained signal" }
                val impulse = FloatArray(rate / 10).apply { this[0] = .3f; this[lastIndex] = .4f }
                val impulseInput = File(dir, "short$rate.wav");writeFloatWav(impulseInput, impulse, rate)
                val limited = render(impulseInput, AudioEdit(nodes = listOf(AudioEffectNode("limit", "limiter", parameters = LimiterParameters()))), "limit$rate")
                check(limited.size == impulse.size && abs(limited.last() - .4f) < 1e-5) { "Limiter lost final impulse ${limited.last()}" }
                measurements.put(JSONObject().put("rate", rate).put("frames", neutral.size).put("nullPeak", nullPeak).put("gainDb", gainDb).put("eqDb", eqDb))
            }
            val stereo = FloatArray(96000) { if (it % 2 == 0) .1f else -.3f }
            val stereoInput = File(dir,"stereo.wav"); writeFloatWav(stereoInput,stereo,48000,2)
            val mono = render(stereoInput, AudioEdit(output = AudioOutputPolicy(channels = ChannelMode.MONO)), "mono")
            check(mono.size == 48000 && abs(mono[200] + .1f) < 1e-6)
            val swapped = render(stereoInput, AudioEdit(output = AudioOutputPolicy(channels = ChannelMode.SWAP)), "swap")
            check(swapped.size == 96000 && abs(swapped[200] + .3f) < 1e-6 && abs(swapped[201] - .1f) < 1e-6)
            report.put("passed", true).put("measurements",measurements)
        } catch (e: Throwable) { report.put("passed",false).put("error", e.toString()); throw e }
        finally { File(context.filesDir,"native-readiness").apply { mkdirs() }.resolve("audio-dsp.json").writeText(report.toString(2));dir.deleteRecursively() }
    }
}

internal fun rms(samples: FloatArray, skip: Int = 0) = sqrt(samples.drop(skip).sumOf { it.toDouble() * it } / (samples.size - skip))
internal fun writeFloatWav(file: File, samples: FloatArray, rate: Int, channels: Int = 1) {
    val b = ByteBuffer.allocate(44 + samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    b.put("RIFF".toByteArray()).putInt(36+samples.size*4).put("WAVEfmt ".toByteArray()).putInt(16)
    b.putShort(3).putShort(channels.toShort()).putInt(rate).putInt(rate*channels*4).putShort((channels*4).toShort()).putShort(32)
    b.put("data".toByteArray()).putInt(samples.size*4);samples.forEach { b.putFloat(it) };file.writeBytes(b.array())
}
internal fun readFloatWav(file: File): FloatArray {
    val bytes=file.readBytes();val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);var offset=12
    while(offset+8<=bytes.size) {
        val name=String(bytes,offset,4);val size=b.getInt(offset+4);check(size>=0 && offset+8L+size<=bytes.size)
        if(name=="data") return FloatArray(size/4) { b.getFloat(offset+8+it*4) }
        offset+=8+size+(size%2)
    };error("WAV has no PCM data")
}
