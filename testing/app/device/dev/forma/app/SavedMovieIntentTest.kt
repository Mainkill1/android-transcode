package dev.forma.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.*
import dev.forma.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Uses an isolated cache directory, never the application's real queue or native worker. */
class SavedMovieIntentTest {
    @Test fun incompleteSavedEditsPreserveOriginalQueueBytes() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"saved-movie-intent-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val isolated=object:ContextWrapper(context) { override fun getFilesDir():File=directory }
        val entry=QueueEntry(JobSpec(UUID.randomUUID().toString(),Source("content://original","original",3000,320,240,1,1),
            Trim(500,2500),Settings(effects=ClipEffects(crop=CropRect(0,0,160,120),speedPercent=125,fadeOutMs=100))))
        try {
            for(field in listOf("endMs","crop","speedPercent","fadeOutMs")) {
                val root=JSONObject(JobCodec.encode(listOf(entry)))
                val job=root.getJSONArray("jobs").getJSONObject(0)
                if(field=="endMs") job.getJSONObject("trim").remove(field)
                else job.getJSONObject("settings").getJSONObject("effects").remove(field)
                val bytes=root.toString().toByteArray()
                val file=File(directory,"queue-v1.json").apply { writeBytes(bytes) }
                assertTrue("Missing $field must fail load",runCatching { QueueRepository(isolated).load() }.isFailure)
                assertArrayEquals(bytes,file.readBytes())
            }
        } finally { directory.deleteRecursively() }
    }
}
