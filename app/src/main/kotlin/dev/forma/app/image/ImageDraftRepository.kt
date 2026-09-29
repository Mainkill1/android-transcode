package dev.forma.app.image
import android.content.Context
import dev.forma.core.image.*
import dev.forma.app.data.ImageDocumentCodec
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
sealed interface ImageDraftLoadResult {
    data object Missing:ImageDraftLoadResult
    data class Valid(val document:ImageEditDocument):ImageDraftLoadResult
    data class Corrupt(val path:String,val reason:String):ImageDraftLoadResult
    data class Unsupported(val path:String):ImageDraftLoadResult
}
sealed interface ImageDraftSaveResult {
    data class Saved(val revision:Long):ImageDraftSaveResult
    data object Conflict:ImageDraftSaveResult
    data object Preserved:ImageDraftSaveResult
}
data class ImageDraftReferences(val uris:Set<String>,val preserveImports:Boolean)
class ImageDraftRepository(private val directory:File) {
    constructor(context:Context):this(File(context.filesDir,"image-drafts"))
    private val mutex=Mutex()
    private fun file(hash:String):File {require(hash.matches(Regex("[a-f0-9]{64}")));return File(directory,"$hash.json")}
    private fun read(hash:String):ImageDraftLoadResult {
        val f=file(hash);if(!f.exists())return ImageDraftLoadResult.Missing
        return try {
            require(f.length()<=ImageValidation.MAX_DOCUMENT_BYTES){"Image draft exceeds 1 MiB."}
            val j=JSONObject(f.readText());if(j.opt("schema")!=1)return ImageDraftLoadResult.Unsupported(f.path)
            val d=ImageDocumentCodec.decode(j);require(d.source.hash==hash){"Draft source identity differs."};ImageDraftLoadResult.Valid(d)
        }catch(e:Exception){ImageDraftLoadResult.Corrupt(f.path,e.message?:"Invalid image draft.")}
    }
    suspend fun load(sourceId:String):ImageDraftLoadResult=withContext(Dispatchers.IO){mutex.withLock{read(sourceId)}}
    suspend fun save(document:ImageEditDocument,expectedRevision:Long):ImageDraftSaveResult=withContext(Dispatchers.IO){mutex.withLock{
        ImageValidation.requireValid(document)
        if(document.revision!=expectedRevision)return@withLock ImageDraftSaveResult.Conflict
        when(val old=read(document.source.hash)){
            is ImageDraftLoadResult.Corrupt,is ImageDraftLoadResult.Unsupported->return@withLock ImageDraftSaveResult.Preserved
            is ImageDraftLoadResult.Valid->if(old.document.revision>expectedRevision)return@withLock ImageDraftSaveResult.Conflict
            ImageDraftLoadResult.Missing->Unit
        }
        directory.mkdirs();val target=file(document.source.hash);val tmp=File(directory,"${document.source.hash}.tmp")
        val bytes=ImageDocumentCodec.encode(document).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=ImageValidation.MAX_DOCUMENT_BYTES)
        try{
            tmp.outputStream().use {it.write(bytes);it.fd.sync()};currentCoroutineContext().ensureActive()
            Files.move(tmp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            ImageDraftSaveResult.Saved(document.revision)
        }finally{tmp.delete()}
    }}
    suspend fun references():ImageDraftReferences = withContext(Dispatchers.IO) { mutex.withLock {
        var preserve=false;val uris=mutableSetOf<String>()
        directory.listFiles().orEmpty().forEach{f->
            val loaded=if(f.extension=="json")runCatching{read(f.nameWithoutExtension)}.getOrNull() else null
            if(loaded is ImageDraftLoadResult.Valid)uris+=loaded.document.source.uri else preserve=true
        }
        ImageDraftReferences(uris,preserve)
    } }
    suspend fun discard(sourceId:String)=withContext(Dispatchers.IO){mutex.withLock{file(sourceId).delete()}}
}
