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
            setOf("scale", "trim", "setpts", "concat", "atrim", "asetpts", "aresample", "aformat", "apad", "setsar", "fps", "tpad", "format", "settb", "pad", "anullsrc"))
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
}
