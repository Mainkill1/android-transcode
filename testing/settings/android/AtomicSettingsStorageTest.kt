package dev.forma.app.settings

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.settings.*
import java.io.File
import java.util.UUID
import org.junit.Test

/** Runs the production Android storage adapter inside a disposable test directory. */
internal object AndroidSettingsStorageChecks {
    private fun isolated(assertion: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "settings-storage-tests/${UUID.randomUUID()}")
        check(root.mkdirs()) { "Could not create isolated settings storage" }
        try { assertion(File(root, "preferences.txt")) }
        finally { check(root.deleteRecursively()) { "Could not remove isolated settings storage" } }
    }
    private fun rejects(assertion: () -> Unit) {
        val error = runCatching(assertion).exceptionOrNull()
        check(error is Exception) { "Expected storage to reject the invalid document" }
    }
    val cases: List<Pair<String, () -> Unit>> = listOf(
        "Android storage persists settings across fresh store instances" to { isolated { file ->
            val store = SettingsStore(AtomicSettingsStorage(file))
            val initial = store.load()
            check(initial == SettingsDocument())
            val saved = store.save(SettingsDraft(initial).edit("ui.theme", SettingValue.Choice("dark")))
            check(saved.revision == 1L)
            check(SettingsStore(AtomicSettingsStorage(file)).load() == saved)
        } },
        "Android storage refuses malformed UTF-8 without replacing it" to { isolated { file ->
            val corrupt = byteArrayOf(0xc3.toByte(), 0x28)
            file.writeBytes(corrupt)
            rejects { SettingsStore(AtomicSettingsStorage(file)).load() }
            check(file.readBytes().contentEquals(corrupt))
        } },
        "Android storage rejects oversized reads and preserves prior data on oversized writes" to { isolated { file ->
            val storage = AtomicSettingsStorage(file)
            val good = SettingsCodec.encode(SettingsDocument())
            storage.write(good)
            rejects { storage.write("x".repeat(SettingsCodec.MAX_BYTES + 1)) }
            check(storage.read() == good)
            file.writeText("x".repeat(SettingsCodec.MAX_BYTES + 1))
            rejects { SettingsStore(AtomicSettingsStorage(file)).load() }
            check(file.length() == SettingsCodec.MAX_BYTES + 1L)
        } },
        "Android storage recovers the prior document after an aborted atomic write" to { isolated { file ->
            val storage = AtomicSettingsStorage(file)
            val good = SettingsCodec.encode(SettingsDocument(7))
            storage.write(good)
            val atomic = AtomicFile(file)
            val interrupted = atomic.startWrite()
            try { interrupted.write("partial".toByteArray(Charsets.UTF_8)) }
            finally { atomic.failWrite(interrupted) }
            check(AtomicSettingsStorage(file).read() == good)
        } }
    )
}

class AtomicSettingsStorageTest {
    @Test fun persistedValuesSurviveReopen() = AndroidSettingsStorageChecks.cases[0].second()
    @Test fun malformedUtf8IsNotResetToDefaults() = AndroidSettingsStorageChecks.cases[1].second()
    @Test fun oversizedDocumentsAreRejected() = AndroidSettingsStorageChecks.cases[2].second()
    @Test fun abortedReplacementPreservesThePreviousDocument() = AndroidSettingsStorageChecks.cases[3].second()
}
