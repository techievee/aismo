package aismo.sms.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import aismo.sms.container
import aismo.sms.data.db.TranslationEntity
import aismo.sms.data.llm.RephraseService
import aismo.sms.data.settings.AppSettings
import aismo.sms.domain.translate.Language
import aismo.sms.domain.translate.TranslationResult
import aismo.sms.domain.translate.defaultTarget
import java.util.Locale

/** What the translate sheet is showing. */
sealed interface TranslateUi {
    data object Closed : TranslateUi
    data object Choosing : TranslateUi
    data class Working(val target: Language) : TranslateUi
    data class Done(val from: Language?, val to: Language, val outcome: TranslationResult, val variation: Int) : TranslateUi
}

/** Runs one translation. Leaving the screen cancels the scope, which stops the model. */
@Stable
class TranslateController(private val service: RephraseService, private val scope: CoroutineScope) {
    var ui by mutableStateOf<TranslateUi>(TranslateUi.Closed)
        private set
    private var job: Job? = null

    fun open() { ui = TranslateUi.Choosing }

    fun start(text: String, from: Language?, to: Language, variation: Int = 0) {
        job?.cancel()
        ui = TranslateUi.Working(to)
        job = scope.launch { ui = TranslateUi.Done(from, to, service.translate(text, from, to, variation), variation) }
    }

    /** Cancels and goes back to the choice of languages. */
    fun cancel() { job?.cancel(); ui = TranslateUi.Choosing }

    fun dismiss() { job?.cancel(); ui = TranslateUi.Closed }
}

@Composable
fun rememberTranslateController(): TranslateController {
    val service = LocalContext.current.container.rephrase
    val scope = rememberCoroutineScope()
    return remember(service, scope) { TranslateController(service, scope) }
}

/**
 * Lets the user choose the languages and shows the model's translation. The result is handed to [onTranslated],
 * which stores it under the message; the original text is never changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateSheet(
    text: String,
    controller: TranslateController,
    defaultFrom: Language?,
    defaultTo: Language,
    onTranslated: (Language?, Language, String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = controller.ui
    if (ui == TranslateUi.Closed) return
    var from by remember(text) { mutableStateOf(defaultFrom) }
    var to by remember(text) { mutableStateOf(defaultTo) }
    var pickingFrom by remember { mutableStateOf(false) }
    var pickingTo by remember { mutableStateOf(false) }
    val done = ui as? TranslateUi.Done
    LaunchedEffect(done) {
        if (done != null && done.outcome is TranslationResult.Success) onTranslated(done.from, done.to, (done.outcome as TranslationResult.Success).text)
    }
    ModalBottomSheet(onDismissRequest = controller::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (ui) {
                TranslateUi.Closed -> Unit
                TranslateUi.Choosing -> Choices(text, from, to, onFrom = { pickingFrom = true }, onTo = { pickingTo = true }) {
                    controller.start(text, from, to)
                }
                is TranslateUi.Working -> Working(ui.target, controller::cancel)
                is TranslateUi.Done -> Result(text, ui, controller, onOpenSettings)
            }
        }
    }
    if (pickingFrom) {
        ChoiceDialog(
            "Translate from",
            listOf("Auto") + Language.entries.map { it.label },
            if (from == null) 0 else Language.entries.indexOf(from) + 1,
            { i -> from = if (i == 0) null else Language.entries[i - 1] },
        ) { pickingFrom = false }
    }
    if (pickingTo) {
        ChoiceDialog(
            "Translate to",
            Language.entries.map { it.label },
            Language.entries.indexOf(to),
            { i -> to = Language.entries[i] },
        ) { pickingTo = false }
    }
}

@Composable
private fun Choices(text: String, from: Language?, to: Language, onFrom: () -> Unit, onTo: () -> Unit, onTranslate: () -> Unit) {
    Text("Translate message", style = MaterialTheme.typography.titleLarge)
    Text("Done on this phone. You pick both languages, or let it guess where the message is from.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LanguageChoiceRow("From", if (from != null) from.label else "Auto", onFrom)
    LanguageChoiceRow("To", to.label, onTo)
    Button(onClick = onTranslate, enabled = text.isNotBlank()) { Text("Translate") }
    Text("The original message stays exactly as it was.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LanguageChoiceRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Working(target: Language, onCancel: () -> Unit) {
    Text("Translate to ${target.label}", style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Text("Translating on this phone…", style = MaterialTheme.typography.bodyLarge)
    }
    Text("The first one takes a few seconds longer while the model loads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun Result(
    original: String,
    done: TranslateUi.Done,
    controller: TranslateController,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    when (val outcome = done.outcome) {
        is TranslationResult.Success -> {
            Text("Translated to ${done.to.label}", style = MaterialTheme.typography.titleLarge)
            Text("Original", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(original, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                SelectionContainer { Text(outcome.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            Text(
                "A small model can get the meaning wrong. The original message stays as it was.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("translation", outcome.text))
                }) { Text("Copy") }
                OutlinedButton(onClick = { controller.start(original, done.from, done.to, done.variation + 1) }) { Text("Try again") }
                TextButton(onClick = controller::dismiss) { Text("Close") }
            }
        }
        TranslationResult.AlreadyIn -> Message(
            "Already in ${done.to.label}",
            "This message is already in ${done.to.label}, so nothing was translated.",
            controller,
        )
        TranslationResult.NoText -> Message("Nothing to translate", "The message has no text to translate.", controller)
        TranslationResult.TooLong -> Message("Too long to translate here", "This message is too long to translate here (up to 500 characters).", controller)
        is TranslationResult.Unavailable -> Message("Can't translate right now", outcome.reason, controller)
        is TranslationResult.NoModel -> Message(
            "No writing model yet",
            "Translating needs a ${outcome.backend.label} model file on this phone. The app has no internet access, so you download it once in your browser and choose it in Settings.",
            controller,
            extra = { Button(onClick = onOpenSettings) { Text("Open settings") } },
        )
    }
}

@Composable
private fun Message(title: String, body: String, controller: TranslateController, extra: @Composable () -> Unit = {}) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(body, style = MaterialTheme.typography.bodyLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        extra()
        TextButton(onClick = controller::dismiss) { Text("Close") }
    }
}

/**
 * Translation: which language messages are translated into, and whether incoming messages that are not in that
 * language are translated automatically. The model and engine are shared with the writing assistant and set up
 * there.
 */
@Composable
fun TranslationSection(s: AppSettings) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var langDialog by remember { mutableStateOf(false) }
    val target = Language.fromKey(s.translateTargetLang) ?: defaultTarget(Locale.getDefault().language)
    val accent = SettingsAccent.Translation
    SettingsGroup("Translation", Icons.Filled.Language, accent) {
        SwitchRow(
            Icons.Filled.Language, accent, "Auto-translate",
            "Messages that are not in your language are translated on this phone and shown under the original",
            s.translateEnabled,
        ) { v -> scope.launch { c.settings.update { it.copy(translateEnabled = v) } } }
        NavRow(Icons.Filled.Public, accent, "Your language", target.label) { langDialog = true }
        Text(
            "Translation runs on this phone with the writing assistant's model. Tap and hold a message to translate it into any other language.",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (langDialog) {
        ChoiceDialog(
            "Your language",
            Language.entries.map { it.label },
            Language.entries.indexOf(target),
            { i -> scope.launch { c.settings.update { it.copy(translateTargetLang = Language.entries[i].key) } } },
        ) { langDialog = false }
    }
}

/** The translations stored for one message, shown under its own text. */
@Composable
fun TranslationBlock(translations: List<TranslationEntity>) {
    for (t in translations) {
        val target = Language.fromKey(t.target) ?: continue
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
            Text("Translated to ${target.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(t.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
