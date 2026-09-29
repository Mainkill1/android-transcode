package dev.forma.app.image
import org.junit.Assert.*
import org.junit.Test
import org.json.*
import java.io.File
class ImageReportContractTest {
    @Test fun unknownCaseAndUnavailableNativeAllCannotPass() {
        assertThrows(IllegalArgumentException::class.java){ImageReportContract.select("unknown",listOf("geometry_identity"),false)}
        assertThrows(IllegalArgumentException::class.java){ImageReportContract.select("all",listOf("geometry_identity"),false)}
    }
    @Test fun reusedRunDirectoryIsRejected() {
        val directory=File(System.getProperty("java.io.tmpdir"),"image-report-${java.util.UUID.randomUUID()}")
        try {ImageReportContract.reserve(directory);assertThrows(IllegalArgumentException::class.java){ImageReportContract.reserve(directory)}}finally{directory.deleteRecursively()}
    }
    @Test fun staleZeroCountAndArtifactMismatchCannotPass() {
        val id=java.util.UUID.randomUUID().toString()
        val valid=JSONObject().put("schema",1).put("runId",id).put("status","PASS").put("selectedCount",1).put("executedCount",1).put("passedCount",1).put("selectedCases",JSONArray(listOf("one"))).put("installedApks",JSONArray().put(JSONObject().put("name","base.apk").put("sha256","hash")))
        ImageReportContract.requirePass(valid,id,listOf("one"),mapOf("base.apk" to "hash"))
        for(bad in listOf(JSONObject(valid.toString()).put("runId","stale"),JSONObject(valid.toString()).put("executedCount",0),JSONObject(valid.toString()).put("passedCount",0)))assertThrows(IllegalArgumentException::class.java){ImageReportContract.requirePass(bad,id,listOf("one"),mapOf("base.apk" to "hash"))}
        assertThrows(IllegalArgumentException::class.java){ImageReportContract.requirePass(valid,id,listOf("one"),mapOf("base.apk" to "other"))}
    }
}
