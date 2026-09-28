package dev.forma.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.forma.app.ui.FormaScreen
import dev.forma.app.ui.FormaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { FormaTheme { FormaRoute() } }
    }
}
private data class ExportRequest(val name: String, val mime: String)
private class CreateOutput : ActivityResultContract<ExportRequest, Uri?>() {
    override fun createIntent(context: Context, input: ExportRequest) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}
@Composable private fun FormaRoute(vm: TranscodeViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var exportId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingStart by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importSources(it) }
    val save = rememberLauncherForActivityResult(CreateOutput()) { uri ->
        val id = exportId
        exportId = null
        if (uri != null && id != null) vm.export(id, uri)
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Permission denial does not forbid a user-initiated foreground service.
        val pending = pendingStart
        pendingStart = null
        if (pending == "convert") vm.act(UiAction.Convert) else if (pending == "queue") vm.act(UiAction.StartQueue)
    }
    FormaScreen(ui, jobs, progress) { action ->
        when (action) {
            UiAction.Import -> picker.launch(arrayOf("video/*", "audio/*"))
            is UiAction.Export -> jobs.firstOrNull { it.spec.id == action.id }?.let {
                exportId = action.id
                save.launch(ExportRequest(vm.graph.files.exportName(it.spec), it.spec.settings.container.mime))
            }
            UiAction.Convert, UiAction.StartQueue -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    pendingStart = if (action == UiAction.Convert) "convert" else "queue"
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else vm.act(action)
            }
            else -> vm.act(action)
        }
    }
}
