package dev.forma.app

import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.JobCodec
import dev.forma.app.service.TranscodeService
import dev.forma.app.work.RunMode
import dev.forma.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in native service qualification, restricted to an idle, empty isolated lab installation. */
@RunWith(AndroidJUnit4::class)
class MovieServiceTest {
    @Test fun nativeMovieServicePublishesDurableCompletedOutput(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaMovieService") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "dev.forma.transcode.lab")
        val graph = (context.applicationContext as FormaApplication).graph
        graph.initialize()
        check(graph.runs.state.value.mode == RunMode.IDLE && graph.queue.entries.value.isEmpty()) { "Use an idle empty lab queue." }
        check(graph.bridge.capabilities().available) { "Native build required." }
        val id = UUID.randomUUID().toString()
        val directory = File(context.filesDir, "forma-tests/service-$id").apply { check(mkdirs()) }
        val original = File(directory, "original.mp4")
        val queueFile = File(context.filesDir, "queue-v1.json")
        val savedQueue = queueFile.readBytes()
        var spec: JobSpec? = null
        var ownedRunId: Long? = null
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            val generated = graph.bridge.execute(listOf("-hide_banner", "-v", "error", "-nostdin", "-n",
                "-f", "lavfi", "-i", "color=blue:s=64x64:r=30:d=1", "-f", "lavfi", "-i", "sine=sample_rate=48000:duration=1",
                "-c:v", "libx264", "-c:a", "aac", original.absolutePath)) {}
            check(generated.exitCode == 0) { generated.diagnostics }
            val source = graph.bridge.probe(original.absolutePath).copy(uri=Uri.fromFile(original).toString())
            val job = MovieProject(sequence=SequenceSpec(EditTimeline(listOf(TimelineClip("fixture", source))), CanvasSpec(64,64)), targetBytes=null).toJob(id)
            spec = job
            graph.queue.add(listOf(job))
            instrumentation.runOnMainSync { ContextCompat.startForegroundService(context, Intent(context, TranscodeService::class.java).setAction(TranscodeService.START)) }
            withTimeout(60_000) {
                while (graph.queue.entries.value.single().state != JobState.COMPLETED) {
                    val entry = graph.queue.entries.value.single()
                    val running = graph.runs.state.value
                    if(entry.spec.id == id && entry.state in setOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING) && running.mode != RunMode.IDLE)
                        ownedRunId = running.id
                    check(entry.state !in setOf(JobState.FAILED, JobState.INTERRUPTED, JobState.CANCELLED)) { entry.message }
                    check(graph.queue.error.value == null) { graph.queue.error.value.orEmpty() }
                    delay(50)
                }
                while (graph.runs.state.value.mode != RunMode.IDLE) delay(50)
            }
            check(JobCodec.decode(queueFile.readText()).single().state == JobState.COMPLETED)
            check(graph.files.output(job).isFile)
        } finally {
            try { withContext(NonCancellable) {
                check(graph.queue.entries.value.all { it.spec.id == id }) { "Refusing to touch foreign jobs." }
                ownedRunId?.let { owner ->
                    graph.runs.stop(owner)
                    withTimeout(60_000) { while(graph.runs.state.value.id==owner && graph.runs.state.value.mode!=RunMode.IDLE) delay(50) }
                }
                check(graph.queue.entries.value.all { it.spec.id == id }) { "Refusing to restore over foreign jobs." }
                check(graph.runs.state.value.mode==RunMode.IDLE) { "Native ownership must end before restoring/deleting fixture files." }
                queueFile.writeBytes(savedQueue)
                graph.queue.load()
                spec?.let { graph.files.output(it).delete() }
                directory.deleteRecursively()
            } } finally { activity.close() }
        }
    }
}
