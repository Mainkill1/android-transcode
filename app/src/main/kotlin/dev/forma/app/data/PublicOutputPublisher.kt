package dev.forma.app.data

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.media.MediaScannerConnection
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.forma.app.settings.TreeGrantStore
import dev.forma.core.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeliveryCopyProgress(val id:String,val copiedBytes:Long,val totalBytes:Long)

/** Owns public artifacts. Queue receipt updates are the durable publication journal. */
class PublicOutputPublisher(private val context:Context, private val queue:QueueRepository,
    private val onChunkCopied:suspend (Uri,Long)->Unit={_,_->}) {
    private val resolver get()=context.contentResolver
    private val mutableProgress=MutableStateFlow<DeliveryCopyProgress?>(null)
    val progress=mutableProgress.asStateFlow()

    suspend fun publish(entry:QueueEntry, privateFile:File):DeliveryReceipt.Saved = withContext(Dispatchers.IO) {
        require(entry.state==JobState.COMPLETED && privateFile.isFile) { "Verified private output is unavailable." }
        val destination=requireNotNull(entry.delivery.destination) { "This older result is private in Forma." }
        val current=entry.delivery.receipt
        require(current==DeliveryReceipt.Waiting || current is DeliveryReceipt.Copying) { "This delivery is not pending." }
        val name=if(current is DeliveryReceipt.Copying) current.intentName else chooseName(entry,destination)
        val copying=if(current is DeliveryReceipt.Copying) current else DeliveryReceipt.Copying(name,null).also {
            check(queue.updateDelivery(entry.spec.id,current,Delivery(destination,it))) { "Delivery changed while preparing the copy." }
        }
        var uri=copying.uri?.let(Uri::parse)
        try {
            require(validName(entry,name)) { "The save journal does not match this file." }
            if(destination is SaveDestination.DocumentTree && !TreeGrantStore(context).validate(destination.uri))
                throw SecurityException("Forma cannot write to this folder. Choose it again in Save location, then retry save.")
            if(uri==null) {
                if(destination is SaveDestination.DocumentTree && current is DeliveryReceipt.Copying &&
                    nameTaken(destination,name)) throw IOException(
                    "A file with this name appeared in the chosen folder. Review it there before retrying; Forma will not overwrite it.")
                uri=recoverPending(destination,entry.spec.mime,name) ?: create(destination,entry.spec.mime,name)
                check(queue.updateDelivery(entry.spec.id,copying,Delivery(destination,copying.copy(uri=uri.toString())))) {
                    "Delivery changed after creating the copy."
                }
            }
            val alreadyPublished=destination is SaveDestination.FormaLibrary &&
                owns(destination,uri,name,allowPublished=true) && !owns(destination,uri,name)
            check(owns(destination,uri,name,allowPublished=alreadyPublished)) { "The saved partial file no longer belongs to this job." }
            mutableProgress.value=DeliveryCopyProgress(entry.spec.id,0,privateFile.length())
            val sourceDigest=MessageDigest.getInstance("SHA-256")
            val bytes=if(alreadyPublished) privateFile.inputStream().use { input ->
                val buffer=ByteArray(64*1024);var total=0L
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val count=input.read(buffer)
                    if(count<0) break
                    sourceDigest.update(buffer,0,count);total+=count
                }
                total
            } else resolver.openOutputStream(uri,"wt")?.use { output ->
                privateFile.inputStream().use { input ->
                    val buffer=ByteArray(64*1024)
                    var total=0L
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val count=input.read(buffer)
                        if(count<0) break
                        output.write(buffer,0,count);sourceDigest.update(buffer,0,count);total+=count
                        mutableProgress.value=DeliveryCopyProgress(entry.spec.id,total,privateFile.length())
                        onChunkCopied(uri,total)
                    }
                    output.flush();total
                }
            } ?: throw IOException("The save location could not be opened for writing.")
            runCatching { resolver.openFileDescriptor(uri,"rw")?.use { it.fileDescriptor.sync() } }
            require(bytes==privateFile.length()) { "The private output changed while saving." }
            val destinationDigest=MessageDigest.getInstance("SHA-256")
            val copiedBytes=resolver.openInputStream(uri)?.use { input ->
                val buffer=ByteArray(64*1024);var total=0L
                while(true) {
                    currentCoroutineContext().ensureActive()
                    val count=input.read(buffer)
                    if(count<0) break
                    destinationDigest.update(buffer,0,count);total+=count
                }
                total
            } ?: throw IOException("The saved copy could not be checked.")
            val destinationHash=destinationDigest.digest()
            require(copiedBytes==bytes && destinationHash.contentEquals(sourceDigest.digest())) {
                "The saved copy did not match the verified output."
            }
            val finalName=resolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                if(cursor.moveToFirst()) cursor.getString(0) else null
            } ?: name
            if(!alreadyPublished && Build.VERSION.SDK_INT>=29 && destination is SaveDestination.FormaLibrary)
                check(resolver.update(uri,ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING,0) },null,null)==1) {
                    "Android could not make the saved file visible."
                }
            if(Build.VERSION.SDK_INT<29 && destination is SaveDestination.FormaLibrary)
                MediaScannerConnection.scanFile(context,arrayOf(legacyFile(destination.category,name).absolutePath),
                    arrayOf(entry.spec.mime),null)
            val saved=DeliveryReceipt.Saved(uri.toString(),finalName,bytes,
                destinationHash.joinToString("") { "%02x".format(it) })
            val last=DeliveryReceipt.Copying(name,uri.toString())
            check(queue.updateDelivery(entry.spec.id,last,Delivery(destination,saved))) { "Delivery changed after saving the copy." }
            saved
        } catch(cancel:CancellationException) {
            withContext(NonCancellable) { fail(entry.spec.id,destination,name,uri,"Saving was stopped.") }
            throw cancel
        } catch(error:Exception) {
            withContext(NonCancellable) { fail(entry.spec.id,destination,name,uri,error.message ?: "Save failed.") }
            throw error
        } finally {
            mutableProgress.value=null
        }
    }

    private suspend fun fail(id:String,destination:SaveDestination,name:String,uri:Uri?,message:String) {
        var retained:Uri?=null
        if(uri!=null) {
            val ownership=runCatching { owns(destination,uri,name) }
            if(ownership.getOrDefault(false)) {
                val deleted=runCatching { if(destination is SaveDestination.DocumentTree) DocumentsContract.deleteDocument(resolver,uri)
                    else resolver.delete(uri,null,null)>0 }.getOrDefault(false)
                if(!deleted) retained=uri
            } else if(destination is SaveDestination.DocumentTree &&
                (ownership.isFailure || !TreeGrantStore(context).validate(destination.uri))) retained=uri
        }
        queue.updateDelivery(id,DeliveryReceipt.Copying(name,uri?.toString()),
            Delivery(destination,DeliveryReceipt.Failed((message+
                if(retained!=null) " Partial file remains in the folder; remove it before retrying." else "").take(500),retained?.toString())))
    }

    fun clearRetainedPartial(entry:QueueEntry,uriText:String):Boolean {
        val destination=requireNotNull(entry.delivery.destination)
        val uri=Uri.parse(uriText)
        if(destination is SaveDestination.DocumentTree) {
            if(!TreeGrantStore(context).validate(destination.uri))
                throw SecurityException("Choose this folder again in Save location, then retry save.")
            if(!owns(destination,uri,"")) return true
            return DocumentsContract.deleteDocument(resolver,uri)
        }
        val library=destination as SaveDestination.FormaLibrary
        val (collection,_)=collection(library.category)
        require(uri.authority==collection.authority && uri.path.orEmpty().startsWith(collection.path.orEmpty()+"/")) {
            "The saved partial URI does not belong to this media folder."
        }
        val rowId=ContentUris.parseId(uri).toString()
        val queryUri=if(Build.VERSION.SDK_INT>=29) MediaStore.setIncludePending(collection) else collection
        val name=resolver.query(queryUri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns._ID}=?",arrayOf(rowId),null)?.use { c ->
            if(c.moveToFirst()) c.getString(0) else null
        } ?: return true
        if(!validName(entry,name) || !owns(destination,uri,name)) return false
        return resolver.delete(uri,null,null)>0
    }

    private fun validName(entry:QueueEntry,name:String):Boolean {
        val base=displayName(entry)
        return name==base || Regex(Regex.escape(base.substringBeforeLast('.'))+"_[2-9][0-9]{0,2}\\."+
            Regex.escape(base.substringAfterLast('.'))).matches(name)
    }

    private fun chooseName(entry:QueueEntry,destination:SaveDestination):String {
        if(destination is SaveDestination.DocumentTree && !TreeGrantStore(context).validate(destination.uri))
            throw SecurityException("Forma cannot write to this folder. Choose it again in Save location, then retry save.")
        val base=displayName(entry)
        val stem=base.substringBeforeLast('.')
        val extension=base.substringAfterLast('.')
        for(index in 1..999) {
            val name=if(index==1) base else "${stem}_$index.$extension"
            if(destination is SaveDestination.FormaLibrary && recoverPending(destination,entry.spec.mime,name)!=null)
                return name
            if(!nameTaken(destination,name)) return name
        }
        throw IOException("The destination has too many files with this name.")
    }

    private fun nameTaken(destination:SaveDestination,name:String):Boolean = when(destination) {
        is SaveDestination.FormaLibrary -> if(Build.VERSION.SDK_INT<29) legacyFile(destination.category,name).exists() else {
            val (collection,path)=collection(destination.category)
            resolver.query(MediaStore.setIncludePending(collection),arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",arrayOf(name),null)?.use { c ->
                var found=false
                while(c.moveToNext()) if(c.getString(0)==path) found=true
                found
            } ?: false
        }
        is SaveDestination.DocumentTree -> {
            val tree=Uri.parse(destination.uri)
            queryTreeChildren(tree,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)) { c ->
                var found=false
                while(c.moveToNext()) if(c.getString(0)==name) found=true
                found
            }
        }
    }

    private fun collection(category:MediaCategory):Pair<Uri,String> = when(category) {
        MediaCategory.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Forma/"
        MediaCategory.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Forma/"
        MediaCategory.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Forma/"
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(category:MediaCategory,name:String):File {
        val directory=when(category) {
            MediaCategory.VIDEO -> Environment.DIRECTORY_MOVIES
            MediaCategory.AUDIO -> Environment.DIRECTORY_MUSIC
            MediaCategory.IMAGE -> Environment.DIRECTORY_PICTURES
        }
        return File(File(Environment.getExternalStoragePublicDirectory(directory),"Forma"),name)
    }

    private fun recoverPending(destination:SaveDestination,mime:String,name:String):Uri? {
        // A document provider exposes no creator identity. A same-name child is not proof
        // that Forma created it before the URI could be journaled, so never truncate it.
        if(destination !is SaveDestination.FormaLibrary || Build.VERSION.SDK_INT<29) return null
        val (collection,path)=collection(destination.category)
        val projection=arrayOf(MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
        val matches=mutableListOf<Uri>()
        resolver.query(MediaStore.setIncludePending(collection),projection,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",arrayOf(name),null)?.use { cursor ->
            while(cursor.moveToNext()) if(cursor.getString(1)==name && cursor.getString(2)==path &&
                cursor.getString(3)==mime && cursor.getInt(4)==1 && cursor.getString(5)==context.packageName)
                matches+=ContentUris.withAppendedId(collection,cursor.getLong(0))
        }
        check(matches.size<=1) { "Several unfinished copies have this name. Remove them before retrying." }
        return matches.singleOrNull()
    }

    private fun owns(destination:SaveDestination,uri:Uri,name:String,allowPublished:Boolean=false):Boolean = when(destination) {
        is SaveDestination.FormaLibrary -> {
            if(Build.VERSION.SDK_INT<29) {
                val (collection,_)=collection(destination.category)
                if(uri.authority!=collection.authority || !uri.path.orEmpty().startsWith(collection.path.orEmpty()+"/")) false
                else resolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.DATA),null,null,null)?.use { c ->
                    c.moveToFirst() && c.getString(0)==name && c.getString(1)==legacyFile(destination.category,name).absolutePath
                } ?: false
            } else {
                val (collection,path)=collection(destination.category)
                if(uri.authority!=collection.authority || !uri.path.orEmpty().startsWith(collection.path.orEmpty()+"/")) false
                else resolver.query(uri,arrayOf(MediaStore.MediaColumns.DISPLAY_NAME,MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.OWNER_PACKAGE_NAME),null,null,null)?.use { c ->
                    c.moveToFirst() && c.getString(0)==name && c.getString(1)==path &&
                        (allowPublished || c.getInt(2)==1) && c.getString(3)==context.packageName
                } ?: false
            }
        }
        is SaveDestination.DocumentTree -> {
            val tree=Uri.parse(destination.uri)
            // The URI was returned by createDocument and journaled. Providers may rename
            // the child, so prove it is still a direct child instead of comparing titles.
            if(uri.authority!=tree.authority || uri.pathSegments.take(2)!=tree.pathSegments.take(2)) false
            else {
                queryTreeChildren(tree,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) { c ->
                    var found=false
                    while(c.moveToNext()) if(DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0))==uri) found=true
                    found
                }
            }
        }
    }

    private inline fun <T> queryTreeChildren(tree:Uri,projection:Array<String>,read:(android.database.Cursor)->T):T {
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val cursor=resolver.query(children,projection,null,null,null)
            ?: throw IOException("The chosen folder could not list its files. Retry save when it is available.")
        return cursor.use(read)
    }

    private fun create(destination:SaveDestination,mime:String,name:String):Uri = when(destination) {
        is SaveDestination.FormaLibrary -> {
            val (collection,path)=collection(destination.category)
            if(Build.VERSION.SDK_INT<29) {
                check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED &&
                    context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED) {
                    "Allow storage access to save in $path, then tap Retry save."
                }
                val file=legacyFile(destination.category,name)
                check(file.parentFile?.mkdirs()==true || file.parentFile?.isDirectory==true) { "The $path folder is unavailable." }
                check(!file.exists()) { "A file with this name already exists in $path." }
                resolver.insert(collection,ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                    put(MediaStore.MediaColumns.MIME_TYPE,mime)
                    put(MediaStore.MediaColumns.DATA,file.absolutePath)
                }) ?: throw IOException("Android could not create the public save location.")
            } else resolver.insert(collection,ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,name)
                put(MediaStore.MediaColumns.MIME_TYPE,mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,path)
                put(MediaStore.MediaColumns.IS_PENDING,1)
            }) ?: throw IOException("Android could not create the public save location.")
        }
        is SaveDestination.DocumentTree -> {
            val tree=Uri.parse(destination.uri)
            val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
            val existing=queryTreeChildren(tree,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) { c ->
                val ids=mutableSetOf<String>()
                while(c.moveToNext()) ids+=c.getString(0)
                ids
            }
            val created=DocumentsContract.createDocument(resolver,parent,mime,name)
                ?: throw IOException("The chosen folder could not create a file.")
            check(DocumentsContract.getDocumentId(created) !in existing) {
                "The chosen folder returned an existing file instead of creating a new one."
            }
            created
        }
    }

    private fun displayName(entry:QueueEntry):String {
        val stem=entry.spec.source.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"),"_")
            .trim(' ','.').take(80).ifBlank { "media" }
        return "${stem}_forma_${entry.spec.id.take(8)}.${entry.spec.extension}"
    }
}
