package aismo.sms.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import aismo.sms.backup.BackupDamagedException
import aismo.sms.backup.BackupTooNewException
import aismo.sms.backup.NotABackupException
import aismo.sms.backup.RestoreSummary
import aismo.sms.backup.VAULT_BACKUP_PASSPHRASE
import aismo.sms.backup.WrongPassphraseException
import aismo.sms.container
import aismo.sms.data.settings.AppSettings
import aismo.sms.work.Workers

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle(AppSettings())

    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var savedPassphrase by remember { mutableStateOf(c.vault.has(VAULT_BACKUP_PASSPHRASE)) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var mmsBytes by remember { mutableStateOf<Long?>(null) }
    var restoreProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var summary by remember { mutableStateOf<RestoreSummary?>(null) }
    var securedNoMethod by remember { mutableStateOf(false) }
    var securedCount by remember { mutableStateOf(0) }
    var intervalDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { mmsBytes = runCatching { c.backup.estimateMmsBytes() }.getOrNull() }

    val folderName = remember(s.backupFolderUri) { s.backupFolderUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.name } }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            scope.launch { c.settings.update { it.copy(backupFolderUri = uri.toString()) } }
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) restoreUri = uri // read as a stream when restoring; attachments can make a backup large
    }

    val passphraseOk = passphrase.length >= 8 && passphrase == confirm

    SimpleScaffold("Backup and restore", onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Backups are encrypted with your passphrase and include text messages, categories, rules, budgets and settings. If you lose the passphrase, a backup cannot be opened.", style = MaterialTheme.typography.bodyMedium)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include MMS and attachments", style = MaterialTheme.typography.titleMedium)
                    Text(
                        mmsBytes?.let { "Pictures, videos and group messages. About ${formatSize(it)} of attachments on this phone." }
                            ?: "Pictures, videos and group messages.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.backupIncludeMms, onCheckedChange = { on -> scope.launch { c.settings.update { it.copy(backupIncludeMms = on) } } })
            }

            Text("Location", style = MaterialTheme.typography.titleMedium)
            Text(folderName ?: "No folder chosen", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(if (folderName == null) "Choose folder" else "Change folder") }

            Text("Passphrase", style = MaterialTheme.typography.titleMedium)
            if (savedPassphrase) {
                Text("A passphrase is saved securely on this device for backups.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { c.vault.remove(VAULT_BACKUP_PASSPHRASE); savedPassphrase = false; scope.launch { c.settings.update { it.copy(autoBackup = false) }; Workers.scheduleAutoBackup(context, 0) } }) { Text("Forget saved passphrase") }
            } else {
                OutlinedTextField(passphrase, { passphrase = it }, label = { Text("Passphrase (8+ characters)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(confirm, { confirm = it }, label = { Text("Repeat passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    isError = confirm.isNotEmpty() && confirm != passphrase)
                OutlinedButton(enabled = passphraseOk, onClick = { c.vault.put(VAULT_BACKUP_PASSPHRASE, passphrase); savedPassphrase = true; passphrase = ""; confirm = "" }) { Text("Save passphrase") }
            }

            Button(
                enabled = !busy && folderName != null && savedPassphrase,
                onClick = {
                    busy = true; status = "Creating backup…"; restoreProgress = 0 to 0
                    scope.launch {
                        status = runCatching {
                            val r = c.backup.writeToFolder(Uri.parse(s.backupFolderUri!!), c.vault.get(VAULT_BACKUP_PASSPHRASE)!!.toCharArray(), automatic = false) { d, t -> restoreProgress = d to t }
                            val left = r.stats.skippedParts
                            "Saved ${r.name}" + if (left > 0) ". $left attachment${if (left == 1) " was" else "s were"} left out (unreadable or over 64 MB)." else ""
                        }.getOrElse { "Backup failed: ${it.message}" }
                        busy = false; restoreProgress = null
                    }
                },
            ) { Text("Back up now") }
            if (s.lastBackupAt > 0) Text("Last backup: ${formatDateTime(s.lastBackupAt)}", style = MaterialTheme.typography.bodySmall)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic backups", style = MaterialTheme.typography.titleMedium)
                    Text("Every ${s.autoBackupDays} day(s), keeping the newest five", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = s.autoBackup, enabled = folderName != null && savedPassphrase,
                    onCheckedChange = { on -> scope.launch { c.settings.update { it.copy(autoBackup = on) }; Workers.scheduleAutoBackup(context, if (on) s.autoBackupDays else 0) } },
                )
            }
            TextButton(onClick = { intervalDialog = true }, enabled = s.autoBackup) { Text("Change interval") }

            Text("Restore", style = MaterialTheme.typography.titleMedium)
            Text("Restoring adds missing messages (and MMS, if the backup has them) and merges categories, rules and budgets. Existing messages are never duplicated.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Restore from file…") }

            if (busy || restoreProgress != null) {
                val p = restoreProgress
                if (p != null && p.second > 0) LinearProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }

    restoreUri?.let { uri ->
        var pass by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text("Enter the backup passphrase") },
            text = {
                Column {
                    OutlinedTextField(pass, { pass = it; error = null }, singleLine = true, visualTransformation = PasswordVisualTransformation(), isError = error != null, supportingText = error?.let { { Text(it) } })
                }
            },
            confirmButton = {
                TextButton(enabled = pass.isNotEmpty() && !busy, onClick = {
                    busy = true; restoreProgress = 0 to 0
                    scope.launch {
                        val open = { context.contentResolver.openInputStream(uri) ?: throw java.io.FileNotFoundException() }
                        runCatching { c.backup.restore(open, pass.toCharArray()) { d, t -> restoreProgress = d to t } }
                            .onSuccess { r ->
                                summary = r
                                restoreUri = null
                                scope.launch {
                                    // A restore can bring back secured categories that this phone cannot re-lock (no PIN, no
                                    // fingerprint): they stay visible rather than locked away, and the user is told so.
                                    val s = c.settings.current()
                                    securedNoMethod = r.settingsRestored && s.securedCategories.isNotEmpty() && c.security.availableMethods(s).isEmpty()
                                    securedCount = s.securedCategories.size
                                }
                            }
                            .onFailure {
                                error = when (it) {
                                    is WrongPassphraseException -> "Wrong passphrase, or the file is damaged."
                                    is NotABackupException -> "That isn't an aismo backup."
                                    is BackupTooNewException -> "This backup is from a newer version of the app."
                                    is BackupDamagedException -> "This backup is damaged (${it.message}). Nothing was restored."
                                    is java.io.IOException -> "Couldn't read that file."
                                    else -> "Restore failed: ${it.message}"
                                }
                            }
                        busy = false; restoreProgress = null
                    }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoreUri = null }) { Text("Cancel") } },
        )
    }

    summary?.let { r ->
        AlertDialog(
            onDismissRequest = { summary = null },
            title = { Text("Restore complete") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.messagesAdded} messages added, ${r.messagesSkipped} already present.")
                    if (r.mmsAdded > 0 || r.mmsSkipped > 0) Text("${r.mmsAdded} MMS added, ${r.mmsSkipped} already present.")
                    Text("${r.categories} categories, ${r.rules} new rules, ${r.budgets} budgets.")
                    if (r.settingsRestored) Text("Settings restored.")
                    if (securedNoMethod) Text("The backup also secures $securedCount categor${if (securedCount == 1) "y" else "ies"}, but this phone has no PIN or fingerprint to unlock them, so they stay visible. Set a PIN under Security to lock them.")
                    if (r.messagesNeedDefaultApp) Text("Messages (and MMS) weren't restored because this isn't your default SMS app. Set it as default and restore again.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = { TextButton(onClick = { summary = null }) { Text("OK") } },
        )
    }

    if (intervalDialog) {
        AlertDialog(
            onDismissRequest = { intervalDialog = false },
            title = { Text("Back up every") },
            text = {
                Column {
                    listOf(1 to "Day", 3 to "3 days", 7 to "Week").forEach { (days, label) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s.autoBackupDays == days, onClick = {
                                scope.launch { c.settings.update { it.copy(autoBackupDays = days) }; Workers.scheduleAutoBackup(context, days) }
                                intervalDialog = false
                            })
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { intervalDialog = false }) { Text("Close") } },
        )
    }
}
