package dev.forma.app.settings

import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.core.settings.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real Android AtomicFile adapter; synthetic policy fixtures do not cover this boundary. */
class SettingsStorageDeviceTest {
    @Test fun durableRoundTripCorruptionAndBoundsPreserveStoredBytes() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.cacheDir,"settings-storage-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try {
            val file=File(directory,"preferences.txt")
            val storage=AtomicSettingsStorage(file)
            val store=SettingsStore(storage)
            val first=store.load()
            val saved=store.save(SettingsDraft(first).edit("ui.theme",SettingValue.Choice("dark")))
            assertEquals(saved,SettingsStore(AtomicSettingsStorage(file)).load())
            val intact=file.readBytes()
            assertTrue(runCatching { storage.write("x".repeat(SettingsCodec.MAX_BYTES+1)) }.isFailure)
            assertArrayEquals(intact,file.readBytes())
            val malformed=byteArrayOf(0xc3.toByte(),0x28)
            file.writeBytes(malformed)
            assertTrue(runCatching { AtomicSettingsStorage(file).read() }.isFailure)
            assertArrayEquals(malformed,file.readBytes())
            file.writeText("unsupported settings document")
            val corrupt=file.readBytes()
            val invalid=SettingsStore(AtomicSettingsStorage(file))
            assertTrue(runCatching { invalid.load() }.isFailure)
            assertTrue(runCatching { invalid.save(SettingsDraft(SettingsDocument())) }.isFailure)
            assertArrayEquals(corrupt,file.readBytes())
        } finally { directory.deleteRecursively() }
    }
}
