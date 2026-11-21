package aismo.sms.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import aismo.sms.AppContainer
import aismo.sms.container
import aismo.sms.data.llm.ModelCatalog
import aismo.sms.data.llm.ModelImport
import aismo.sms.data.llm.SupportedModel
import aismo.sms.data.settings.AppSettings
import aismo.sms.domain.llm.LlmBackend

/** Choosing and importing the writing assistant's model file; survives the screen being rebuilt (for example when a foldable opens). */
class WritingAssistantViewModel(private val c: AppContainer) : ViewModel() {
    /** Bytes copied so far while a file is being checked and installed, or null when idle. */
    val importing = MutableStateFlow<Long?>(null)
    /** The expected size of the file being imported, for the progress bar; 0 until it is known. */
    val importingTotal = MutableStateFlow(0L)
    val message = MutableStateFlow<String?>(null)
    /** Changes whenever a model is installed or removed, so the screen re-reads what is installed. */
    val installedVersion = MutableStateFlow(0)
    /** Which model the user is currently fetching; null until they have picked one. */
    val wantedModel = MutableStateFlow<SupportedModel?>(null)
    private var job: Job? = null

    fun chooseModel(model: SupportedModel) {
        wantedModel.value = model
    }

    fun import(uri: Uri, resolver: android.content.ContentResolver) {
        if (job?.isActive == true) return
        importingTotal.value = wantedModel.value?.sizeBytes ?: 0L
        importing.value = 0
        job = viewModelScope.launch {
            var reported = 0L
            val result = try {
                val input = resolver.openInputStream(uri)
                if (input == null) ModelImport.Failed("could not open the file")
                else c.modelStore.import(input) { bytes -> if (bytes - reported >= PROGRESS_STEP) { reported = bytes; importing.value = bytes } }
            } catch (e: SecurityException) {
                ModelImport.Failed("the file could not be read")
            } finally {
                importing.value = null
                importingTotal.value = 0L
            }
            message.value = when (result) {
                is ModelImport.Installed -> {
                    c.rephrase.release(result.model.backend)
                    installedVersion.value++
                    val chosen = c.settings.current().rephraseBackend
                    if (chosen != result.model.backend) c.settings.update { it.copy(rephraseBackend = result.model.backend) }
                    "Installed ${result.model.title}" + if (chosen != result.model.backend) ". Engine switched to ${result.model.backend.label}." else "."
                }
                is ModelImport.NotSupported -> "That file is not one of the supported models, so it was not installed. (SHA-256 ${result.sha256.take(12)}…)"
                is ModelImport.Failed -> "Could not install the file: ${result.reason}."
            }
        }
    }

    fun cancelImport() { job?.cancel() }

    fun remove(backend: LlmBackend) {
        viewModelScope.launch {
            c.rephrase.release(backend)
            c.modelStore.remove(backend)
            installedVersion.value++
            message.value = "Model removed."
        }
    }

    private companion object {
        const val PROGRESS_STEP = 1L shl 20
    }
}

@Composable
fun WritingAssistantSection(s: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val vm = containerViewModel { WritingAssistantViewModel(it) }
    val importing by vm.importing.collectAsStateWithLifecycle()
    val importingTotal by vm.importingTotal.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val version by vm.installedVersion.collectAsStateWithLifecycle()
    val wantedPicked by vm.wantedModel.collectAsStateWithLifecycle()
    val backend = s.rephraseBackend
    // The phone's memory, read once: it decides which model to hold out as the one that fits best.
    val ramBytes = remember {
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        info.totalMem
    }
    val recommended = remember(ramBytes, backend) { ModelCatalog.recommended(ramBytes, backend) }
    val wanted = wantedPicked ?: recommended
    val installed = remember(backend, version) { c.modelStore.installed(backend) }
    var engineDialog by remember { mutableStateOf(false) }
    var modelDialog by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.import(uri, context.contentResolver) }

    val accent = SettingsAccent.Writing
    SettingsGroup("Writing assistant", Icons.Filled.AutoFixHigh, accent) {
        SwitchRow(
            Icons.Filled.AutoFixHigh, accent, "Rewrite helper",
            "A button in the message box rewrites your draft on this phone: fix everything, more polite, friendlier or shorter. You see the result before anything changes.",
            s.rephraseEnabled,
        ) { v -> scope.launch { c.settings.update { it.copy(rephraseEnabled = v) } } }
        val best = ModelCatalog.bestEngine(ramBytes)
        NavRow(Icons.Filled.Memory, accent, "Engine",
            "${backend.label}. ${backend.summary}" + if (best != backend) " For this phone's memory, ${best.label} is the better fit." else " It is the better fit for this phone's memory.") { engineDialog = true }
        NavRow(
            Icons.Filled.Inventory2, accent, "Model",
            installed?.let { "${it.model.title} · ${it.model.sizeLabel} — ${it.model.ramNote}" } ?: "No ${backend.label} model yet. Pick one below.",
        ) {}
        NavRow(
            Icons.Filled.CloudDownload, accent, "Choose a model",
            (if (ramBytes > 0) "This phone has about ${ramBytes / 1_073_741_824L} GB of memory, so ${recommended.title} is the biggest model it should run well. " else "") +
                "Each model costs a different download size and RAM: bigger is smarter but slower and hungrier. Picking one opens your browser to download it — this app itself never goes online.",
        ) { modelDialog = true }
        NavRow(Icons.Filled.FolderOpen, accent, "Choose model file", "Pick the downloaded ${wanted.fileName}. It is checked against a fixed checksum before it is used.", enabled = importing == null) {
            picker.launch(arrayOf("*/*"))
        }
        // The size quoted is the one actually on disk, not the recommended one, so the number matches what freeing gives back.
        NavRow(Icons.Filled.Delete, accent, "Remove model", "Delete the ${backend.label} model from this phone to free ${(installed?.model ?: wanted).sizeLabel}.", enabled = installed != null && importing == null) { removeDialog = true }
        Text(
            "Models run only on this phone and are kept in app storage that is not backed up. The RAM figures above are what a model holds while it runs — a phone that is running other apps has less to spare, so if a big model feels slow, a smaller one is the answer. A rewrite takes a couple of seconds on a recent phone after the first one.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (engineDialog) {
        val best = ModelCatalog.bestEngine(ramBytes)
        ChoiceDialog(
            "Engine",
            LlmBackend.entries.map { "${it.label}: ${it.summary}" },
            LlmBackend.entries.indexOf(backend),
            { i -> scope.launch { c.settings.update { it.copy(rephraseBackend = LlmBackend.entries[i]) } } },
            recommended = LlmBackend.entries.indexOf(best),
        ) { engineDialog = false }
    }
    if (modelDialog) {
        var justPicked by remember { mutableStateOf(false) }
        val models = ModelCatalog.modelsFor(backend)
        val picked = models.indexOfFirst { it == wanted }
        ChoiceDialog(
            "Model for ${backend.label}",
            models.map { "${it.title} · ${it.sizeLabel} — ${it.ramNote}" },
            picked,
            { i -> justPicked = true; vm.chooseModel(models[i]) },
            recommended = models.indexOfFirst { it == recommended }.takeIf { it >= 0 },
        ) {
            modelDialog = false
            if (justPicked) {
                // Only a deliberate pick opens the browser; someone just comparing the RAM notes is left alone.
                val target = vm.wantedModel.value ?: recommended
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.downloadUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: ActivityNotFoundException) { vm.message.value = "No browser found. Download ${target.downloadUrl} on another device and copy it to this phone." }
            }
        }
    }
    if (removeDialog) {
        AlertDialog(
            onDismissRequest = { removeDialog = false },
            title = { Text("Remove model?") },
            text = { Text("The rewrite button will ask for a model again until you choose a file. You can add it again at any time.") },
            confirmButton = { TextButton(onClick = { vm.remove(backend); removeDialog = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removeDialog = false }) { Text("Cancel") } },
        )
    }
    importing?.let { bytes ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Checking the file") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val total = importingTotal.takeIf { it > 0 } ?: ModelCatalog.all.maxOf { it.sizeBytes }
                    LinearProgressIndicator(progress = { (bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text("%.0f MB read. Copying into private storage and verifying its checksum.".format(bytes / 1_048_576.0), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = vm::cancelImport) { Text("Cancel") } },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { vm.message.value = null },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { vm.message.value = null }) { Text("OK") } },
        )
    }
}
