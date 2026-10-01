package dev.forma.app.video

import android.content.Context
import android.util.AtomicFile
import dev.forma.app.data.JobCodec
import dev.forma.core.*
import dev.forma.core.image.QueueJobSpec
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class VideoDraft(val edit:SourceEdit,val tool:String,val revision:Long)
sealed interface VideoDraftLoad {
    data object Missing:VideoDraftLoad
    data class Valid(val draft:VideoDraft):VideoDraftLoad
    data class Corrupt(val path:String,val reason:String):VideoDraftLoad
}
data class VideoDraftReferences(val uris:Set<String>,val preserveImports:Boolean)

/** Only committed gestures reach this atomic, versioned store. */
class VideoDraftRepository(context:Context) {
    private val directory=File(context.filesDir,"video-drafts")
    private val mutex=Mutex()
    fun key(uri:String):String=MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    private fun file(uri:String)=File(directory,"${key(uri)}.json")

    suspend fun load(uri:String):VideoDraftLoad=withContext(Dispatchers.IO) {mutex.withLock {read(file(uri),uri)} }

    suspend fun save(draft:VideoDraft)=withContext(Dispatchers.IO) {mutex.withLock {
        require(draft.revision>=0 && draft.tool in setOf("Trim","Crop","Rotate","Adjust")) {"Invalid video draft tool or revision."}
        require(draft.edit.source.videoTracks>0 && draft.edit.source.imageInfo==null) {"Choose a video before saving its draft."}
        val target=file(draft.edit.source.uri)
        val existing=read(target,draft.edit.source.uri)
        check(existing !is VideoDraftLoad.Corrupt) {"The saved draft is damaged. Keep it for recovery or discard it explicitly."}
        val saved=(existing as? VideoDraftLoad.Valid)?.draft
        check(saved==null || draft.revision>=saved.revision) {"A newer video draft is already saved."}
        val job=JobSpec(UUID.nameUUIDFromBytes(draft.edit.source.uri.toByteArray(Charsets.UTF_8)).toString(),
            draft.edit.source,draft.edit.trim,Settings(effects=draft.edit.effects))
        val data=JSONObject().put("schema",1).put("tool",draft.tool).put("revision",draft.revision)
            .put("job",JobCodec.encode(listOf(QueueEntry(QueueJobSpec.Av(job)))))
            .toString().toByteArray(Charsets.UTF_8)
        require(data.size<=1_048_576) {"Video draft is too large."}
        directory.mkdirs()
        val atomic=AtomicFile(target)
        val output=atomic.startWrite()
        try {output.write(data);atomic.finishWrite(output)}
        catch(error:Exception) {atomic.failWrite(output);throw error}
    } }

    suspend fun latest():VideoDraftLoad=withContext(Dispatchers.IO) {mutex.withLock {
        val candidates=directory.listFiles().orEmpty().filter {it.extension=="json" || it.name.endsWith(".json.bak")}
            .map {if(it.name.endsWith(".bak"))File(it.path.removeSuffix(".bak")) else it}.distinct()
            .sortedByDescending {maxOf(it.lastModified(),File("${it.path}.bak").lastModified())}
        candidates.firstOrNull()?.let {read(it,null)} ?: VideoDraftLoad.Missing
    } }

    suspend fun references():VideoDraftReferences=withContext(Dispatchers.IO) {mutex.withLock {
        val uris=mutableSetOf<String>();var preserve=false
        directory.listFiles().orEmpty().forEach { f ->
            if(f.extension!="json" && !f.name.endsWith(".json.bak")) {preserve=true;return@forEach}
            val target=if(f.name.endsWith(".bak"))File(f.path.removeSuffix(".bak")) else f
            when(val saved=read(target,null)) {
                is VideoDraftLoad.Valid -> uris+=saved.draft.edit.source.uri
                else -> preserve=true
            }
        }
        VideoDraftReferences(uris,preserve)
    } }

    suspend fun discard(uri:String)=withContext(Dispatchers.IO) {mutex.withLock {
        AtomicFile(file(uri)).delete()
        check(read(file(uri),uri) is VideoDraftLoad.Missing) {"The video draft could not be discarded."}
    } }

    private fun read(target:File,expectedUri:String?):VideoDraftLoad {
        val backup=File("${target.path}.bak")
        if(!target.exists() && !backup.exists()) return VideoDraftLoad.Missing
        return try {
            require((if(backup.exists())backup else target).length()<=1_048_576) {"Video draft exceeds 1 MiB."}
            val record=JSONObject(AtomicFile(target).openRead().use {it.readBytes().toString(Charsets.UTF_8)})
            require(record.keys().asSequence().toSet()==setOf("schema","tool","revision","job") && record.getInt("schema")==1)
            val tool=record.getString("tool").also {require(it in setOf("Trim","Crop","Rotate","Adjust"))}
            val revision=record.getLong("revision").also {require(it>=0)}
            val entries=JobCodec.decode(record.getString("job"))
            require(entries.size==1 && entries.single().state==JobState.QUEUED)
            val job=(entries.single().spec as QueueJobSpec.Av).job
            require(job.sequence==null && job.source.videoTracks>0 && job.source.imageInfo==null)
            require(target.name=="${key(job.source.uri)}.json" && (expectedUri==null || job.source.uri==expectedUri))
            VideoDraftLoad.Valid(VideoDraft(SourceEdit(job.source,job.trim,job.settings.effects),tool,revision))
        } catch(error:Exception) {VideoDraftLoad.Corrupt(target.path,error.message ?: "Invalid video draft.")}
    }
}
