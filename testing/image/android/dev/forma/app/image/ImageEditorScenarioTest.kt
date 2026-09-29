package dev.forma.app.image
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.FormaApplication
import dev.forma.app.data.*
import dev.forma.core.*
import dev.forma.core.image.*
import dev.forma.core.image.ImageFormat
import dev.forma.ffmpeg.*
import dev.forma.ffmpeg.image.*
import kotlinx.coroutines.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
/** Direct instrumentation selection only; every output goes through the production image executor. */
class ImageEditorScenarioTest {
    private val nativeCases=listOf("geometry_identity","orientation_all_eight","geometry_crop_turn_resize","geometry_odd_png","alpha_geometry","alpha_blur_edges","jpeg_alpha_block","jpeg_flatten","adjustments_neutral","adjustments_known_pixels","markup_unicode","solid_redaction","source_corrupt","source_animation","source_unsupported_color","export_format_mismatch","export_cap_retries","export_cap_exhausted","cancel_prepare","cancel_encode","cancel_verify","publication_collision","full_device_roundtrip")
    private val expectedErrors=mapOf("native_missing" to "CAPABILITY_UNAVAILABLE","jpeg_alpha_block" to "ALPHA_WOULD_BE_LOST","source_uri_revoked" to "SOURCE_ACCESS","source_corrupt" to "DECODE_FAILED","source_animation" to "UNSUPPORTED_IMAGE","source_unsupported_color" to "UNSUPPORTED_IMAGE","draft_corrupt" to "DRAFT_CORRUPT","export_format_mismatch" to "OUTPUT_INVALID","export_cap_exhausted" to "CANNOT_FIT","publication_collision" to "OUTPUT_EXISTS","cancel_prepare" to "CANCELLED","cancel_encode" to "CANCELLED","cancel_verify" to "CANCELLED")
    private val androidCases=listOf("source_uri_revoked","preview_stale","draft_corrupt")
    @Test fun selectedCases()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val args=InstrumentationRegistry.getArguments();org.junit.Assume.assumeTrue("Native image scenarios require explicit formaImageCase/runId arguments.",args.containsKey("formaImageCase"));val requested=args.getString("formaImageCase")?:error("formaImageCase is required; choose a named case or all.")
        val runId=ImageReport.validateRunId(args.getString("formaImageRunId")?:error("formaImageRunId is required."))
        val selected=if(requested=="all")nativeCases+androidCases else listOf(requested)
        require(selected.isNotEmpty() && selected.all{it in nativeCases+androidCases+"native_missing"}){"Unknown or unsupported image case."}
        val root=File(context.filesDir,"image-tests/$runId");ImageReportContract.reserve(root)
        val graph=(context.applicationContext as FormaApplication).graph;graph.initialize();val caps=graph.bridge.capabilities()
        val started=SystemClock.elapsedRealtime();val cases=JSONArray();var passed=0
        for(id in selected){val expected=expectedErrors[id];val row=JSONObject().put("id",id).put("startedMs",SystemClock.elapsedRealtime()).put("expectedOutcome",if(expected==null)"success" else "reject").put("expectedError",expected?:JSONObject.NULL)
            val preparations=JSONArray()
            val recorder=object:FfmpegBridge by graph.bridge {
                override suspend fun prepare(spec:ImageJobSpec,actual:ImageInfo,attempt:ImageAttempt,input:String,output:String):List<String> {
                    val arguments=graph.bridge.prepare(spec,actual,attempt,input,output)
                    val plan=ImagePlanner.plan(actual,spec,attempt,caps)
                    preparations.put(JSONObject().put("attempt",attempt.index).put("renderer",attempt.rendererIdentity).put("sourceHash",actual.hash).put("documentHash",documentHash(spec.document)).put("requestedFormat",spec.document.output.format.name).put("effectiveFormat",attempt.format.name).put("quality",attempt.quality).put("scale",attempt.scale).put("width",plan.geometry.outputSize.width).put("height",plan.geometry.outputSize.height).put("requiredFilters",JSONArray(plan.requiredFilters.sorted())).put("arguments",JSONArray(arguments.map{if(it.startsWith("/"))"<private>/"+File(it).name else it})))
                    return arguments
                }
            }
            try {if(id!="native_missing" && id in nativeCases)assertTrue("Native image suite requires the native build: ${caps.reason}",caps.available)
                val evidence=runCase(context,id,runId,caps,recorder)
                assertEquals("Declared error oracle $id",expected,evidence.optString("expectedError").takeIf{it.isNotEmpty()})
                row.put("evidence",evidence).put("observedOutcome",if(expected==null)"success" else "reject").put("assertionsPassed",true);passed++
            }catch(e:Throwable){row.put("observedOutcome","failure").put("assertionsPassed",false).put("error",e.toString()).put("stackTrace",e.stackTraceToString())}
            row.put("preparations",preparations).put("finishedMs",SystemClock.elapsedRealtime());cases.put(row)
        }
        val report=ImageReport.artifacts(context).put("schema",1).put("runId",runId).put("requestedSelection",requested).put("selectedCases",JSONArray(selected)).put("selectedCount",selected.size).put("executedCount",cases.length()).put("passedCount",passed).put("status",if(passed==selected.size)"PASS" else "FAIL").put("startedMs",started).put("finishedMs",SystemClock.elapsedRealtime()).put("declaredRevision",args.getString("formaDeclaredRevision")?:JSONObject.NULL).put("nativeBuild",caps.build).put("nativeConfiguration",caps.configuration).put("decoders",JSONArray(caps.decoders.toList())).put("encoders",JSONArray(caps.encoders.toList())).put("cases",cases)
        ImageReport.write(root,report)
        if(passed==selected.size){
            val actual=ImageReport.artifacts(context).getJSONArray("installedApks")
            val installed=(0 until actual.length()).associate {i->val apk=actual.getJSONObject(i);apk.getString("name") to apk.getString("sha256")}
            ImageReportContract.requirePass(JSONObject(File(root,"report.json").readText()),runId,selected,installed)
        }
        assertEquals("Fresh image report ${root.path}",selected.size,passed)
    }
    private suspend fun runCase(context:Context,id:String,runId:String,caps:Capabilities,bridge:FfmpegBridge):JSONObject {
        val graph=(context.applicationContext as FormaApplication).graph
        val transcoder=ImageTranscoder(graph.files,bridge,graph.imageMarkup)
        val dir=File(context.filesDir,"imports/image-$runId-$id")
        var (input,info)=if(id=="alpha_blur_edges")ImageFixtures.transparentEdges(dir)else ImageFixtures.png(context,dir,if(id=="geometry_crop_turn_resize")1200 else 101,if(id=="geometry_crop_turn_resize")800 else 77,alpha=id!="adjustments_known_pixels")
        var source=ImageFixtures.source(context,input,info)
        if(id=="full_device_roundtrip"){
            val authority=InstrumentationRegistry.getInstrumentation().context.packageName+".writable-image-export"
            val provider=android.net.Uri.parse("content://$authority")
            context.contentResolver.call(provider,"reset",null,null)
            context.contentResolver.call(provider,"seed",null,android.os.Bundle().apply{putByteArray("png",input.readBytes())})
            val incoming=android.net.Uri.parse("content://$authority/original.png")
            val imported=graph.files.inspect(incoming,persistPermission=false)
            info=imported.imageInfo ?:error("URI import did not route to image inspection")
            source=ImageSource(imported.uri,imported.name,info.hash,info.bytes,imported.imageOriginalUri)
        }
        var d=ImageEditDocument(source=source,output=ImageOutputPolicy(format=ImageFormat.PNG,targetBytes=null))
        if(id=="native_missing"){assertFalse(caps.available);expect("CAPABILITY_UNAVAILABLE"){transcoder.run(ImageJobSpec(UUID.randomUUID().toString(),d,info),{}, {})};return JSONObject().put("expectedError","CAPABILITY_UNAVAILABLE")}
        if(id=="geometry_crop_turn_resize")d=d.copy(crop=NormalizedCrop(.25,.25,.75,.75),quarterTurns=1,output=d.output.copy(resizeMode=ResizeMode.LONGEST_EDGE,longestEdge=300))
        if(id=="jpeg_alpha_block" || id=="jpeg_flatten")d=d.copy(output=d.output.copy(format=ImageFormat.JPEG,flatten=if(id=="jpeg_flatten")Rgba(255,255,255)else null))
        if(id=="alpha_blur_edges")d=d.copy(adjustments=ImageAdjustments(blurSigma=2.0))
        if(id=="adjustments_known_pixels")d=d.copy(adjustments=ImageAdjustments(saturation=0.0))
        if(id=="markup_unicode")d=d.copy(annotations=listOf(ImageAnnotation("unicode",AnnotationKind.TEXT,text="Hello\nمرحبا é \"quoted\"",textSize=10.0,color=Rgba(0,0,0))))
        if(id=="solid_redaction" || id=="full_device_roundtrip")d=d.copy(annotations=listOf(ImageAnnotation("secret",AnnotationKind.REDACTION,left=.5,top=.5,right=.9,bottom=.9,color=Rgba(0,0,0))))
        if(id=="full_device_roundtrip")d=d.copy(crop=NormalizedCrop(.05,.05,.95,.95),annotations=d.annotations+ImageAnnotation("label",AnnotationKind.TEXT,text="Saved copy مرحبا",textSize=10.0),output=d.output.copy(targetBytes=10_000_000))
        if(id=="jpeg_alpha_block"){expect("ALPHA_WOULD_BE_LOST"){transcoder.run(ImageJobSpec(UUID.randomUUID().toString(),d,info),{}, {})};return JSONObject().put("expectedError","ALPHA_WOULD_BE_LOST")}
        if(id=="source_uri_revoked"){
            val drafts=ImageDraftRepository(File(dir,"revoked-draft"));assertTrue(drafts.save(d,0) is ImageDraftSaveResult.Saved)
            val stageId=UUID.randomUUID().toString()
            expect("SOURCE_ACCESS"){ImageInputAdapter(context).stage("content://revoked-provider/missing",stageId)}
            assertTrue(drafts.load(source.hash) is ImageDraftLoadResult.Valid);assertFalse(File(context.filesDir,"imports/$stageId").exists())
            return JSONObject().put("expectedError","SOURCE_ACCESS").put("draftPreserved",true).put("stagingCleaned",true)
        }
        if(id=="source_corrupt"){
            val imports=File(context.filesDir,"imports");val before=imports.listFiles()?.map{it.name}?.toSet()?:emptySet()
            ImageFixtures.corruptDeflate(input)
            assertEquals(ImageFormat.PNG,ImageProbe.inspect(input.path).format)
            expect("DECODE_FAILED"){graph.files.inspect(android.net.Uri.parse(source.uri),persistPermission=false)}
            assertEquals(before,imports.listFiles()?.map{it.name}?.toSet()?:emptySet<String>())
            return JSONObject().put("expectedError","DECODE_FAILED").put("validHeaderActualNativeDecodeRejected",true).put("postStageImportCleaned",true)
        }
        if(id=="source_animation"){input.writeText("GIF89a");expect("UNSUPPORTED_IMAGE"){ImageInputAdapter(context).stage(source.uri,UUID.randomUUID().toString())};return JSONObject().put("expectedError","UNSUPPORTED_IMAGE")}
        if(id=="source_unsupported_color"){
            val tagged=ImageFixtures.orientedJpeg(dir,1,65535)
            expect("UNSUPPORTED_IMAGE"){ImageInputAdapter(context).stage(ImageFixtures.source(context,tagged,info).uri,UUID.randomUUID().toString())}
            return JSONObject().put("expectedError","UNSUPPORTED_IMAGE").put("actualExifColorSpace",65535)
        }
        if(id=="draft_corrupt"){val directory=File(dir,"drafts").apply{mkdirs()};val f=File(directory,"${source.hash}.json").apply{writeText("broken")};val repo=ImageDraftRepository(directory);assertTrue(repo.load(source.hash) is ImageDraftLoadResult.Corrupt);assertTrue(repo.save(d,0) is ImageDraftSaveResult.Preserved);assertEquals("broken",f.readText());return JSONObject().put("expectedError","DRAFT_CORRUPT")}
        if(id=="preview_stale") {
            assertTrue("Real asynchronous preview oracle requires native image support",caps.available)
            val entered=CompletableDeferred<Unit>();var first=true
            val slow=object:FfmpegBridge by bridge {
                override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult {
                    if(first && arguments.last().endsWith("preview.png")){
                        first=false;entered.complete(Unit)
                        return withContext(NonCancellable){delay(200);bridge.execute(arguments,onProgress)}
                    }
                    return bridge.execute(arguments,onProgress)
                }
            }
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val controller=ImagePreviewController(scope,graph.runs,graph.files,slow,graph.imageMarkup,context)
            try {
                controller.request(d.copy(revision=1),info);withTimeout(10000){entered.await()}
                controller.request(d.copy(revision=2,adjustments=ImageAdjustments(saturation=0.0)),info)
                withTimeout(15000){while(controller.state.value.revision!=2L || controller.state.value.status!="Preview"){
                    controller.state.value.error?.let{error(it)};delay(10)
                }}
                assertEquals(2L,controller.state.value.revision);assertNotNull(controller.state.value.path)
                val rendered=BitmapFactory.decodeFile(controller.state.value.path);val c=rendered.getPixel(5,5)
                assertTrue(kotlin.math.abs(Color.red(c)-Color.green(c))<=1);assertTrue(kotlin.math.abs(Color.red(c)-Color.blue(c))<=1);rendered.recycle()
                return JSONObject().put("staleRevisionRejected",true).put("supersededNativeEncodeFinishedLate",true).put("nativeCurrentPixelsVerified",true)
            }finally{controller.cancelAndJoin();scope.cancel()}
        }
        if(id=="export_format_mismatch"){
            val wrong=object:FfmpegBridge by bridge {
                override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult {
                    val result=bridge.execute(arguments,onProgress)
                    if(arguments.last().endsWith("candidate.png")){
                        val (other,_)=ImageFixtures.png(context,File(dir,"wrong-size"),100,77)
                        other.copyTo(File(arguments.last()),overwrite=true)
                    }
                    return result
                }
            }
            val rejected=ImageJobSpec(UUID.randomUUID().toString(),d,info)
            expect("OUTPUT_INVALID"){ImageTranscoder(graph.files,wrong,graph.imageMarkup).run(rejected,{}, {})}
            assertFalse(graph.files.output(QueueJobSpec.Image(rejected)).exists())
            return JSONObject().put("expectedError","OUTPUT_INVALID").put("nativeEncodeBeforeMismatch",true).put("publication",false)
        }
        if(id=="orientation_all_eight"){
            for(o in 1..8){
                val encoded=ImageFixtures.orientedJpeg(dir,o);val actual=bridge.inspectImage(encoded.path)
                assertEquals(o,actual.orientation)
                val sourceBitmap=BitmapFactory.decodeFile(encoded.path)
                val src=ImageFixtures.source(context,encoded,actual);val doc=d.copy(source=src)
                val job=ImageJobSpec(UUID.randomUUID().toString(),doc,actual)
                transcoder.run(job,{},{});val output=graph.files.output(QueueJobSpec.Image(job))
                val b=BitmapFactory.decodeFile(output.path)
                val expected=when(o){1->sourceBitmap.getPixel(5,5);2->sourceBitmap.getPixel(sourceBitmap.width-6,5);3->sourceBitmap.getPixel(sourceBitmap.width-6,sourceBitmap.height-6);4->sourceBitmap.getPixel(5,sourceBitmap.height-6);5->sourceBitmap.getPixel(5,5);6->sourceBitmap.getPixel(5,sourceBitmap.height-6);7->sourceBitmap.getPixel(sourceBitmap.width-6,sourceBitmap.height-6);else->sourceBitmap.getPixel(sourceBitmap.width-6,5)}
                val width=if(o>=5)77 else 101;val height=if(o>=5)101 else 77
                assertEquals(width,b.width);assertEquals(height,b.height);assertColorClose("Native orientation $o",expected,b.getPixel(5,5),2)
                for(modern in if(android.os.Build.VERSION.SDK_INT>=28)listOf(false,true)else listOf(false)){
                    val displayed=ImageDisplayAdapter.original(context,src.uri,actual,modern)
                    assertTrue(displayed.orientationApplied);assertEquals(width,displayed.bitmap.width);assertEquals(height,displayed.bitmap.height)
                    assertColorClose("Adapter orientation $o modern=$modern",expected,displayed.bitmap.getPixel(5,5),2);displayed.bitmap.recycle()
                }
                assertEquals(1,bridge.inspectImage(output.path).orientation)
                b.recycle();sourceBitmap.recycle();output.delete()
            }
            return JSONObject().put("actualExifOrientations",8).put("nativeNormalizeOnce",true).put("legacyAdapter",true).put("modernAdapter",android.os.Build.VERSION.SDK_INT>=28)
        }
        if(id.startsWith("cancel_")){
            val phase=id.removePrefix("cancel_")
            if(phase=="prepare") {
                val entered=CompletableDeferred<Unit>()
                val wrapped=object:FfmpegBridge by bridge {
                    override suspend fun prepare(spec:ImageJobSpec,actual:ImageInfo,attempt:ImageAttempt,input:String,output:String):List<String>{entered.complete(Unit);awaitCancellation()}
                }
                val job=ImageJobSpec(UUID.randomUUID().toString(),d,info)
                coroutineScope {val worker=launch{ImageTranscoder(graph.files,wrapped,graph.imageMarkup).run(job,{}, {})};withTimeout(10000){entered.await()};worker.cancelAndJoin()}
                assertFalse(graph.files.output(QueueJobSpec.Image(job)).exists());assertFalse(File(context.filesDir,"work/${job.id}").exists())
                return JSONObject().put("expectedError","CANCELLED").put("typedPrepareCancelled",true).put("publication",false)
            }
            val (large,largeInfo)=ImageFixtures.png(context,File(dir,"cancel-native"),1536,1536,alpha=false,noise=true)
            val cancelDocument=d.copy(source=ImageFixtures.source(context,large,largeInfo),adjustments=if(phase=="encode")ImageAdjustments(blurSigma=20.0)else ImageAdjustments())
            val job=ImageJobSpec(UUID.randomUUID().toString(),cancelDocument,largeInfo)
            val entered=CompletableDeferred<Unit>();val before=nativeSessions().map{sessionId(it)}.toSet()
            val wrapped=object:FfmpegBridge by bridge {
                override suspend fun inspectImage(localPath:String):ImageInfo {
                    if(phase=="verify" && localPath.endsWith("candidate.png"))entered.complete(Unit)
                    return bridge.inspectImage(localPath)
                }
                override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult {
                    if((phase=="encode" && arguments.last().endsWith("candidate.png")) ||
                        (phase=="verify" && arguments.last()=="-" && arguments.any{it.endsWith("candidate.png")}))entered.complete(Unit)
                    return bridge.execute(arguments,onProgress)
                }
            }
            var nativeId=-1L
            coroutineScope {
                val worker=launch(Dispatchers.Default){ImageTranscoder(graph.files,wrapped,graph.imageMarkup).run(job,{}, {})}
                withTimeout(15000){entered.await()}
                withTimeout(5000){while(nativeId<0){
                    val running=nativeSessions().firstOrNull{sessionId(it) !in before && sessionState(it)=="RUNNING"}
                    if(running!=null)nativeId=sessionId(running)else {check(!worker.isCompleted){"Native $phase finished before a cancellation could be observed"};delay(1)}
                }}
                worker.cancelAndJoin()
            }
            assertFalse(nativeSessions().any{sessionId(it)==nativeId && sessionState(it)=="RUNNING"})
            assertFalse(graph.files.output(QueueJobSpec.Image(job)).exists());assertFalse(File(context.filesDir,"work/${job.id}").exists())
            return JSONObject().put("expectedError","CANCELLED").put("nativeSessionId",nativeId).put("nativeTeardownAwaited",true).put("publication",false)
        }
        if(id=="export_cap_exhausted"){
            val (large,largeInfo)=ImageFixtures.png(context,File(dir,"noise"),512,512,noise=true)
            val job=ImageJobSpec(UUID.randomUUID().toString(),d.copy(source=ImageFixtures.source(context,large,largeInfo),output=d.output.copy(targetBytes=32000)),largeInfo)
            expect("CANNOT_FIT"){transcoder.run(job,{}, {})};assertFalse(graph.files.output(QueueJobSpec.Image(job)).exists());return JSONObject().put("expectedError","CANNOT_FIT")
        }
        if(id=="export_cap_retries") {
            val (large,largeInfo)=ImageFixtures.png(context,File(dir,"fit-noise"),512,512,alpha=false,noise=true)
            val fixed=d.copy(source=ImageFixtures.source(context,large,largeInfo),output=d.output.copy(format=ImageFormat.JPEG))
            suspend fun measured(quality:Int):Long {
                val baseline=ImageJobSpec(UUID.randomUUID().toString(),fixed.copy(output=fixed.output.copy(jpegQuality=quality)),largeInfo)
                transcoder.run(baseline,{},{});val file=graph.files.output(QueueJobSpec.Image(baseline));val bytes=file.length();file.delete();return bytes
            }
            val first=measured(90);val second=measured(81);assertTrue("JPEG fixture needs distinct quality bytes",first>second)
            val cap=(first+second)/2;assertTrue(cap>=32000)
            val calls=mutableListOf<ImageAttempt>();val originalHashes=mutableListOf<String>()
            val recorder=object:FfmpegBridge by bridge {
                override suspend fun prepare(spec:ImageJobSpec,actual:ImageInfo,attempt:ImageAttempt,input:String,output:String):List<String> {
                    calls+=attempt;originalHashes+=ImageProbe.hash(File(input));return bridge.prepare(spec,actual,attempt,input,output)
                }
            }
            val fit=ImageJobSpec(UUID.randomUUID().toString(),fixed.copy(output=fixed.output.copy(targetBytes=cap)),largeInfo)
            ImageTranscoder(graph.files,recorder,graph.imageMarkup).run(fit,{},{});val final=graph.files.output(QueueJobSpec.Image(fit))
            assertTrue(calls.size in 2..7);assertTrue(final.length()<cap);assertTrue(originalHashes.all{it==largeInfo.hash})
            val evidence=JSONObject().put("cap",cap).put("bytes",final.length()).put("firstOversizedBytes",first).put("prepareCalls",calls.size).put("attempts",JSONArray(calls.map{JSONObject().put("index",it.index).put("quality",it.quality).put("scale",it.scale)})).put("originalReapplied",true)
            final.delete();return evidence
        }
        val job=ImageJobSpec(UUID.randomUUID().toString(),d,info);val tagged=QueueJobSpec.Image(job);val output=graph.files.output(tagged)
        if(id=="publication_collision"){output.writeText("keep");try{expect("OUTPUT_EXISTS"){transcoder.run(job,{}, {})};assertEquals("keep",output.readText())}finally{output.delete()};return JSONObject().put("expectedError","OUTPUT_EXISTS")}
        graph.queue.addImages(listOf(job));val states=mutableListOf<JobState>();val ready=CompletableDeferred<Unit>()
        val ticket=graph.runs.start {
            try{graph.queue.transition(job.id,JobState.PREPARING);transcoder.run(job,{state->states+=state;graph.queue.transition(job.id,state)},{});ready.complete(Unit)}catch(e:Throwable){ready.completeExceptionally(e);throw e}
        }?:error("Worker is already owned.")
        ready.await();ticket.job.join();assertEquals(JobState.COMPLETED,states.last());assertTrue(output.length()>0)
        val decoded=BitmapFactory.decodeFile(output.path)?:error("No decoded output")
        try {
            val expected=when(id){"geometry_crop_turn_resize"->ImageSize(200,300);"full_device_roundtrip"->ImageSize(91,71);else->ImageSize(101,77)}
            assertEquals(expected.width,decoded.width);assertEquals(expected.height,decoded.height)
            if(id in listOf("geometry_identity","geometry_odd_png","adjustments_neutral","alpha_geometry")){val before=BitmapFactory.decodeFile(input.path);for(y in 0 until before.height)for(x in 0 until before.width)assertEquals("Pixel $x,$y",before.getPixel(x,y),decoded.getPixel(x,y));before.recycle()}
            if(id=="adjustments_known_pixels"){val c=decoded.getPixel(5,5);assertTrue(kotlin.math.abs(Color.red(c)-54)<=1);assertEquals(Color.red(c),Color.green(c));assertEquals(Color.red(c),Color.blue(c))}
            if(id=="alpha_blur_edges") {
                assertTransparentRedEdges(decoded)
                val sharpen=ImageJobSpec(UUID.randomUUID().toString(),d.copy(adjustments=ImageAdjustments(sharpenAmount=1.0)),info)
                transcoder.run(sharpen,{},{});val sharpened=BitmapFactory.decodeFile(graph.files.output(QueueJobSpec.Image(sharpen)).path)
                assertTransparentRedEdges(sharpened,requireFractional=false);sharpened.recycle();graph.files.output(QueueJobSpec.Image(sharpen)).delete()
            }
            if(id=="markup_unicode") {
                val before=BitmapFactory.decodeFile(input.path);var changed=0
                for(y in 7..23)for(x in 10..69)if(before.getPixel(x,y)!=decoded.getPixel(x,y))changed++
                assertTrue("Unicode label must visibly rasterize",changed>15);before.recycle()
            }
            if(id=="jpeg_flatten") {
                val pixel=decoded.getPixel(5,5);assertTrue(Color.red(pixel)>=247);assertTrue(kotlin.math.abs(Color.green(pixel)-127)<=8);assertTrue(kotlin.math.abs(Color.blue(pixel)-127)<=8)
                val renderer=ImagePreviewRenderer(graph.files,bridge,graph.imageMarkup,File(dir,"flatten-preview"))
                for(actualPixels in listOf(false,true)){
                    val preview=renderer.render(d,ImagePreviewRequest(info,actualPixels,d.revision))
                    val b=BitmapFactory.decodeFile(preview.path);val c=b.getPixel(5,5)
                    assertEquals(255,Color.alpha(c));assertTrue(Color.red(c)>=254);assertTrue(kotlin.math.abs(Color.green(c)-127)<=1);assertTrue(kotlin.math.abs(Color.blue(c)-127)<=1)
                    b.recycle();File(preview.path).parentFile?.deleteRecursively()
                }
            }
            if(id=="alpha_geometry"){
                val (sparse,sparseInfo)=ImageFixtures.sparseAlpha(File(dir,"sparse-alpha"))
                val sparseSource=ImageFixtures.source(context,sparse,sparseInfo)
                val variants=listOf(ImageEditDocument(source=sparseSource,crop=NormalizedCrop(.5,.5,1.0,1.0),output=d.output),ImageEditDocument(source=sparseSource,annotations=listOf(ImageAnnotation("cover-all",AnnotationKind.REDACTION,left=0.0,top=0.0,right=1.0,bottom=1.0)),output=d.output))
                for((index,document) in variants.withIndex()){
                    val edited=ImageJobSpec(UUID.randomUUID().toString(),document,sparseInfo);transcoder.run(edited,{},{});val file=graph.files.output(QueueJobSpec.Image(edited))
                    val actual=bridge.inspectImage(file.path);assertEquals(ImageAlpha.OPAQUE,actual.alpha);assertEquals(255,actual.minimumAlpha)
                    assertEquals(if(index==0)1 else 2,actual.width);assertEquals(if(index==0)1 else 2,actual.height);file.delete()
                }
                assertEquals(sparseInfo.hash,ImageProbe.hash(sparse))
            }
            if(id=="solid_redaction" || id=="full_device_roundtrip")assertEquals(Color.BLACK,decoded.getPixel(70,50))
            if(id=="full_device_roundtrip") {
                assertTrue(output.length()<10_000_000)
                val saved=File(dir,"saved-copy.png");assertFalse(saved.exists());assertTrue(saved.createNewFile())
                val destination=androidx.core.content.FileProvider.getUriForFile(context,"${context.packageName}.files",saved)
                source.originalUri?.let{original->
                    val base=android.net.Uri.parse(original).buildUpon().path("").build()
                    for(name in listOf("original.png","alias.png","unreadable.png")) {
                        val guarded=base.buildUpon().appendPath(name).build()
                        try{graph.files.export(tagged,guarded);fail("Nonempty/unreadable original or alias must be guarded")}catch(_:IllegalArgumentException){}
                    }
                    assertEquals(0,context.contentResolver.call(base,"writes",null,null)!!.getInt("writes"))
                    val originalBytes=context.contentResolver.openInputStream(android.net.Uri.parse(original))!!.use{it.readBytes()}
                    assertArrayEquals(input.readBytes(),originalBytes)
                    val empty=base.buildUpon().appendPath("empty.png").build()
                    graph.files.export(tagged,empty)
                    assertEquals(1,context.contentResolver.call(base,"writes",null,null)!!.getInt("writes"))
                    assertArrayEquals(output.readBytes(),context.contentResolver.openInputStream(empty)!!.use{it.readBytes()})
                }
                graph.files.export(tagged,destination)
                assertEquals(ImageProbe.hash(output),ImageProbe.hash(saved));assertEquals("image/png",context.contentResolver.getType(destination))
                val copied=BitmapFactory.decodeFile(saved.path);assertEquals(91,copied.width);assertEquals(71,copied.height);copied.recycle()
            }
            ImageMetadata.requireClean(output,d.output.format)
            assertEquals(source.hash,ImageProbe.hash(input))
            return JSONObject().put("sourceHash",source.hash).put("documentHash",documentHash(d)).put("format",d.output.format.name).put("width",decoded.width).put("height",decoded.height).put("bytes",output.length()).put("outputHash",ImageProbe.hash(output)).put("fullyDecoded",true).put("sourceUnchanged",true).put("nativeBuild",caps.build)
        }finally{decoded.recycle()}
    }
    private fun documentHash(document:ImageEditDocument)=java.security.MessageDigest.getInstance("SHA-256").digest(ImageDocumentCodec.encode(document).toString().toByteArray()).joinToString(""){"%02x".format(it)}
    private fun nativeSessions():List<Any> = (Class.forName("com.arthenica.ffmpegkit.FFmpegKit").getMethod("listSessions").invoke(null) as List<*>).filterNotNull()
    private fun sessionId(session:Any)=(session.javaClass.getMethod("getSessionId").invoke(session) as Number).toLong()
    private fun sessionState(session:Any)=session.javaClass.getMethod("getState").invoke(session).toString()
    private fun assertColorClose(label:String,expected:Int,actual:Int,tolerance:Int) {
        for(channel in listOf<(Int)->Int>(Color::red,Color::green,Color::blue,Color::alpha))assertTrue(label+" expected=$expected actual=$actual",kotlin.math.abs(channel(expected)-channel(actual))<=tolerance)
    }
    private fun assertTransparentRedEdges(bitmap:Bitmap,requireFractional:Boolean=true) {
        var fractional=0
        for(y in 0 until bitmap.height)for(x in 0 until bitmap.width){val c=bitmap.getPixel(x,y);val a=Color.alpha(c)
            if(a in 8..247){fractional++;assertTrue("Red fringe $x,$y alpha=$a red=${Color.red(c)}",Color.red(c)>=240);assertTrue(Color.green(c)<=3);assertTrue(Color.blue(c)<=3)}
            if(a==0)assertEquals("Hidden RGB cleared",0,c)
        }
        if(requireFractional)assertTrue("Blur must produce fractional transparent edges",fractional>40)
    }
    private suspend fun expect(code:String,block:suspend()->Unit){try{block();fail("Expected $code")}catch(e:ImageFailure){assertEquals(code,e.code)}}
}
