package dev.forma.app.data

import dev.forma.core.*
import dev.forma.ffmpeg.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real desktop fixture exercises production acceptance, independently of the graph compiler. */
class MovieCompletenessTest {
    @Test fun rationalObservedClocksDoNotInventMissingStreamStarts() {
        val fields=mapOf("codec_type" to "audio", "start_time" to "N/A", "time_base" to "1/44100",
            "duration_ts" to "88200", "duration" to "999", "observed_start_pts" to "1024", "observed_start_time" to "0")
        val measured=OutputFactsReader.read(null,listOf(fields))
        assertEquals(23_220L,measured.originUs)
        assertEquals(2_000_000L,measured.streams.single().durationUs)
        val unknown=OutputFactsReader.read(null,listOf(fields - "observed_start_pts" - "observed_start_time"))
        assertNull(unknown.originUs);assertNull(unknown.streams.single().startUs)
        assertNull(OutputFactsReader.read(null,listOf(fields,fields - "observed_start_pts" - "observed_start_time")).originUs)
    }
    private fun command(vararg args: String): String {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { text }
        return text
    }
    private inner class IncompleteBridge(val defect: String = "video-tail") : FfmpegBridge {
        override suspend fun capabilities() = Capabilities(true, "fixture", setOf("libx264", "aac"), setOf("mp4", "ipod"), setOf("fps", "scale", "trim", "setpts", "atrim", "asetpts", "atempo", "concat", "aresample", "aformat", "apad", "anullsrc"))
        override suspend fun probe(localPath: String): Source {
            val root = JSONObject(command("ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", localPath))
            val streams = root.getJSONArray("streams")
            val entries=(0 until streams.length()).map { streams.getJSONObject(it) }
            val video=entries.firstOrNull { it.optString("codec_type")=="video" }
            return Source(localPath, File(localPath).name, (root.getJSONObject("format").getString("duration").toDouble() * 1000).toLong(),
                video?.optInt("width") ?: 0, video?.optInt("height") ?: 0, entries.count { it.optString("codec_type")=="video" },
                entries.count { it.optString("codec_type")=="audio" }, bytes = File(localPath).length())
        }
        override suspend fun inspectStreams(localPath: String, countFrames: Boolean): OutputFacts {
            val root=JSONObject(command(*(listOf("ffprobe","-v","error") + (if(countFrames) listOf("-count_frames") else emptyList()) + listOf("-show_streams","-show_format","-of","json",localPath)).toTypedArray()))
            val streams=root.getJSONArray("streams")
            val fields=(0 until streams.length()).map { i ->
                val stream=streams.getJSONObject(i)
                stream.keys().asSequence().associateWith { stream.optString(it) } + mapOf("tag:DURATION" to stream.optJSONObject("tags")?.optString("DURATION").orEmpty())
            }
            val origin=root.getJSONObject("format").optString("start_time")
            val first=OutputFactsReader.read(origin,fields)
            val measured=fields.mapIndexed { i,stream ->
                if(first.streams[i].kind!=StreamKind.OTHER && first.streams[i].startUs==null) {
                    val observation=JSONObject(command("ffprobe","-v","error","-select_streams",streams.getJSONObject(i).getInt("index").toString(),
                        "-read_intervals","%+#1","-show_packets","-show_entries","packet=pts,pts_time","-of","json",localPath)).getJSONArray("packets").getJSONObject(0)
                    stream + mapOf("observed_start_pts" to observation.optString("pts"),"observed_start_time" to observation.optString("pts_time"))
                } else stream
            }
            return OutputFactsReader.read(origin,measured)
        }
        override suspend fun execute(arguments: List<String>, onProgress: (Progress) -> Unit): NativeResult {
            if (arguments.last() == "-" || defect=="original") command("ffmpeg", *arguments.toTypedArray()) else
                command(*(listOf("ffmpeg", "-v", "error", "-nostdin", "-n") +
                    (if(defect=="video-start") listOf("-itsoffset", "0.1") else emptyList()) +
                    listOf("-f", "lavfi", "-i", "color=red:s=64x64:r=${if(defect=="frame-count") 1 else 30}:d=${if(defect=="video-tail") 1 else 5}",
                    "-f", "lavfi", "-i", "sine=sample_rate=48000:duration=${if(defect=="audio-tail") 1 else 5}",
                    "-c:v", "libx264", "-threads:v", "1", "-c:a", "aac", arguments.last())).toTypedArray())
            return NativeResult(0, "")
        }
    }
    @Test fun fullyDecodableTracksStillRequireCompleteClocksAndFrames(): Unit = runBlocking {
        val directory = File("build/movie-completeness/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val input = File(directory, "original.mp4")
        val output = File(directory, "published.mp4")
        try {
            command("ffmpeg", "-v", "error", "-nostdin", "-n", "-f", "lavfi", "-i", "color=blue:s=64x64:r=30:d=5",
                "-f", "lavfi", "-i", "sine=sample_rate=48000:duration=5", "-c:v", "libx264", "-threads:v", "1", "-c:a", "aac", input.path)
            for (defect in listOf("video-tail", "audio-tail", "frame-count", "video-start", "none")) {
                val bridge = IncompleteBridge(defect)
                val source = bridge.probe(input.path)
                val result = runCatching { FfmpegRenderSession(bridge).render(JobSpec("fixture", source, Trim(), Settings(fps=30)), listOf(input), output, {}) }
                assertEquals("Completeness for $defect: ${result.exceptionOrNull()}", defect != "none", result.isFailure)
                assertEquals(defect == "none", output.exists())
                output.delete()
                assertEquals(listOf("original.mp4"), directory.list()!!.toList())
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun intentionalAudioOffsetRemainsOnTheSharedTimeline(): Unit = runBlocking {
        val directory = File("build/movie-completeness/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val input = File(directory, "offset-original.mp4")
        try {
            command("ffmpeg", "-v", "error", "-nostdin", "-n", "-f", "lavfi", "-i", "color=blue:s=64x64:r=30:d=5",
                "-itsoffset", "0.3", "-f", "lavfi", "-i", "sine=sample_rate=48000:duration=5",
                "-c:v", "libx264", "-threads:v", "1", "-c:a", "aac", input.path)
            val bridge = IncompleteBridge("original")
            val source = bridge.probe(input.path)
            for(speed in listOf(100,200)) {
                val output = File(directory, "offset-$speed.mp4")
                val settings = Settings(fps=30, effects=ClipEffects(speedPercent=speed))
                FfmpegRenderSession(bridge).render(JobSpec("offset", source, Trim(), settings), listOf(input), output, {})
                val facts=bridge.inspectStreams(output.path, true)
                val video=facts.streams.single { it.kind==StreamKind.VIDEO }
                val audio=facts.streams.single { it.kind==StreamKind.AUDIO }
                assertTrue("Audio offset must remain positive at speed $speed", audio.startUs!!-video.startUs!! > 100_000)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun measuredWavClockAllowsTrimmedAudioExport(): Unit = runBlocking {
        val directory = File("build/movie-completeness/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val input = File(directory, "clockless.wav")
        val output = File(directory, "trimmed.m4a")
        try {
            command("ffmpeg", "-v", "error", "-nostdin", "-n", "-f", "lavfi", "-i", "sine=sample_rate=44100:duration=3", "-c:a", "pcm_s16le", input.path)
            val bridge=IncompleteBridge("original")
            FfmpegRenderSession(bridge).render(JobSpec("wav", bridge.probe(input.path), Trim(333,2333), Settings(container=Container.M4A)), listOf(input), output, {})
            assertTrue(output.isFile)
            assertEquals(0, bridge.probe(output.path).videoTracks)
        } finally { directory.deleteRecursively() }
    }

}
