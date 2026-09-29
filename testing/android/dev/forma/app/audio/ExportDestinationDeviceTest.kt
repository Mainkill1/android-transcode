package dev.forma.app.audio

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.data.MediaFiles
import dev.forma.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real Android provider and private import/save boundaries, independent of native codec qualification. */
class ExportDestinationDeviceTest {
    @Test fun importedOriginalAndAliasCannotBeTruncatedAndOnlyNewEmptyDocumentCanBeSaved()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val authority="${instrumentation.context.packageName}.writable-export"
        fun uri(name:String)=Uri.parse("content://$authority/$name")
        val original=uri("original.wav");val alias=uri("alias.wav")
        context.contentResolver.call(original,"reset",null,null)
        val originalBytes=context.contentResolver.openInputStream(original)!!.use { it.readBytes() }
        val files=MediaFiles(context)
        val copied=files.importShared(original)
        assertNotEquals(original.toString(),copied.uri)
        val spec=JobSpec(UUID.randomUUID().toString(),copied,Trim(),Settings(container=Container.WAV,audio=AudioEncoder.PCM_S16LE))
        // This fixture isolates saving; authoritative encoding is exercised by the native audio suite.
        val replacement="different disposable completed-output bytes".toByteArray()
        val output=files.output(spec).apply { writeBytes(replacement) }
        try {
            for(destination in listOf(original,alias,uri("unreadable.wav"))) {
                assertTrue("Destination must be rejected before opening for write: $destination",runCatching { files.export(spec,destination) }.isFailure)
                assertArrayEquals(originalBytes,context.contentResolver.openInputStream(original)!!.use { it.readBytes() })
            }
            assertEquals(0,context.contentResolver.call(original,"state",null,null)!!.getInt("writes"))
            files.export(spec,uri("empty.wav"))
            assertArrayEquals(replacement,context.contentResolver.openInputStream(uri("empty.wav"))!!.use { it.readBytes() })
            assertEquals(1,context.contentResolver.call(original,"state",null,null)!!.getInt("writes"))
            assertArrayEquals(originalBytes,context.contentResolver.openInputStream(original)!!.use { it.readBytes() })
        } finally {
            output.delete()
            val importId=Uri.parse(copied.uri).pathSegments.getOrNull(1)
            if(importId!=null && runCatching { UUID.fromString(importId).toString()==importId }.getOrDefault(false))
                File(context.filesDir,"imports/$importId").deleteRecursively()
            context.contentResolver.call(original,"reset",null,null)
        }
    }
}
