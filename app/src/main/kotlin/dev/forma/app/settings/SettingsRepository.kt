package dev.forma.app.settings

import android.content.Context
import android.util.AtomicFile
import dev.forma.core.settings.*
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SettingsLoadState(val document: SettingsDocument? = null, val error: String? = null)

/** Construct once in AppGraph. AtomicFile does not provide locking; SettingsStore serializes access. */
class SettingsRepository(context: Context, scope: CoroutineScope) {
    private val store = SettingsStore(AtomicSettingsStorage(File(context.filesDir, "settings-v1.txt")))
    private val lock = Mutex()
    private val mutable = MutableStateFlow(SettingsLoadState())
    val state = mutable.asStateFlow()
    init { scope.launch { load() } }

    suspend fun load(): SettingsDocument? = withContext(Dispatchers.IO) { lock.withLock {
        try { store.load().also { mutable.value = SettingsLoadState(it) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { mutable.value = SettingsLoadState(error = "Preferences could not be read. Existing data was not replaced: ${error.message}"); null }
    } }
    suspend fun save(draft: SettingsDraft): Result<SettingsDocument> = withContext(NonCancellable + Dispatchers.IO) { lock.withLock {
        // Rotation/cancellation cannot interrupt the write between durable replacement and publication.
        runCatching { store.save(draft) }.onSuccess { mutable.value = SettingsLoadState(it) }
    } }
}

internal class AtomicSettingsStorage(file: File) : SettingsStorage {
    private val atomic = AtomicFile(file)
    override fun read(): String? {
        val input = try { atomic.openRead() } catch (missing: FileNotFoundException) {
            // Only a genuinely absent file means first run. Permissions/recovery failures remain errors.
            if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) throw missing
            return null
        }
        return input.use {
            val bytes = ByteArray(SettingsCodec.MAX_BYTES + 1)
            var count = 0
            while (count < bytes.size) {
                val got = it.read(bytes, count, bytes.size - count)
                if (got < 0) break
                if (got == 0) continue
                count += got
            }
            require(count <= SettingsCodec.MAX_BYTES) { "Preferences exceed the size limit" }
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, 0, count)).toString()
        }
    }
    override fun write(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= SettingsCodec.MAX_BYTES)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
}
