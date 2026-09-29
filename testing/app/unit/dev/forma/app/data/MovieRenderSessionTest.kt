package dev.forma.app.data

import dev.forma.core.*
import dev.forma.ffmpeg.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Host lifecycle tests of the production renderer; this recorder does not claim native qualification. */
class MovieRenderSessionTest {
    private val source = Source("content://a", "a.mp4", 1000, 64, 64, 1, 1)
    private fun directory() = File("build/movie-runtime/${UUID.randomUUID()}").apply { check(mkdirs()) }
    private class Recorder(val source: Source, val sizes: List<Int>, val decodeFails: Boolean = false) : FfmpegBridge {
        val prepared = mutableListOf<List<String>>()
        var renders = 0
        override suspend fun capabilities() = Capabilities(true, "", setOf("libx264", "aac"), setOf("mp4"),
            setOf("scale", "trim", "setpts", "concat", "atrim", "asetpts", "aresample", "aformat", "apad", "setsar", "fps", "tpad", "format", "settb", "pad", "anullsrc", "color", "overlay"))
        override suspend fun probe(localPath: String) = source.copy(uri = localPath)
        override suspend fun prepare(source: Source, trim: Trim, settings: Settings, input: String, output: String): List<String> {
            return Planner.arguments(source, trim, settings, input, output).also { prepared += it }
        }
        override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult {
            if (arguments.last() == "-") return NativeResult(if (decodeFails) 1 else 0, "decode result")
            File(arguments.last()).writeBytes(ByteArray(sizes[minOf(renders++, sizes.lastIndex)]) { 7 })
            return NativeResult(0, "")
        }
    }
    @Test fun cancellationFromAcceptedAttemptLeavesNoFinalizedOutput(): Unit = runBlocking {
        val dir = directory(); val input = File(dir,"source").apply { writeText("original") }; val output = File(dir,"output.mp4")
        try {
            val renderer = FfmpegRenderSession(Recorder(source, listOf(100)))
            val work = launch {
                renderer.render(JobSpec("id",source,Trim(),Settings()), listOf(input), output, {},
                    { if (it.accepted) cancel("Stop requested at accepted attempt") })
            }
            work.join()
            assertTrue(work.isCancelled); assertFalse(output.exists())
            assertEquals(listOf("source"), dir.list()!!.toList())
        } finally { dir.deleteRecursively() }
    }
    @Test fun strictEqualityRetriesOnlyOriginalSourcesAndCleansAttempts(): Unit = runBlocking {
        val dir=directory(); val input=File(dir,"source").apply { writeText("original") }; val output=File(dir,"output.mp4")
        try {
            val bridge=Recorder(source,listOf(100000,99999)); val evidence=mutableListOf<RenderAttempt>()
            FfmpegRenderSession(bridge).render(JobSpec("id",source,Trim(),Settings(),targetBytes=100000),listOf(input),output,{},evidence::add)
            assertEquals(2,bridge.renders); assertEquals(99999L,output.length())
            assertFalse(evidence.first().accepted); assertTrue(evidence.last().accepted)
            assertTrue(bridge.prepared.all { it[it.indexOf("-i")+1] == input.canonicalPath })
            assertEquals("original",input.readText()); assertEquals(2,dir.list()!!.size)
        } finally { dir.deleteRecursively() }
    }
    @Test fun decoderFailureAndExhaustedCapCannotFinalize(): Unit = runBlocking {
        for (decodeFails in listOf(true,false)) {
            val dir=directory(); val input=File(dir,"source").apply { writeText("original") }; val output=File(dir,"output.mp4")
            try {
                val result=runCatching { FfmpegRenderSession(Recorder(source,listOf(200000),decodeFails)).render(
                    JobSpec("id",source,Trim(),Settings(),targetBytes=100000),listOf(input),output,{}) }
                assertTrue(result.isFailure); assertFalse(output.exists()); assertEquals(listOf("source"),dir.list()!!.toList())
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun sourceProtectionIncludesEveryMovieInput() {
        val b=source.copy(uri="content://b")
        val job=MovieProject(sequence=SequenceSpec(EditTimeline(listOf(TimelineClip("a",source),TimelineClip("b",b))))).toJob("id")
        assertTrue("The second original must be protected", b.uri in JobPlans.sourceUris(job))
    }
    @Test fun orderedMovieUsesOnePreparedGraphForEveryOriginal(): Unit = runBlocking {
        val dir=directory(); val a=File(dir,"a").apply { writeText("original A") }; val b=File(dir,"b").apply { writeText("original B") }; val out=File(dir,"out.mp4")
        try {
            val timeline=EditTimeline(listOf(TimelineClip("a",source,Trim(0,500)),TimelineClip("b",source.copy(uri="content://b"),Trim(0,500))))
            val job=MovieProject(sequence=SequenceSpec(timeline,CanvasSpec(64,64)),targetBytes=null).toJob("id")
            val attempts=mutableListOf<RenderAttempt>(); val bridge=Recorder(source,listOf(100))
            FfmpegRenderSession(bridge).render(job,listOf(a,b),out,{},attempts::add)
            val argv=attempts.single().arguments
            assertEquals(listOf(a.canonicalPath,b.canonicalPath),argv.indices.filter { argv[it]=="-i" }.map { argv[it+1] })
            assertTrue(argv.contains("-filter_complex")); assertEquals(1,bridge.renders)
            assertEquals("original A",a.readText()); assertEquals("original B",b.readText())
        } finally { dir.deleteRecursively() }
    }
    @Test fun acceptedCallbackFailureAndRenameFailureCleanOnlyOwnedCandidates(): Unit = runBlocking {
        for (blockRename in listOf(false,true)) {
            val dir=directory(); val input=File(dir,"source").apply { writeText("original") }; val output=File(dir,"out.mp4")
            try {
                val failure=runCatching { FfmpegRenderSession(Recorder(source,listOf(100))).render(
                    JobSpec("id",source,Trim(),Settings()),listOf(input),output,{}, {
                        if(blockRename) { check(output.mkdir());File(output,"foreign").writeText("retained") }
                        else error("Observer failed at the accepted attempt")
                    }) }
                assertTrue(failure.isFailure);assertEquals("original",input.readText())
                assertTrue(dir.listFiles()!!.none { it.name.startsWith("attempt-") })
                if(blockRename) assertEquals("retained",File(output,"foreign").readText()) else assertFalse(output.exists())
            } finally { dir.deleteRecursively() }
        }
    }
    @Test fun publicationFailureCannotLeaveAShareableOrOverwriteExistingOutput(): Unit = runBlocking {
        val dir=directory(); val encoded=File(dir,"encoded").apply { writeText("verified") }; val output=File(dir,"published")
        try {
            val failed=runCatching { FfmpegPublication.publish(encoded,output) { error("Queue commit failed") } }
            assertTrue(failed.isFailure);assertFalse(output.exists())
            encoded.writeText("verified retry");output.writeText("foreign existing output")
            val conflict=runCatching { FfmpegPublication.publish(encoded,output) {} }
            assertTrue(conflict.isFailure);assertEquals("foreign existing output",output.readText());assertTrue(encoded.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun stopDuringDurablePublicationRetainsCompletedOutput(): Unit = runBlocking {
        val dir=directory(); val encoded=File(dir,"encoded").apply { writeText("verified") }; val output=File(dir,"published")
        var committed=false
        try {
            val worker=launch(start=CoroutineStart.LAZY) {
                FfmpegPublication.publish(encoded,output) { this@launch.cancel("Stop during publication");committed=true }
            }
            worker.start();worker.join()
            assertTrue(worker.isCancelled);assertTrue(committed);assertEquals("verified",output.readText())
        } finally { dir.deleteRecursively() }
    }
}
