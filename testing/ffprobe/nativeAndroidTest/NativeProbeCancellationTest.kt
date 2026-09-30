package dev.forma.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.FFprobeSession
import com.arthenica.ffmpegkit.FFprobeSessionCompleteCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.SessionState
import dev.forma.ffmpeg.ManagedFfmpegBridge
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native cancellation proof; pass a long source via formaProbeInput. */
@RunWith(AndroidJUnit4::class)
class NativeProbeCancellationTest {
    @Test fun cancelledBeforeNativeStartNeverScans(): Unit = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaProbeCancel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.startsWith("dev.forma.transcode.lab"))
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
        check(context.packageName.startsWith("dev.forma.transcode.lab"))
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
