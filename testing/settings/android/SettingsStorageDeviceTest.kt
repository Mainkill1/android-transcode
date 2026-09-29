package dev.forma.app.settings

import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.settings.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real Android AtomicFile adapter; synthetic policy fixtures do not cover this boundary. */
class SettingsStorageDeviceTest {
    @Test fun corruptDocumentCannotBeOverwrittenBySave() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"settings-storage-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            val file=File(directory,"preferences.txt")
            file.writeText("unsupported settings document")
            val corrupt=file.readBytes()
            val invalid=SettingsStore(AtomicSettingsStorage(file))
            assertTrue(runCatching { invalid.load() }.isFailure)
            assertTrue(runCatching { invalid.save(SettingsDraft(SettingsDocument())) }.isFailure)
            assertArrayEquals(corrupt,file.readBytes())
        } finally { directory.deleteRecursively() }
    }
}
