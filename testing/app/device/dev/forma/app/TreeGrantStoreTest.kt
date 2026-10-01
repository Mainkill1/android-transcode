package dev.forma.app

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.settings.TreeGrantStore
import dev.forma.core.SaveDestination
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TreeGrantStoreTest {
    @Test fun chosenFolderPersistsButRevokedGrantBlocksNewQueueSnapshot() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"tree-store-${UUID.randomUUID()}").apply { mkdirs() }
        val context=object:ContextWrapper(app) { override fun getFilesDir():File=root }
        val uri="content://provider/tree/exports"
        var granted=setOf(uri)
        try {
            val store=TreeGrantStore(context) { granted }
            store.choose(uri,"My exports")
            assertEquals(SaveDestination.DocumentTree(uri,"My exports"),TreeGrantStore(context) { granted }.selected())
            granted=emptySet()
            assertFalse(TreeGrantStore(context) { granted }.validate(uri))
            assertEquals(SaveDestination.DocumentTree(uri,"My exports"),TreeGrantStore(context) { granted }.selected())
        } finally { root.deleteRecursively() }
    }
}
