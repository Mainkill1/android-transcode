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
    private val androidCases=listOf("source_uri_revoked","preview_stale","draft_corrupt")
    @Test fun selectedCases()=runBlocking {
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val args=InstrumentationRegistry.getArguments();val requested=args.getString("formaImageCase")?:error("formaImageCase is required; choose a named case or all.")
        val runId=ImageReport.validateRunId(args.getString("formaImageRunId")?:error("formaImageRunId is required."))
        val selected=if(requested=="all")nativeCases+androidCases else listOf(requested)
        require(selected.isNotEmpty() && selected.all{it in nativeCases+androidCases+"native_missing"}){"Unknown or unsupported image case."}
        val root=File(context.filesDir,"image-tests/$runId");ImageReportContract.reserve(root)
        val graph=(context.applicationContext as FormaApplication).graph;graph.initialize();val caps=graph.bridge.capabilities()
        val started=SystemClock.elapsedRealtime();val cases=JSONArray();var passed=0
        for(id in selected){val row=JSONObject().put("id",id).put("startedMs",SystemClock.elapsedRealtime())
            try {if(id!="native_missing" && id in nativeCases)assertTrue("Native image suite requires the native build: ${caps.reason}",caps.available)
                val evidence=runCase(context,id,runId,caps,graph.bridge)
                val rejection=evidence.has("expectedError")
                row.put("expectedOutcome",if(rejection)"reject" else "success").put("evidence",evidence).put("observedOutcome",if(rejection)"reject" else "success").put("assertionsPassed",true);passed++
            }catch(e:Throwable){row.put("observedOutcome","failure").put("assertionsPassed",false).put("error",e.toString())}
            row.put("finishedMs",SystemClock.elapsedRealtime());cases.put(row)
        }
        val report=ImageReport.artifacts(context).put("schema",1).put("runId",runId).put("requestedSelection",requested).put("selectedCases",JSONArray(selected)).put("selectedCount",selected.size).put("executedCount",cases.length()).put("passedCount",passed).put("status",if(passed==selected.size)"PASS" else "FAIL").put("startedMs",started).put("finishedMs",SystemClock.elapsedRealtime()).put("declaredRevision",args.getString("formaDeclaredRevision")?:JSONObject.NULL).put("nativeBuild",caps.build).put("cases",cases)
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
        val dir=File(context.filesDir,"imports/image-$runId-$id")
        val (input,info)=ImageFixtures.png(context,dir,if(id=="geometry_crop_turn_resize")1200 else 101,if(id=="geometry_crop_turn_resize")800 else 77,alpha=id !in listOf("jpeg_flatten","adjustments_known_pixels"))
        val source=ImageFixtures.source(context,input,info)
        var d=ImageEditDocument(source=source,output=ImageOutputPolicy(format=ImageFormat.PNG,targetBytes=null))
        if(id=="native_missing"){assertFalse(caps.available);expect("CAPABILITY_UNAVAILABLE"){graph.imageTranscoder.run(ImageJobSpec(UUID.randomUUID().toString(),d,info),{}, {})};return JSONObject().put("expectedError","CAPABILITY_UNAVAILABLE")}
        if(id=="geometry_crop_turn_resize")d=d.copy(crop=NormalizedCrop(.25,.25,.75,.75),quarterTurns=1,output=d.output.copy(resizeMode=ResizeMode.LONGEST_EDGE,longestEdge=300))
        if(id=="jpeg_alpha_block" || id=="jpeg_flatten")d=d.copy(output=d.output.copy(format=ImageFormat.JPEG,flatten=if(id=="jpeg_flatten")Rgba(255,255,255)else null))
        if(id=="alpha_blur_edges")d=d.copy(adjustments=ImageAdjustments(blurSigma=2.0))
        if(id=="adjustments_known_pixels")d=d.copy(adjustments=ImageAdjustments(saturation=0.0))
        if(id=="markup_unicode")d=d.copy(annotations=listOf(ImageAnnotation("unicode",AnnotationKind.TEXT,text="Hello\nمرحبا é \"quoted\"",textSize=10.0,color=Rgba(0,0,0))))
        if(id=="solid_redaction" || id=="full_device_roundtrip")d=d.copy(annotations=listOf(ImageAnnotation("secret",AnnotationKind.REDACTION,left=.5,top=.5,right=.9,bottom=.9,color=Rgba(0,0,0))))
        if(id=="jpeg_alpha_block"){expect("ALPHA_WOULD_BE_LOST"){graph.imageTranscoder.run(ImageJobSpec(UUID.randomUUID().toString(),d,info),{}, {})};return JSONObject().put("expectedError","ALPHA_WOULD_BE_LOST")}
        if(id=="source_uri_revoked"){expect("SOURCE_ACCESS"){ImageInputAdapter(context).stage("content://revoked-provider/missing",UUID.randomUUID().toString())};return JSONObject().put("expectedError","SOURCE_ACCESS")}
        if(id=="source_corrupt"){input.writeBytes(input.readBytes().copyOf(30));expect("DECODE_FAILED"){ImageInputAdapter(context).stage(source.uri,UUID.randomUUID().toString())};return JSONObject().put("expectedError","DECODE_FAILED")}
        if(id=="source_animation"){input.writeText("GIF89a");expect("UNSUPPORTED_IMAGE"){ImageInputAdapter(context).stage(source.uri,UUID.randomUUID().toString())};return JSONObject().put("expectedError","UNSUPPORTED_IMAGE")}
        if(id=="source_unsupported_color"){expect("UNSUPPORTED_IMAGE"){ImageValidation.requireSupported(info.copy(bitDepth=16))};return JSONObject().put("expectedError","UNSUPPORTED_IMAGE")}
        if(id=="draft_corrupt"){val directory=File(dir,"drafts").apply{mkdirs()};val f=File(directory,"${source.hash}.json").apply{writeText("broken")};val repo=ImageDraftRepository(directory);assertTrue(repo.load(source.hash) is ImageDraftLoadResult.Corrupt);assertTrue(repo.save(d,0) is ImageDraftSaveResult.Preserved);assertEquals("broken",f.readText());return JSONObject().put("expectedError","DRAFT_CORRUPT")}
        if(id=="preview_stale"){val p=ImagePreviewState(2,"current","Updating");assertEquals(p,p.accept(1,"old","stale.png",false));return JSONObject().put("staleRevisionRejected",true)}
        if(id=="export_format_mismatch"){expect("OUTPUT_INVALID"){ImageVerifier.requireFacts(info.copy(width=100),ImageFormat.PNG,ImageSize(101,77),ImageAlpha.PRESENT)};return JSONObject().put("expectedError","OUTPUT_INVALID")}
        if(id=="orientation_all_eight"){
            val sourceBitmap=BitmapFactory.decodeFile(input.path)
            for(o in 1..8){
                val oriented=info.copy(orientation=o);val job=ImageJobSpec(UUID.randomUUID().toString(),d,oriented)
                // Preserve EXIF at the source boundary, then let typed native preparation normalize it exactly once.
                val output=File(dir,"orientation-$o.png")
                val args=bridge.prepare(job,oriented,ImageAttempt(0,ImageFormat.PNG,90),input.path,output.path)
                assertEquals(0,bridge.execute(args){}.exitCode)
                val b=BitmapFactory.decodeFile(output.path)
                val expected=when(o){1->sourceBitmap.getPixel(5,5);2->sourceBitmap.getPixel(sourceBitmap.width-6,5);3->sourceBitmap.getPixel(sourceBitmap.width-6,sourceBitmap.height-6);4->sourceBitmap.getPixel(5,sourceBitmap.height-6);5->sourceBitmap.getPixel(5,5);6->sourceBitmap.getPixel(5,sourceBitmap.height-6);7->sourceBitmap.getPixel(sourceBitmap.width-6,sourceBitmap.height-6);else->sourceBitmap.getPixel(sourceBitmap.width-6,5)}
                assertEquals("Orientation $o",expected,b.getPixel(5,5));b.recycle();output.delete()
            };sourceBitmap.recycle();return JSONObject().put("orientations",8)
        }
        if(id.startsWith("cancel_")){
            val phase=id.removePrefix("cancel_");val wrapped=object:FfmpegBridge by bridge {
                override suspend fun prepare(spec:ImageJobSpec,actual:ImageInfo,attempt:ImageAttempt,input:String,output:String):List<String>{if(phase=="prepare")throw CancellationException("cancel prepare");return bridge.prepare(spec,actual,attempt,input,output)}
                override suspend fun execute(arguments:List<String>,onProgress:(Progress)->Unit):NativeResult{if((phase=="encode" && arguments.contains("-frames:v")) || (phase=="verify" && arguments.last()=="-" && arguments.any{it.endsWith("candidate.png")}))throw CancellationException("cancel $phase");return bridge.execute(arguments,onProgress)}
            }
            val job=ImageJobSpec(UUID.randomUUID().toString(),d,info);try{ImageTranscoder(graph.files,wrapped,graph.imageMarkup).run(job,{},{});fail("Expected cancellation")}catch(_:CancellationException){}
            assertFalse(graph.files.output(QueueJobSpec.Image(job)).exists());assertFalse(graph.files.workDir(job).listFiles().orEmpty().isNotEmpty());graph.files.workDir(job).deleteRecursively();return JSONObject().put("expectedError","CANCELLED").put("publication",false)
        }
        if(id=="export_cap_exhausted"){
            val (large,largeInfo)=ImageFixtures.png(context,File(dir,"noise"),512,512,noise=true)
            val job=ImageJobSpec(UUID.randomUUID().toString(),d.copy(source=ImageFixtures.source(context,large,largeInfo),output=d.output.copy(targetBytes=32000)),largeInfo)
            expect("CANNOT_FIT"){graph.imageTranscoder.run(job,{}, {})};assertFalse(graph.files.output(QueueJobSpec.Image(job)).exists());return JSONObject().put("expectedError","CANNOT_FIT")
        }
        if(id=="export_cap_retries") {
            val (large,largeInfo)=ImageFixtures.png(context,File(dir,"fit-noise"),512,512,alpha=false,noise=true)
            val fixed=d.copy(source=ImageFixtures.source(context,large,largeInfo),output=d.output.copy(format=ImageFormat.JPEG))
            suspend fun measured(quality:Int):Long {
                val baseline=ImageJobSpec(UUID.randomUUID().toString(),fixed.copy(output=fixed.output.copy(jpegQuality=quality)),largeInfo)
                graph.imageTranscoder.run(baseline,{},{});val file=graph.files.output(QueueJobSpec.Image(baseline));val bytes=file.length();file.delete();return bytes
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
        if(id=="publication_collision"){output.writeText("keep");try{expect("OUTPUT_EXISTS"){graph.imageTranscoder.run(job,{}, {})};assertEquals("keep",output.readText())}finally{output.delete()};return JSONObject().put("expectedError","OUTPUT_EXISTS")}
        graph.queue.addImages(listOf(job));val states=mutableListOf<JobState>();val ready=CompletableDeferred<Unit>()
        val ticket=graph.runs.start {
            try{graph.queue.transition(job.id,JobState.PREPARING);graph.imageTranscoder.run(job,{state->states+=state;graph.queue.transition(job.id,state)},{});ready.complete(Unit)}catch(e:Throwable){ready.completeExceptionally(e);throw e}
        }?:error("Worker is already owned.")
        ready.await();ticket.job.join();assertEquals(JobState.COMPLETED,states.last());assertTrue(output.length()>0)
        val decoded=BitmapFactory.decodeFile(output.path)?:error("No decoded output")
        try {
            val expected=if(id=="geometry_crop_turn_resize")ImageSize(200,300)else ImageSize(101,77)
            assertEquals(expected.width,decoded.width);assertEquals(expected.height,decoded.height)
            if(id in listOf("geometry_identity","geometry_odd_png","adjustments_neutral","alpha_geometry")){val before=BitmapFactory.decodeFile(input.path);for(y in 0 until before.height)for(x in 0 until before.width)assertEquals("Pixel $x,$y",before.getPixel(x,y),decoded.getPixel(x,y));before.recycle()}
            if(id=="adjustments_known_pixels"){val c=decoded.getPixel(5,5);assertTrue(kotlin.math.abs(Color.red(c)-54)<=1);assertEquals(Color.red(c),Color.green(c));assertEquals(Color.red(c),Color.blue(c))}
            if(id=="solid_redaction" || id=="full_device_roundtrip")assertEquals(Color.BLACK,decoded.getPixel(70,50))
            ImageMetadata.requireClean(output,d.output.format)
            assertEquals(source.hash,ImageProbe.hash(input))
            return JSONObject().put("sourceHash",source.hash).put("documentHash",d.hashCode().toString()).put("format",d.output.format.name).put("width",decoded.width).put("height",decoded.height).put("bytes",output.length()).put("outputHash",ImageProbe.hash(output)).put("fullyDecoded",true).put("sourceUnchanged",true).put("nativeBuild",caps.build)
        }finally{decoded.recycle()}
    }
    private suspend fun expect(code:String,block:suspend()->Unit){try{block();fail("Expected $code")}catch(e:ImageFailure){assertEquals(code,e.code)}}
}
