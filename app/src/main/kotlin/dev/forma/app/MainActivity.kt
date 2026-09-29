package dev.forma.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.forma.app.ui.FormaWorkspace
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.ProgressView
import dev.forma.core.QueueEntry

class MainActivity : ComponentActivity() {
    private val vm: TranscodeViewModel by viewModels()
    private var workspaceRequest by mutableStateOf<WorkspaceRequest?>(null)
    private fun navigate(showQueue: Boolean) { workspaceRequest = WorkspaceRequest((workspaceRequest?.id ?: 0) + 1, showQueue) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val queue = intent.getBooleanExtra("open_queue", false)
        // The retained VM skips rotation; a new VM after process death retries the intent.
        if (!vm.receivedInitialIntent) { vm.receivedInitialIntent = true; receiveMedia(intent) }
        setContent {
            val settings by (application as FormaApplication).graph.settings.state.collectAsStateWithLifecycle()
            FormaTheme(settings.document?.values ?: dev.forma.core.settings.PreferenceValues.EMPTY) {
                FormaRoute(vm, initiallyQueue = queue, workspaceRequest = workspaceRequest)
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_queue", false)) navigate(showQueue = true) else receiveMedia(intent)
    }
    private fun receiveMedia(intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        navigate(showQueue = false)
        try {
            val streams = when (intent.action) {
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::listOf)
                else -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            } ?: intent.clipData?.let { clip ->
                require(clip.itemCount <= 200) { "Share at most 200 files at a time." }
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            }.orEmpty()
            require(streams.isNotEmpty()) { "No media file was included. Share a video or audio file from Gallery or Files." }
            require(streams.all { it.scheme == "content" }) { "The sender must share readable media files." }
            vm.importSharedSources(streams.distinct())
        } catch (error: Exception) {
            vm.showImportError(error.message ?: "The shared media could not be read.")

        }
    }
}
private data class ExportRequest(val name: String, val mime: String)
private class CreateOutput : ActivityResultContract<ExportRequest, Uri?>() {
    override fun createIntent(context: Context, input: ExportRequest) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
@Composable private fun FormaRoute(vm: TranscodeViewModel, initiallyQueue: Boolean = false, workspaceRequest: WorkspaceRequest? = null) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val run by vm.runState.collectAsStateWithLifecycle()
    // DO NOT collect native progress here: it would invalidate the whole editor each tick.
    val progressContent: @Composable (QueueEntry) -> Unit = remember(vm) { { entry -> LiveJobProgress(vm, entry) } }
    val context = LocalContext.current
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStart by rememberSaveable { mutableStateOf<String?>(null) }
    var askedNotifications by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importSources(it) }
    val save = rememberLauncherForActivityResult(CreateOutput()) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) vm.export(id, uri)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // A denied notification permission does not forbid user-started foreground processing.
        val pending = pendingStart
        pendingStart = null
        if (pending == "convert") vm.act(UiAction.Convert) else if (pending == "queue") vm.act(UiAction.StartQueue)
    }
    FormaWorkspace(ui, jobs, run, initiallyQueue, workspaceRequest = workspaceRequest, progressContent = progressContent, onAction = { action ->
        when (action) {
            UiAction.Import -> if (ui.fileTask == null) picker.launch(arrayOf("video/*", "audio/*"))
            is UiAction.Export -> if (ui.fileTask == null && exportId == null) jobs.firstOrNull { it.spec.id == action.id }?.let {
                exportId = action.id
                save.launch(ExportRequest(vm.graph.files.exportName(it.spec), it.spec.settings.container.mime))
            }
            UiAction.Convert, UiAction.StartQueue -> {
                val needsPrompt = Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (pendingStart == null) {
                    if (needsPrompt) {
                        askedNotifications = true
                        pendingStart = if (action == UiAction.Convert) "convert" else "queue"
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else vm.act(action)
                }
            }
            else -> vm.act(action)
        }
    })
}
@Composable private fun LiveJobProgress(vm: TranscodeViewModel, entry: QueueEntry) {
    val progress by vm.progress.collectAsStateWithLifecycle()
    ProgressView(entry, progress)
}
