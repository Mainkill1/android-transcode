package dev.forma.app.image
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import dev.forma.ffmpeg.image.ImageProbe
import org.json.*
import java.io.File
import java.util.UUID
object ImageReport {
    fun validateRunId(value:String):String {require(UUID.fromString(value).toString()==value){"Run ID must be a canonical UUID."};return value}
    fun artifacts(context:Context):JSONObject {
        val files=listOf(context.applicationInfo.sourceDir)+context.applicationInfo.splitSourceDirs.orEmpty()
        return JSONObject().put("installedApks",JSONArray(files.map{path->JSONObject().put("name",File(path).name).put("sha256",ImageProbe.hash(File(path)))}))
            .put("model",Build.MODEL).put("api",Build.VERSION.SDK_INT).put("abis",JSONArray(Build.SUPPORTED_ABIS.toList())).put("pageSize",Os.sysconf(OsConstants._SC_PAGESIZE))
    }
    fun write(directory:File,report:JSONObject){val temp=File(directory,"report.tmp");val final=File(directory,"report.json");require(!final.exists());temp.outputStream().use{it.write(report.toString(2).toByteArray());it.fd.sync()};check(temp.renameTo(final));check(JSONObject(final.readText()).getString("runId")==report.getString("runId"))}
}
