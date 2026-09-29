package dev.forma.app.image

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.*
import dev.forma.app.data.JobCodec
import dev.forma.app.work.RunMode
import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.ffmpeg.image.ImageProbe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual Share receiver, editor actions and Convert tap exercise the production foreground dispatch. */
class ImageShareServiceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun imageShareEditConvertPublishesFrozenJobThroughForegroundService() {
        assumeTrue("Explicit native opt-in required",InstrumentationRegistry.getArguments().getString("formaNative")=="true")
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        require(context.packageName.endsWith(".lab")){"This disposable integration test requires the isolated LAB application."}
        val graph=(context.applicationContext as FormaApplication).graph
        runBlocking{graph.initialize();graph.imagePreviews.cancelAndJoin()}
        assertEquals(RunMode.IDLE,graph.runs.state.value.mode)
        require(graph.queue.entries.value.isEmpty()){"This LAB-only integration test requires an empty queue; preserve foreign rows."}
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            instrument.uiAutomation.grantRuntimePermission(context.packageName,Manifest.permission.POST_NOTIFICATIONS)
        val queue=AtomicFile(File(context.filesDir,"queue-v1.json"))
        val originalExists=queue.baseFile.exists();val original=if(originalExists)queue.openRead().use{it.readBytes()}else null
        val owned=File(context.filesDir,"image-service-tests/${UUID.randomUUID()}").apply{mkdirs()}
        original?.let{File(owned,"queue-before.json").writeBytes(it)}
        fun writeQueue(bytes:ByteArray){val out=queue.startWrite();try{out.write(bytes);queue.finishWrite(out)}catch(e:Throwable){queue.failWrite(out);throw e}}
        val fixture=File(context.filesDir,"imports/image-service-${owned.name}")
        val (input,info)=ImageFixtures.png(context,fixture,512,512,alpha=false,noise=true)
        val drafts=File(context.filesDir,"image-drafts")
        val draft=File(drafts,"${info.hash}.json");val previousDraft=if(draft.exists())draft.readBytes()else null
        val beforeImports=File(context.filesDir,"imports").listFiles().orEmpty().map{it.name}.toSet()
        val authority=instrument.context.packageName+".writable-image-export"
        val provider=Uri.parse("content://$authority")
        context.contentResolver.call(provider,"reset",null,null)
        context.contentResolver.call(provider,"seed",null,android.os.Bundle().apply{putByteArray("png",input.readBytes())})
        val incoming=provider.buildUpon().appendPath("original.png").build()
        var output:File?=null;var ownedImport:File?=null;val ownedJobId=AtomicReference<String?>(null);val ownedSourceUri=AtomicReference<String?>(null);val ownedRunId=AtomicReference<Long?>(null)
        val foregroundIds=CopyOnWriteArraySet<Long>();val foregroundObserved=AtomicBoolean(false)
        val observerScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val observer=observerScope.launch(start=CoroutineStart.UNDISPATCHED){combine(graph.runs.state,graph.queue.entries){state,rows->state to rows}.collect{(state,rows)->
            val row=rows.singleOrNull()
            if(state.mode!=RunMode.IDLE && row?.spec is QueueJobSpec.Image && row.spec.source.uri==ownedSourceUri.get() && row.state in setOf(JobState.PREPARING,JobState.RUNNING,JobState.VERIFYING)){
                ownedJobId.compareAndSet(null,row.spec.id)
                if(context.getSystemService(NotificationManager::class.java).activeNotifications.any{it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE!=0}){
                    foregroundObserved.set(true);state.id?.let{ id->ownedRunId.compareAndSet(null,id);foregroundIds.add(id) }
                }
            }
        }}
        try {
            writeQueue(JobCodec.encode(emptyList()).toByteArray());runBlocking{graph.queue.load()}
            val share=Intent(context,MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/png")
                .putExtra(Intent.EXTRA_STREAM,incoming).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply{clipData=ClipData.newUri(context.contentResolver,"Disposable PNG",incoming)}
            ActivityScenario.launch<MainActivity>(share).use{scenario->
                lateinit var vm:TranscodeViewModel
                scenario.onActivity{vm=ViewModelProvider(it)[TranscodeViewModel::class.java]}
                compose.waitUntil(15000){vm.state.value.sources.size==1 && vm.state.value.fileTask==null && !vm.state.value.validating}
                val source=vm.state.value.sources.single().source;ownedSourceUri.set(source.uri)
                assertNotNull(source.imageInfo);assertEquals(0L,source.durationMs)
                assertEquals(context.packageName+".files",Uri.parse(source.uri).authority)
                val importId=Uri.parse(source.uri).pathSegments[1];require(UUID.fromString(importId).toString()==importId && importId !in beforeImports)
                ownedImport=File(context.filesDir,"imports/$importId")
                assertEquals(incoming.toString(),source.imageOriginalUri)
                assertEquals(info.hash,source.imageInfo!!.hash);assertTrue(graph.queue.entries.value.isEmpty())
                compose.onNodeWithText("Edit image").performScrollTo().performClick()
                compose.waitUntil(10000){vm.state.value.imageEditor.open}
                compose.onNodeWithTag("image-canvas").assertExists()
                for((label,value) in listOf("Left px" to "128","Top px" to "128","Right px" to "384","Bottom px" to "384")){
                    compose.onNodeWithTag("editor").performScrollToNode(hasText(label))
                    compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement(value)
                    compose.waitUntil(10000){!vm.state.value.validating}
                }
                compose.waitUntil(10000){vm.state.value.imageDocuments.getValue(source.uri).crop==NormalizedCrop(.25,.25,.75,.75)}
                val cropped=vm.state.value.imageDocuments.getValue(source.uri)
                scenario.onActivity{vm.act(UiAction.ChangeImage(cropped.copy(output=cropped.output.copy(format=ImageFormat.PNG,targetBytes=10_000_000))))}
                compose.waitUntil(10000){!vm.state.value.validating && vm.state.value.imageDocuments.getValue(source.uri).output.format==ImageFormat.PNG}
                val requested=vm.state.value.imageDocuments.getValue(source.uri)
                compose.onNodeWithTag("convert").assertIsEnabled().performClick()
                compose.waitUntil(15000){graph.queue.entries.value.size==1}
                val queued=graph.queue.entries.value.single();ownedJobId.compareAndSet(null,queued.spec.id);require(ownedJobId.get()==queued.spec.id);output=graph.files.output(queued.spec);val frozen=(queued.spec as QueueJobSpec.Image).job
                assertEquals(requested,frozen.document);assertEquals(ImageFormat.PNG,frozen.resolvedFormat)
                // A later editor change cannot rewrite the service's frozen crop/output.
                scenario.onActivity{vm.act(UiAction.ChangeImage(requested.copy(quarterTurns=1)))}
                try{compose.waitUntil(60000){graph.queue.entries.value.single().state==JobState.COMPLETED}}
                catch(e:Throwable){throw AssertionError("Image foreground conversion failed: ${vm.state.value.message}; ${graph.queue.entries.value}",e)}
                runBlocking{withTimeout(10000){graph.runs.state.first{it.mode==RunMode.IDLE}}}
                assertEquals(1,foregroundIds.size);assertTrue("Actual foreground notification must be observed while service owns work",foregroundObserved.get())
                assertNull(graph.queue.progress.value)
                val completed=graph.queue.entries.value.single();assertEquals(frozen,(completed.spec as QueueJobSpec.Image).job)
                output=graph.files.output(completed.spec);assertTrue(output!!.length() in 1 until 10_000_000)
                val actual=runBlocking{graph.bridge.inspectImage(output!!.path)}
                assertEquals(ImageFormat.PNG,actual.format);assertEquals(256,actual.width);assertEquals(256,actual.height)
                assertEquals(ImageAlpha.OPAQUE,actual.alpha)
                val durable=JobCodec.decode(queue.openRead().use{it.readBytes().toString(Charsets.UTF_8)}).single()
                assertEquals(JobState.COMPLETED,durable.state);assertEquals(frozen,(durable.spec as QueueJobSpec.Image).job)
                assertTrue(durable.message.contains("effective PNG"));assertFalse(File(context.filesDir,"work/${frozen.id}").exists())
                assertEquals(info.hash,ImageProbe.hash(input))
            }
        } finally {
            runBlocking {
                val state=graph.runs.state.value
                if(state.mode!=RunMode.IDLE){require(state.id==ownedRunId.get()){ "A foreign or unidentified worker appeared; do not stop it or replace its queue." };graph.runs.stop(state.id);withTimeout(10000){graph.runs.state.first{it.mode==RunMode.IDLE}}}
                graph.imagePreviews.cancelAndJoin()
            }
            observer.cancel();observerScope.cancel()
            require(graph.queue.entries.value.all{it.spec.id==ownedJobId.get()}){"A foreign queue row appeared; preserve it and the owned backup."}
            output?.delete()
            // Restore only this LAB queue's captured bytes after our own worker is released.
            if(original!=null)writeQueue(original)else queue.delete()
            runBlocking{graph.queue.load()}
            if(original!=null)writeQueue(original)else queue.delete()
            if(previousDraft!=null)draft.writeBytes(previousDraft)else draft.delete()
            ownedImport?.deleteRecursively()
            fixture.deleteRecursively();owned.deleteRecursively()
        }
    }
}
