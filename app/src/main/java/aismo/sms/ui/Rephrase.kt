package aismo.sms.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import aismo.sms.container
import aismo.sms.data.llm.RephraseService
import aismo.sms.domain.rephrase.RephraseMode
import aismo.sms.domain.rephrase.RephraseOutcome

/** What the rewrite sheet is showing. */
sealed interface RephraseUi {
    data object Closed : RephraseUi
    data object Choosing : RephraseUi
    data class Working(val mode: RephraseMode) : RephraseUi
    data class Done(val mode: RephraseMode, val draft: String, val context: String?, val outcome: RephraseOutcome, val variation: Int) : RephraseUi
}

/** Runs the rewrite for one composer. Leaving the screen cancels the scope, which stops the model. */
@Stable
class RephraseController(private val service: RephraseService, private val scope: CoroutineScope) {
    var ui by mutableStateOf<RephraseUi>(RephraseUi.Closed)
        private set
    private var job: Job? = null

    fun open() { ui = RephraseUi.Choosing }

    /** [context] is the recent messages of the thread the draft is a reply in; "Try again" [variation] keeps it. */
    fun start(mode: RephraseMode, draft: String, context: String? = null, variation: Int = 0) {
        job?.cancel()
        ui = RephraseUi.Working(mode)
        job = scope.launch { ui = RephraseUi.Done(mode, draft, context, service.rephrase(mode, draft, context, variation), variation) }
    }

    /** Stops the model and goes back to the choice of rewrite. */
    fun cancel() { job?.cancel(); ui = RephraseUi.Choosing }

    fun dismiss() { job?.cancel(); ui = RephraseUi.Closed }
}

@Composable
fun rememberRephraseController(): RephraseController {
    val service = LocalContext.current.container.rephrase
    val scope = rememberCoroutineScope()
    return remember(service, scope) { RephraseController(service, scope) }
}

@Composable
fun RephraseButton(controller: RephraseController, draft: String) {
    IconButton(onClick = controller::open, enabled = draft.isNotBlank()) { Icon(Icons.Filled.AutoFixHigh, "Rewrite message") }
}

/**
 * Lets the user choose how to rewrite [draft], shows the model's answer, and only calls [onUse] when they accept it. The
 * checks behind it catch lost numbers and names but not a swapped meaning, so the answer is always shown first. When the
 * draft is a reply in a thread, [context] carries the recent messages of it and the user can have the model write the
 * rewrite with that conversation in mind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RephraseSheet(controller: RephraseController, draft: String, context: String? = null, onUse: (String) -> Unit, onOpenSettings: () -> Unit) {
    val ui = controller.ui
    if (ui == RephraseUi.Closed) return
    val hasContext = !context.isNullOrBlank()
    // On by default in a thread: the point of opening the assistant there is a reply that fits the conversation.
    var useContext by remember { mutableStateOf(hasContext) }
    ModalBottomSheet(onDismissRequest = controller::dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (ui) {
                RephraseUi.Closed -> Unit
                RephraseUi.Choosing -> Choices(hasContext, useContext, { useContext = it }) { mode ->
                    controller.start(mode, draft, if (useContext) context else null)
                }
                is RephraseUi.Working -> Working(ui.mode, controller::cancel)
                is RephraseUi.Done -> Result(ui, controller, onUse = { onUse(it); controller.dismiss() }, onOpenSettings = { controller.dismiss(); onOpenSettings() })
            }
        }
    }
}

@Composable
private fun Choices(hasContext: Boolean, useContext: Boolean, onUseContext: (Boolean) -> Unit, onPick: (RephraseMode) -> Unit) {
    Text("Rewrite message", style = MaterialTheme.typography.titleLarge)
    Text("Done on this phone. You see the result before anything changes.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (hasContext) {
        Row(
            Modifier.clickable(onClickLabel = "Use the conversation as context", onClick = { onUseContext(!useContext) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = useContext, onCheckedChange = null)
            Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Use the conversation as context", style = MaterialTheme.typography.bodyLarge)
                Text("The recent messages are shown to the model so the reply fits the discussion.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Column {
        for (mode in RephraseMode.entries) {
            ListItem(
                modifier = Modifier.clickable { onPick(mode) },
                headlineContent = { Text(mode.label) },
                supportingContent = { Text(mode.description) },
            )
        }
    }
}

@Composable
private fun Working(mode: RephraseMode, onCancel: () -> Unit) {
    Text(mode.label, style = MaterialTheme.typography.titleLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Text("Rewriting on this phone…", style = MaterialTheme.typography.bodyLarge)
    }
    Text("The first rewrite takes a few seconds longer while the model loads.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
private fun Result(done: RephraseUi.Done, controller: RephraseController, onUse: (String) -> Unit, onOpenSettings: () -> Unit) {
    val again = { controller.start(done.mode, done.draft, done.context, done.variation + 1) }
    when (val outcome = done.outcome) {
        is RephraseOutcome.Suggestion -> {
            Text(done.mode.label, style = MaterialTheme.typography.titleLarge)
            Text("Your message", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(done.draft, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
            Text("Suggestion", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                SelectionContainer { Text(outcome.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            if (done.context != null) {
                Text("Written with the conversation in mind.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "A small model can get the meaning wrong. Read it before you send.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onUse(outcome.text) }) { Text("Use this") }
                OutlinedButton(onClick = again) { Text("Try again") }
                TextButton(onClick = controller::dismiss) { Text("Keep mine") }
            }
        }
        RephraseOutcome.NothingToFix -> Message("Nothing to fix", "This already reads fine, so it was left as it is.", controller)
        is RephraseOutcome.Declined -> Message(
            "Left as it is", outcome.message, controller, detail = outcome.detail, extra = { OutlinedButton(onClick = again) { Text("Try again") } },
        )
        is RephraseOutcome.Unavailable -> Message("Can't rewrite right now", outcome.reason.replaceFirstChar { it.uppercase() } + ".", controller)
        is RephraseOutcome.NoModel -> Message(
            "No writing model yet",
            "Rewriting needs a ${outcome.backend.label} model file on this phone. The app has no internet access, so you download it once in your browser and choose it in Settings.",
            controller,
            extra = { Button(onClick = onOpenSettings) { Text("Open settings") } },
        )
    }
}

@Composable
private fun Message(title: String, body: String, controller: RephraseController, detail: String = "", extra: @Composable () -> Unit = {}) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(body, style = MaterialTheme.typography.bodyLarge)
    if (detail.isNotBlank()) Text(detail.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        extra()
        TextButton(onClick = controller::dismiss) { Text("Close") }
    }
}
