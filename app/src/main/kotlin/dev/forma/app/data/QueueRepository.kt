package dev.forma.app.data

import android.content.Context
import android.util.AtomicFile
import dev.forma.core.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LiveProgress(val id: String, val progress: Progress)

class QueueRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "queue-v1.json"))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<List<QueueEntry>>(emptyList())
    val entries = mutable.asStateFlow()
    val progress = MutableStateFlow<LiveProgress?>(null)
    val error = MutableStateFlow<String?>(null)

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val exists = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()
            val saved = if (exists) file.openRead().use { JobCodec.decode(it.readBytes().toString(Charsets.UTF_8)) } else emptyList()
            persist(saved.map(QueueRules::recover))
        }
    }
    suspend fun add(specs: List<JobSpec>) = change {
        require(it.size + specs.size <= 200) { "The queue is limited to 200 entries in this foundation." }
        require((it.map { j -> j.spec.id } + specs.map { j -> j.id }).distinct().size == it.size + specs.size)
        it + specs.map(::QueueEntry)
    }
    suspend fun removeQueued(id: String) = change { entries ->
        require(entries.first { it.spec.id == id }.state == JobState.QUEUED) { "Only a waiting job can be removed." }
        entries.filterNot { it.spec.id == id }
    }
    suspend fun claimNext(): JobSpec? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = mutable.value.firstOrNull { it.state == JobState.QUEUED } ?: return@withLock null
            persist(mutable.value.map { if (it == next) QueueRules.transition(it, JobState.PREPARING) else it })
            next.spec
        }
    }
    suspend fun transition(id: String, state: JobState, message: String = "") = change { entries ->
        require(entries.any { it.spec.id == id }) { "The job no longer exists." }
        entries.map { if (it.spec.id == id) QueueRules.transition(it, state, message) else it }
    }
    private suspend fun change(update: (List<QueueEntry>) -> List<QueueEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock { persist(update(mutable.value)) }
    }
    private fun persist(entries: List<QueueEntry>) {
        val output = file.startWrite()
        try {
            output.write(JobCodec.encode(entries).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            mutable.value = entries.toList()
        } catch (e: Exception) { file.failWrite(output); throw e }
    }
}
