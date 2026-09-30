package dev.forma.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.FFprobeSessionCompleteCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import dev.forma.app.data.FfmpegTranscoder
import dev.forma.app.data.MediaFiles
import dev.forma.app.work.RunCoordinator
import dev.forma.core.*
import dev.forma.ffmpeg.*
import dev.forma.ffmpeg.ManagedFfmpegBridge
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native cancellation proof; pass a long source via formaProbeInput. */
@RunWith(AndroidJUnit4::class)
class NativeProbeCancellationTest {
    @Test fun stoppedCappedWorkerAfterRejectedRouteCannotPublishOrBlockNextJob(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaProbeCancel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode.lab.codec")
        val fixture = File(requireNotNull(InstrumentationRegistry.getArguments().getString("formaProbeInput")))
        check(fixture.isFile)
        val original = File(context.filesDir, "outputs/cancel-original-${UUID.randomUUID()}.flac")
        check(original.parentFile!!.mkdirs() || original.parentFile!!.isDirectory)
        fixture.copyTo(original)
        val sourceHash = sha256(original)
        val files = MediaFiles(context)
        val native = ManagedFfmpegBridge(createFfmpegBridge())
        val source = native.probe(original.absolutePath).copy(uri = FileProvider.getUriForFile(
            context, "${context.packageName}.files", original).toString())
        val settings = Settings(container = Container.M4A, audio = AudioEncoder.AAC)
        val spec = JobSpec(UUID.randomUUID().toString(), source, Trim(), settings, 100_000_000)
        val candidate = File(files.workDir(spec), "encoded.m4a")
        val output = files.output(spec)
        val states = mutableListOf<JobState>()
        var routeCalls = 0
        val traced = object : FfmpegBridge by native {
            override suspend fun prepareAttempts(source: Source, trim: Trim, settings: Settings,
                input: String, output: String): List<PreparedAttempt> {
                val argv = listOf("-i", input, output)
                return listOf(
                    PreparedAttempt(argv, EncodeDecision(EncodeBackend.MEDIACODEC, "aac_mediacodec", reason = "synthetic rejected route")),
                    PreparedAttempt(argv, EncodeDecision(EncodeBackend.SOFTWARE, "aac", reason = "copy long fixture for real native verification")))
            }
            override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult {
                // Keep this test focused on cancellation inside the real frame-count scan.
                // The subsequent short export still uses native decode and encode.
                if (arguments.lastOrNull() == "-") return NativeResult(0, "synthetic pre-scan decode")
                routeCalls++
                if (routeCalls == 1) return NativeResult(1, "synthetic initialization failure", FailureKind.CODEC_INITIALIZATION)
                check(routeCalls == 2) { "Cancellation started another route." }
                val input = File(arguments[arguments.indexOf("-i") + 1])
                input.copyTo(File(arguments.last()))
                return NativeResult(0, "")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = RunCoordinator(scope)
        var nextOutput: File? = null
        var nextSpec: JobSpec? = null
        try {
            val worker = requireNotNull(coordinator.start {
                FfmpegTranscoder(files, traced).run(spec, { states += it }, {})
            })
            val nativeScan = withTimeout(30_000) {
                var running: FFprobeSession? = null
                while (running == null) {
                    check(!worker.job.isCompleted) { "Worker ended before frame-count verification: ${worker.failure}" }
                    running = FFmpegKitConfig.getFFprobeSessions().firstOrNull {
                        it.getArguments().contains(candidate.absolutePath) && it.getArguments().contains("-count_frames") &&
                            it.getState() == SessionState.RUNNING
                    }
                    if (running == null) delay(10)
                }
                running
            }
            delay(100)
            check(candidate.isFile && !worker.job.isCompleted && routeCalls == 2)
            coordinator.stop(worker.id)
            withTimeout(10_000) { worker.job.join() }
            check(ReturnCode.isCancel(nativeScan.getReturnCode()))
            check(output.exists().not() && candidate.exists().not() && JobState.COMPLETED !in states && routeCalls == 2)
            check(sha256(original) == sourceHash)
            withTimeout(10_000) { while (coordinator.state.value.mode != dev.forma.app.work.RunMode.IDLE) delay(10) }
            val next = spec.copy(id = UUID.randomUUID().toString(), trim = Trim(endMs = 1_000), targetBytes = null)
            nextSpec = next
            nextOutput = files.output(next)
            val nextStates = mutableListOf<JobState>()
            val nextWorker = requireNotNull(coordinator.start {
                FfmpegTranscoder(files, native).run(next, { nextStates += it }, {})
            })
            withTimeout(30_000) { nextWorker.job.join() }
            check(nextStates.lastOrNull() == JobState.COMPLETED && nextOutput.isFile)
        } finally {
            scope.cancel()
            original.delete()
            output.delete()
            File(output.parentFile, "${spec.id}.acceleration.json").delete()
            files.workDir(spec).deleteRecursively()
            nextOutput?.delete()
            nextSpec?.let { files.workDir(it).deleteRecursively() }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val length = input.read(buffer); if (length < 0) break; digest.update(buffer, 0, length) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Test fun cancelledBeforeNativeStartNeverScans(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaProbeCancel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode.lab.codec")
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val completed = CompletableDeferred<FFprobeSession>()
        try {
            executor.submit { release.await() }
            val session = FFprobeKit.executeWithArgumentsAsync(
                arrayOf("-v", "error", "-show_format", "/does-not-exist"),
                FFprobeSessionCompleteCallback { completed.complete(it) }, executor)
            FFmpegKit.cancel(session.getSessionId())
            release.countDown()
            val result = withTimeout(10_000) { completed.await() }
            assertTrue("Queued native probe ignored cancellation", ReturnCode.isCancel(result.getReturnCode()))
        } finally {
            release.countDown()
            executor.shutdown()
        }
    }

    @Test fun cancelledCountScanTerminatesBeforeNextProbe(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaProbeCancel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "dev.forma.transcode.lab.codec")
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("formaProbeInput"))
        val source = File(path)
        check(source.isFile && source.length() > 0) { "Push a long media fixture into the lab package first." }
        val bridge = ManagedFfmpegBridge(createFfmpegBridge())
        val scan = async { bridge.inspectStreams(path, countFrames = true) }
        val session = withTimeout(10_000) {
            var running: FFprobeSession? = null
            while (running == null) {
                running = FFmpegKitConfig.getFFprobeSessions().firstOrNull {
                    it.getArguments().contains(path) && it.getArguments().contains("-count_frames") &&
                        it.getState() == SessionState.RUNNING
                }
                if (running == null) delay(10)
            }
            running
        }
        delay(100)
        assertFalse("Fixture finished too quickly to exercise cancellation", scan.isCompleted)
        scan.cancel()
        withTimeout(10_000) { scan.join() }
        assertTrue(scan.isCancelled)
        assertTrue("FFprobe did not report a native cancellation", ReturnCode.isCancel(session.getReturnCode()))
        assertTrue("Source was released before native completion", source.isFile)
        withTimeout(10_000) { bridge.probe(path) }
    }
}
