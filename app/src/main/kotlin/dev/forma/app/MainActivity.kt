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
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.forma.app.ui.FormaWorkspace
import dev.forma.app.ui.FormaTheme
import dev.forma.app.ui.ProgressView
import dev.forma.core.QueueEntry

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val queue = intent.getBooleanExtra("open_queue", false)
        setContent {
            val settings by (application as FormaApplication).graph.settings.state.collectAsStateWithLifecycle()
            FormaTheme(settings.document?.values ?: dev.forma.core.settings.PreferenceValues.EMPTY) {
                FormaRoute(initiallyQueue = queue)
            }
        }
    }
}
private data class ExportRequest(val name: String, val mime: String)
private class CreateOutput : ActivityResultContract<ExportRequest, Uri?>() {
    override fun createIntent(context: Context, input: ExportRequest) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
@Composable private fun FormaRoute(vm: TranscodeViewModel = viewModel(), initiallyQueue: Boolean = false) {
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
    FormaWorkspace(ui, jobs, run, initiallyQueue, progressContent = progressContent, onAction = { action ->
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
