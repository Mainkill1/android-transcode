package dev.forma.app

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.net.Uri
import dev.forma.app.data.FfmpegTranscoder
import dev.forma.app.data.JobCodec
import dev.forma.app.data.MediaFiles
import dev.forma.core.*
import dev.forma.ffmpeg.ManagedFfmpegBridge
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The input is the first 32 MiB of the user's Movies HEVC Main 10/E-AC-3 file. */
class Sdr10MovieDeviceTest {
    @Test fun completeMoviesDocumentImportsWithoutCopyingGigabytes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaFullMovie") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode")
        val queueFile = File(context.filesDir, "queue-v1.json")
        val before = sha256(queueFile)
        val selected = JobCodec.decode(queueFile.readText()).single {
            it.state == JobState.FAILED && it.spec.source.name.startsWith("Oppenheimer.2023.")
        }
        val bridge = ManagedFfmpegBridge(createFfmpegBridge())
        val inspected = MediaFiles(context, bridge).inspect(Uri.parse(selected.spec.source.uri), persistPermission = false)
        check(inspected.bytes > 3_000_000_000L && inspected.durationMs in 10_820_000L..10_825_000L)
        check(inspected.videoTracks == 1 && inspected.audioTracks == 1 && !inspected.hdr)
        check(inspected.audioStreams.single().sampleRateHz == 48000 && inspected.audioStreams.single().channels == 6)
        check(sha256(queueFile) == before) { "Inspecting the original changed the saved queue." }
    }

    @Test fun completeMoviesDocumentConvertsFiveSecondExcerpt() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaFullMovieConversion") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode")
        val queueFile = File(context.filesDir, "queue-v1.json")
        val before = sha256(queueFile)
        val selected = JobCodec.decode(queueFile.readText()).single {
            it.state == JobState.FAILED && it.spec.source.name.startsWith("Oppenheimer.2023.")
        }
        val bridge = ManagedFfmpegBridge(createFfmpegBridge())
        val files = MediaFiles(context, bridge)
        // The saved failed record predates native inspection and says audioTracks=0.
        // Rendering must re-probe the staged original before mapping audio.
        check(selected.spec.source.audioTracks == 0)
        val saved = selected.spec as dev.forma.core.image.QueueJobSpec.Av
        val job = saved.job.copy(id = UUID.randomUUID().toString(), trim = Trim(0, 5000), targetBytes = 1_500_000)
        try {
            FfmpegTranscoder(files, bridge).run(job, {}, {})
            val output = files.output(job)
            val verified = bridge.probe(output.path)
            check(output.isFile && output.length() in 1L until 1_500_000L)
            check(verified.videoTracks == 1 && verified.audioTracks == 1 && !verified.hdr)
            check(verified.width == 1280 && verified.height == 720 && verified.durationMs in 4900..5100)
            check(sha256(queueFile) == before) { "The real-source excerpt changed the saved queue." }
        } finally {
            files.output(job).delete()
            File(context.filesDir, "outputs/${job.id}.acceleration.json").delete()
            files.workDir(job).deleteRecursively()
        }
    }

    @Test fun actualMovieImportsBothTracksAndConvertsAnSdrTenBitExcerpt() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaNative") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode.lab")
        val input = File(context.filesDir, "imports/sdr10-device/source.mkv")
        check(input.isFile && input.length() == 32L * 1024 * 1024)
        val bridge = ManagedFfmpegBridge(createFfmpegBridge())
        val files = MediaFiles(context, bridge)
        val originalHash = sha256(input)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", input)
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { descriptor ->
            val byDescriptor = bridge.probe("/proc/self/fd/${descriptor.fd}")
            check(byDescriptor.videoTracks == 1 && byDescriptor.audioTracks == 1) {
                "Native FFprobe could not inspect the document descriptor."
            }
        }
        val source = files.inspect(uri, persistPermission = false)
        check(source.videoTracks == 1 && source.audioTracks == 1) {
            "Import lost the movie's E-AC-3 audio track: video=${source.videoTracks}, audio=${source.audioTracks}"
        }
        check(source.audioStreams.single().sampleRateHz == 48000 && source.audioStreams.single().channels == 6)
        check(!source.hdr) { "SDR Main 10 was mistaken for HDR." }
        val settings = Settings(video = VideoEncoder.X264, rateControl = RateControl.BITRATE,
            videoKbps = 1000, maxHeight = 720, fps = 24, audioKbps = 128)
        val job = JobSpec(UUID.randomUUID().toString(), source, Trim(0, 5000), settings, targetBytes = 1_500_000)
        try {
            FfmpegTranscoder(files, bridge).run(job, {}, {})
            val result = files.output(job)
            val inspected = bridge.probe(result.path)
            check(result.isFile && result.length() in 1L until 1_500_000L)
            check(inspected.videoTracks == 1 && inspected.audioTracks == 1 && !inspected.hdr)
            check(inspected.width == 1280 && inspected.height == 720 && inspected.durationMs in 4900..5100)
            check(sha256(input) == originalHash) { "The original was changed." }
        } finally {
            files.output(job).delete()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
