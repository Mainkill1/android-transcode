package dev.forma.app.settings

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.*
import dev.forma.core.*
import dev.forma.core.settings.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Disposable directories only: never reads or rewrites the user's queue/preferences. */
class SettingsQueueStorageTest {
    @Test fun provenanceRestartAndCompletedPruningPreserveActiveStorageAndRecovery()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"settings-queue-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir()=root }
        try {
            val preferences=MediaPreferences(SettingsDocument(11,PreferenceValues.EMPTY.with("video.quality",SettingValue.Integer(23))),
                overrides=PreferenceValues.EMPTY.with("video.quality",SettingValue.Integer(23)))
            fun spec()=JobSpec(UUID.randomUUID().toString(),Source("content://documents/clip","../clip.mp4",1000),Trim(),
                NativePreferences.apply(Settings(),preferences.resolve()),preferences)
            val done=spec();val waiting=spec();val active=spec()
            val queue=QueueRepository(context);queue.load();queue.add(listOf(done,waiting,active))
            queue.transition(done.id,JobState.PREPARING);queue.transition(done.id,JobState.RUNNING)
            queue.transition(done.id,JobState.VERIFYING);queue.transition(done.id,JobState.COMPLETED)
            queue.transition(active.id,JobState.PREPARING)
            val files=MediaFiles(context)
            files.output(done).writeText("verified fixture")
            val held=File(files.workDir(active),"held.media").apply { writeText("active reader fixture") }
            val reopened=QueueRepository(context);reopened.load()
            assertEquals(preferences,reopened.entries.value.first { it.spec.id==waiting.id }.spec.preferences)
            assertEquals(JobState.INTERRUPTED,reopened.entries.value.first { it.spec.id==active.id }.state)
            val expired=reopened.pruneCompleted(0,System.currentTimeMillis())
            assertEquals(setOf(done.id),expired)
            files.cleanupExpiredOutputs(expired)
            assertFalse(files.output(done).exists())
            assertTrue(held.isFile)
            assertEquals(setOf(waiting.id,active.id),reopened.entries.value.map { it.spec.id }.toSet())
            val restored=QueueRepository(context);restored.load()
            assertEquals(reopened.entries.value,restored.entries.value)
        } finally { root.deleteRecursively() }
    }
}
