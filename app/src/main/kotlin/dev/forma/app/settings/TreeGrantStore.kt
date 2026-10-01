package dev.forma.app.settings

import android.content.Context
import android.util.AtomicFile
import dev.forma.core.SaveDestination
import java.io.File
import org.json.JSONObject

/** The remembered tree is separate from app settings; Android owns the actual grant. */
class TreeGrantStore(context: Context,
    private val writableGrants: () -> Set<String> = {
        context.contentResolver.persistedUriPermissions.filter { it.isWritePermission }.map { it.uri.toString() }.toSet()
    }) {
    private val file=AtomicFile(File(context.filesDir,"save-tree-v1.json"))

    fun validate(uri:String):Boolean = uri in writableGrants()

    fun selected():SaveDestination.DocumentTree? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return null
        val buffer=ByteArray(8193)
        val count=file.openRead().use { input ->
            var used=0
            while(used<buffer.size) {
                val read=input.read(buffer,used,buffer.size-used)
                if(read<0) break
                if(read>0) used+=read
            }
            used
        }
        require(count<=8192) { "Saved folder record is too large." }
        val record=JSONObject(String(buffer,0,count,Charsets.UTF_8))
        require(record.keys().asSequence().toSet()==setOf("schema","uri","label") && record.getInt("schema")==1) {
            "Saved folder record is unsupported."
        }
        return SaveDestination.DocumentTree(record.getString("uri"),record.getString("label"))
    }

    fun choose(uri:String,label:String):SaveDestination.DocumentTree {
        val tree=SaveDestination.DocumentTree(uri,label)
        require(validate(uri)) { "Forma does not have permission to write to that folder. Choose it again." }
        val bytes=JSONObject().put("schema",1).put("uri",uri).put("label",label).toString().toByteArray(Charsets.UTF_8)
        val output=file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch(error:Exception) { file.failWrite(output);throw error }
        return tree
    }
}
