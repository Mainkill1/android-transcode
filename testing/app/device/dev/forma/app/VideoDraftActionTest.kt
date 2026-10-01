package dev.forma.app

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.forma.app.video.VideoDraftLoad
import dev.forma.core.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class VideoDraftActionTest {
    @Test fun draftWritesDuringUnfinishedCropKeepLastCommittedEdit() = runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FormaApplication
        val fixture=File(app.filesDir,"imports/unfinished-crop-${UUID.randomUUID()}.mp4")
        val vm=TranscodeViewModel(app)
        var importedUri:String?=null
        try {
            withTimeoutOrNull(10_000) {vm.state.first {it.ready}}
                ?: error("ViewModel was not ready: ${vm.state.value.message}")
            assumeTrue("Native preview fixture requires FFmpeg", app.graph.bridge.capabilities().available)
            fixture.parentFile!!.mkdirs()
            val generated=app.graph.bridge.execute(listOf("-hide_banner","-v","error","-nostdin","-n",
                "-f","lavfi","-i","color=red:s=160x90:r=24:d=2","-c:v","libx264",
                "-pix_fmt","yuv420p",fixture.path)) {}
            assertEquals(generated.diagnostics,0,generated.exitCode)
            val uri=FileProvider.getUriForFile(app,"${app.packageName}.files",fixture)
            vm.importSharedSources(listOf(uri))
            val selected=(withTimeoutOrNull(10_000) {vm.state.first {ui -> ui.sources.any {it.source.name==fixture.name}}}
                ?: error("Import did not select source: ${vm.state.value.message}; task=${vm.state.value.fileTask}"))
                .sources.first {it.source.name==fixture.name}
            importedUri=selected.source.uri
            vm.act(UiAction.Select(selected.source.uri))
            val provisional=selected.copy(effects=selected.effects.copy(crop=CropRect(0,0,80,80)))
            vm.act(UiAction.ChangeVideoEdit(provisional,commit=false))
            assertEquals(provisional,vm.state.value.selected)
            vm.act(UiAction.SaveVideoDraft)
            val completed=withTimeoutOrNull(10_000) {vm.state.first {it.videoDraftExitResult=="saved"}}
                ?: error("Draft did not save: ${vm.state.value.message}; result=${vm.state.value.videoDraftExitResult}")
            assertFalse(completed.videoDraftDirty)
            assertEquals(selected,completed.selected)
            val disk=app.graph.videoDrafts.load(selected.source.uri) as VideoDraftLoad.Valid
            assertEquals(selected,disk.draft.edit)
            vm.act(UiAction.ChangeVideoEdit(provisional,commit=false))
            vm.act(UiAction.VideoTool("Rotate"))
            val toolDraft=withTimeout(10_000) {
                var latest:VideoDraftLoad.Valid
                while(true) {
                    latest=app.graph.videoDrafts.load(selected.source.uri) as VideoDraftLoad.Valid
                    if(latest.draft.tool=="Rotate")break
                    delay(20)
                }
                latest
            }
            assertEquals(selected,toolDraft.draft.edit)
            assertEquals(selected,vm.state.value.selected)
        } finally {
            importedUri?.let {app.graph.videoDrafts.discard(it)}
            fixture.delete()
        }
    }
}
