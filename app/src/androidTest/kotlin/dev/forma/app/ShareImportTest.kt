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
