package dev.forma.app

import android.content.ClipData
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.os.Build
import android.Manifest
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.forma.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.util.UUID

class ShareImportTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixturePackage get() = InstrumentationRegistry.getInstrumentation().context.packageName
    private val one get() = Uri.parse("content://$fixturePackage.shared-media/tone-one.wav")
    private val two get() = Uri.parse("content://$fixturePackage.shared-media/tone-two.wav")
    private fun sender() = Intent().setComponent(ComponentName(fixturePackage, "dev.forma.app.SharedMediaSenderActivity"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    @Before fun restoreSenderAccess() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkPermission("$fixturePackage.READ_MEDIA", Process.myPid(), Process.myUid()))
        val token = UUID.randomUUID().toString()
        context.startActivity(sender().putExtra("grantOnly", true).putExtra("setupToken", token))
        compose.waitUntil(10_000) { runCatching { context.contentResolver.call(one, "state", null, null)?.getString("setupToken") }.getOrNull() == token }
    }

    @Test fun restoredActivityWithFreshViewModelRetriesSharedIntent() {
        ActivityScenario.launch<MainActivity>(share().putExtra(Intent.EXTRA_STREAM, one)).use { scenario ->
            waitSources(scenario, 1)
            var previousUri = ""
            withVm(scenario) { previousUri = it.state.value.sources.single().source.uri }
            // Keep Android's activity saved state while discarding retained VM state, as process death does.
            scenario.onActivity { it.viewModelStore.clear() }
            scenario.recreate()
            waitSources(scenario, 1)
            withVm(scenario) { assertNotEquals(previousUri, it.state.value.sources.single().source.uri) }
        }
    }
    private fun share(action: String = Intent.ACTION_SEND) = Intent(context, MainActivity::class.java)
        .setAction(action).setType("audio/wav").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    private fun withVm(scenario: ActivityScenario<MainActivity>, action: (TranscodeViewModel) -> Unit) {
        scenario.onActivity { action(ViewModelProvider(it)[TranscodeViewModel::class.java]) }
    }
    private fun waitSources(scenario: ActivityScenario<MainActivity>, count: Int) = compose.waitUntil(10_000) {
        var ready = false
        withVm(scenario) { ready = it.state.value.sources.size == count && it.state.value.fileTask == null && !it.state.value.validating }
        ready
    }
    private fun externalShare(scenario: ActivityScenario<MainActivity>, request: Intent, count: Int, check: (TranscodeViewModel) -> Unit) {
        lateinit var activity: MainActivity
        lateinit var vm: TranscodeViewModel
        lateinit var originalIntent: Intent
        scenario.onActivity { activity = it; vm = ViewModelProvider(it)[TranscodeViewModel::class.java]; originalIntent = it.intent }
        try {
            context.startActivity(sender().putExtra("shared", Intent(request).apply { flags = 0; clipData = null }))
            try {
                compose.waitUntil(10_000) { activity.intent !== originalIntent && activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    vm.state.value.sources.size == count && vm.state.value.fileTask == null && !vm.state.value.validating }
            } catch (error: Throwable) {
                throw AssertionError("Expected $count prepared sources; actual=${vm.state.value.sources.size}, message=${vm.state.value.message}", error)
            }
            check(vm)
        } finally {
            // ActivityScenario matches its initial intent. The real app retains the latest share intent.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { activity.intent = originalIntent; activity.finish() }
        }
    }

    @Test fun appearsAsShareTargetForSingleAndMultipleVideoAndAudio() {
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (mime in listOf("video/mp4", "audio/wav")) {
                val request = Intent(action).setType(mime).setPackage(context.packageName).addCategory(Intent.CATEGORY_DEFAULT)
                assertNotNull("Forma must receive $action $mime", context.packageManager.resolveActivity(request, PackageManager.MATCH_DEFAULT_ONLY))
            }
        }
    }

    @Test fun coldSharePreparesPrivateAudioCopyWithoutStartingConversion() {
        ActivityScenario.launch<MainActivity>(share().putExtra(Intent.EXTRA_STREAM, one)).use { scenario ->
            waitSources(scenario, 1)
            compose.onNodeWithText("tone-one.wav").assertExists()
            if (InstrumentationRegistry.getArguments().getString("formaNative") == "true") {
                compose.onNodeWithTag("convert").assertIsEnabled()
            }
            withVm(scenario) { vm ->
                val source = vm.state.value.sources.single().source
                assertEquals("${context.packageName}.files", Uri.parse(source.uri).authority)
                assertEquals(32044L, source.bytes)
                assertEquals(1000L, source.durationMs)
                assertEquals(Container.M4A, vm.state.value.editor.settings.container)
                assertTrue(vm.jobs.value.none { it.spec.source.uri == source.uri })
            }
            scenario.recreate()
            waitSources(scenario, 1) // rotation must not import the original intent twice
        }
    }

    @Test fun warmMultipleShareKeepsSettingsAndDeduplicatesStreams() {
        ActivityScenario.launch<MainActivity>(share().apply { clipData = ClipData.newUri(context.contentResolver, "Shared audio", one) }).use { scenario ->
            waitSources(scenario, 1)
            val original = Settings(container = Container.M4A, audioKbps = 96)
            withVm(scenario) { it.act(UiAction.ChangeSettings(original)); it.act(UiAction.ToggleAdvanced) }
            compose.onNodeWithTag("open-shelf").performClick()
            compose.onNode(hasText("Queue ·", substring = true)).performClick()
            compose.onNodeWithText("Your queue").assertExists()
            val request = share(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two, two))
            assertTrue(runCatching { context.contentResolver.openInputStream(two)?.close() }.isFailure)
            externalShare(scenario, request, 3) { vm ->
                compose.onNodeWithTag("convert").assertExists() // a warm share leaves the queue screen
                assertEquals(original, vm.state.value.editor.settings)
                assertTrue(vm.state.value.editor.advanced)
                assertEquals(1, vm.state.value.sources.count { it.source.name == "tone-two.wav" })
                context.startActivity(sender().putExtra("revokeOnly", true))
                compose.waitUntil(10_000) { runCatching { context.contentResolver.openInputStream(two)?.close() }.isFailure }
                val copied = vm.state.value.sources.single { it.source.name == "tone-two.wav" }.source
                assertEquals(32044L, context.contentResolver.openInputStream(Uri.parse(copied.uri))!!.use { it.readBytes().size.toLong() })
            }
        }
    }

    @Test fun explicitlySelectedPresetSurvivesAudioShareIntoEmptyEditor() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            val chosen = Planner.preset(Goal.DETAIL, Quality.CLEAR)
            withVm(scenario) { it.act(UiAction.Preset(Goal.DETAIL, Quality.CLEAR)) }
            externalShare(scenario, share().putExtra(Intent.EXTRA_STREAM, one), 1) { assertEquals(chosen, it.state.value.editor.settings) }
        }
    }

    @Test fun warmNotificationIntentOpensExistingQueue() {
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            externalShare(scenario, Intent(context, MainActivity::class.java).putExtra("open_queue", true), 0) {
                compose.waitUntil(10_000) { compose.onAllNodesWithText("Your queue").fetchSemanticsNodes().isNotEmpty() }
            }
        }
    }

    @Test fun missingSharedStreamShowsActionableMessageWithoutImporting() {
        ActivityScenario.launch<MainActivity>(share()).use { scenario ->
            compose.onNodeWithText("No media file was included. Share a video or audio file from Gallery or Files.").assertExists()
            withVm(scenario) { assertTrue(it.state.value.sources.isEmpty()) }
        }
    }

    @Test fun queuedShareRemainsReadableAfterSenderAccessEndsAndQueueReloads() {
        ActivityScenario.launch<MainActivity>(share().putExtra(Intent.EXTRA_STREAM, one)).use { scenario ->
            waitSources(scenario, 1)
            var graph: AppGraph? = null
            var sourceUri = ""
            withVm(scenario) { graph = it.graph; sourceUri = it.state.value.sources.single().source.uri; it.act(UiAction.Queue) }
            compose.waitUntil(10_000) { requireNotNull(graph).queue.entries.value.any { it.spec.source.uri == sourceUri } }
            val job = requireNotNull(graph).queue.entries.value.single { it.spec.source.uri == sourceUri }
            try {
                context.contentResolver.call(one, "block", null, null)
                assertTrue(runCatching { context.contentResolver.openInputStream(one)?.use { it.read() } }.isFailure)
                runBlocking {
                    val restarted = AppGraph(context.applicationContext as FormaApplication).apply { initialize() }
                    val restored = restarted.queue.entries.value.single { it.spec.id == job.spec.id }
                    val staged = requireNotNull(graph).files.stage(restored.spec)
                    assertEquals(32044L, staged.length())
                    assertEquals("RIFF", staged.inputStream().use { input -> String(ByteArray(4).also { assertEquals(4, input.read(it)) }) })
                    staged.parentFile?.deleteRecursively()
                }
            } finally { runBlocking { requireNotNull(graph).queue.removeQueued(job.spec.id) } }
        }
    }

    @Test fun convertTapStartsForegroundServiceAndPublishesVerifiedAudio() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("formaNative") == "true")
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch<MainActivity>(share().putExtra(Intent.EXTRA_STREAM, one)).use { scenario ->
            waitSources(scenario, 1)
            lateinit var vm: TranscodeViewModel
            var sourceUri = ""
            withVm(scenario) { vm = it; sourceUri = it.state.value.sources.single().source.uri }
            compose.onNodeWithTag("convert").assertIsEnabled().performClick()
            try {
                compose.waitUntil(60_000) { vm.jobs.value.any { it.spec.source.uri == sourceUri && it.state == JobState.COMPLETED } }
            } catch (error: Throwable) {
                throw AssertionError("Convert did not complete: ${vm.state.value.message}; jobs=${vm.jobs.value}", error)
            }
            val job = vm.jobs.value.single { it.spec.source.uri == sourceUri }
            runBlocking {
                val output = vm.graph.files.output(job.spec)
                assertTrue(output.length() > 0)
                val probe = vm.graph.bridge.probe(output.absolutePath)
                assertEquals(0, probe.videoTracks)
                assertEquals(1, probe.audioTracks)
                assertTrue(probe.durationMs in 900L..1100L)
                val decoded = vm.graph.bridge.execute(listOf("-hide_banner", "-nostdin", "-v", "error", "-xerror", "-i", output.absolutePath, "-f", "null", "-")) {}
                assertEquals(decoded.diagnostics, 0, decoded.exitCode)
            }
        }
    }
}
