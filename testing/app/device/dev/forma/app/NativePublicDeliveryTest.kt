package dev.forma.app

import android.content.ContextWrapper
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.audio.writeFloatWav
import dev.forma.app.data.*
import dev.forma.core.*
import dev.forma.core.image.QueueJobSpec
import dev.forma.core.image.ImageEditDocument
import dev.forma.core.image.ImageOutputPolicy
import dev.forma.core.image.ImageFormat
import dev.forma.core.image.ImageJobSpec
import dev.forma.app.image.ImageFixtures
import dev.forma.app.image.ImageMarkupRenderer
import dev.forma.ffmpeg.ManagedFfmpegBridge
import dev.forma.ffmpeg.createFfmpegBridge
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real native conversion, queue verification, and public delivery on the target phone. */
class NativePublicDeliveryTest {
    @Test fun verifiedVideoAudioAndImageAppearInFormaMediaFolders() = runBlocking(Dispatchers.IO) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaNative")=="true")
        val app=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(app.cacheDir,"native-delivery-${UUID.randomUUID()}").apply {mkdirs()}
        val context=object:ContextWrapper(app) {override fun getFilesDir():File=root}
        val inputDir=File(app.filesDir,"imports/native-delivery-${UUID.randomUUID()}").apply {mkdirs()}
        val video=File(inputDir,"sample.mp4")
        val audio=File(inputDir,"sample.wav")
        val bridge=ManagedFfmpegBridge(createFfmpegBridge())
        check(bridge.capabilities().available)
        val files=MediaFiles(context,bridge)
        val queue=QueueRepository(context)
        val saved=mutableListOf<Uri>()
        try {
            val generated=bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-y","-f","lavfi",
                "-i","testsrc2=s=160x90:r=24:d=2","-f","lavfi","-i","sine=frequency=440:sample_rate=48000:duration=2",
                "-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac",video.path)) {}
            check(generated.exitCode==0) {generated.diagnostics}
            writeFloatWav(audio,FloatArray(96000) {(.1*sin(2*PI*440*it/48000)).toFloat()},48000)
            queue.load()
            val cases=listOf(
                Triple(video,Settings(video=VideoEncoder.X264),MediaCategory.VIDEO),
                Triple(audio,Settings(container=Container.WAV,audio=AudioEncoder.PCM_F32LE),MediaCategory.AUDIO))
            for((input,settings,category) in cases) {
                val source=bridge.probe(input.path).copy(uri=FileProvider.getUriForFile(app,"${app.packageName}.files",input).toString(),name=input.name)
                val spec=JobSpec(UUID.randomUUID().toString(),source,Trim(0,1500),settings)
                val destination=SaveDestination.FormaLibrary(category)
                queue.addTagged(listOf(QueueJobSpec.Av(spec)),mapOf(spec.id to Delivery(destination,DeliveryReceipt.Waiting)))
                check(queue.claimNext()?.id==spec.id)
                FfmpegTranscoder(files,bridge).run(spec,{queue.transition(spec.id,it)},{})
                check(queue.entries.value.first {it.spec.id==spec.id}.state==JobState.COMPLETED)
                DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
                val receipt=queue.entries.value.first {it.spec.id==spec.id}.delivery.receipt as DeliveryReceipt.Saved
                val uri=Uri.parse(receipt.uri);saved+=uri
                val privateHash=MessageDigest.getInstance("SHA-256").digest(files.output(spec).readBytes())
                val publicHash=MessageDigest.getInstance("SHA-256").digest(app.contentResolver.openInputStream(uri)!!.use {it.readBytes()})
                check(privateHash.contentEquals(publicHash)) {"Public ${category.name.lowercase()} differs from verified output."}
                val checked=bridge.probe(files.output(spec).path)
                check(if(category==MediaCategory.VIDEO) checked.videoTracks==1 else checked.audioTracks==1)
                app.contentResolver.query(uri,arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use { c ->
                    check(c.moveToFirst())
                    check(c.getString(0)==if(category==MediaCategory.VIDEO) "Movies/Forma/" else "Music/Forma/")
                }
            }
            val (image,imageInfo)=ImageFixtures.png(app,inputDir,w=96,h=64)
            val imageSpec=ImageJobSpec(UUID.randomUUID().toString(),
                ImageEditDocument(source=ImageFixtures.source(app,image,imageInfo),
                    output=ImageOutputPolicy(format=ImageFormat.PNG)),imageInfo)
            val imageJob=QueueJobSpec.Image(imageSpec)
            queue.addTagged(listOf(imageJob),mapOf(imageSpec.id to
                Delivery(SaveDestination.FormaLibrary(MediaCategory.IMAGE),DeliveryReceipt.Waiting)))
            check(queue.claimNext()?.id==imageSpec.id)
            ImageTranscoder(files,bridge,ImageMarkupRenderer()).run(imageSpec,{queue.transition(imageSpec.id,it)},{})
            check(queue.entries.value.first {it.spec.id==imageSpec.id}.state==JobState.COMPLETED)
            DeliveryWorker(queue,files,PublicOutputPublisher(context,queue)).resumePending()
            val imageReceipt=queue.entries.value.first {it.spec.id==imageSpec.id}.delivery.receipt as DeliveryReceipt.Saved
            val imageUri=Uri.parse(imageReceipt.uri);saved+=imageUri
            val privateImage=files.output(imageJob).readBytes()
            val publicImage=app.contentResolver.openInputStream(imageUri)!!.use {it.readBytes()}
            check(privateImage.contentEquals(publicImage)) {"Public image differs from verified output."}
            app.contentResolver.query(imageUri,arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH),null,null,null)!!.use { c ->
                check(c.moveToFirst() && c.getString(0)=="Pictures/Forma/")
            }
        } finally {
            saved.forEach {app.contentResolver.delete(it,null,null)}
            inputDir.deleteRecursively();root.deleteRecursively()
        }
    }
}
