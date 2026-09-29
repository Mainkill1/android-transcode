package dev.forma.app.image
import dev.forma.app.data.publishVerifiedImage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
class ImagePublicationTest {
    @Test fun completionCallbackCancellationRollsBackPublication()=runBlocking {
        val directory=File(System.getProperty("java.io.tmpdir"),"image-publish-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        try{
            val candidate=File(directory,"candidate.png").apply{writeText("verified")};val output=File(directory,"result.png")
            try{publishVerifiedImage(candidate,output){throw CancellationException("durable write cancelled")};fail()}catch(_:CancellationException){}
            assertFalse("No durable completion means no published output",output.exists())
        }finally{directory.deleteRecursively()}
    }
    @Test fun stopAfterSuccessfulDurableCompletionKeepsArtifact()=runBlocking {
        val directory=File(System.getProperty("java.io.tmpdir"),"image-publish-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        try{
            val candidate=File(directory,"candidate.png").apply{writeText("verified")};val output=File(directory,"result.png")
            var durable=false;lateinit var worker:Job
            worker=launch(start=CoroutineStart.LAZY){publishVerifiedImage(candidate,output){durable=true;worker.cancel()}}
            worker.start();worker.join();assertTrue(durable);assertEquals("verified",output.readText())
        }finally{directory.deleteRecursively()}
    }
}
