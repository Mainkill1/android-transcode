package dev.forma.app.image
import org.json.JSONObject
import java.io.File
/** Test-only protocol checks prevent a zero-test/stale-artifact native PASS. */
object ImageReportContract {
    fun select(requested:String,cases:List<String>,nativeAvailable:Boolean):List<String> {
        require(requested=="all" || requested in cases || requested=="native_missing"){"Unknown or unsupported image case."}
        require(requested!="all" || nativeAvailable){"CAPABILITY_UNAVAILABLE: all requires the source-built native payload."}
        return if(requested=="all")cases else listOf(requested)
    }
    fun reserve(directory:File){require(!directory.exists() && directory.mkdirs()){"Run UUID has already been used or storage is unavailable."}}
    fun requirePass(report:JSONObject,runId:String,selected:List<String>,installed:Map<String,String>){
        require(report.getInt("schema")==1 && report.getString("runId")==runId && report.getString("status")=="PASS")
        require(selected.isNotEmpty() && report.getInt("selectedCount")==selected.size && report.getInt("executedCount")==selected.size && report.getInt("passedCount")==selected.size){"Incomplete or zero-test report."}
        val ids=report.getJSONArray("selectedCases");require((0 until ids.length()).map(ids::getString)==selected)
        val apks=report.getJSONArray("installedApks");val actual=(0 until apks.length()).associate {i->val apk=apks.getJSONObject(i);apk.getString("name") to apk.getString("sha256")}
        require(actual==installed){"Installed APK identity differs from the report."}
    }
}
