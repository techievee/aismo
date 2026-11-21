package aismo.sms.ui

import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import aismo.sms.container
import aismo.sms.data.db.BudgetEntity
import aismo.sms.data.db.ReminderEntity
import aismo.sms.domain.SmsCategory
import aismo.sms.domain.parser.TxnKind
import aismo.sms.telephony.NotificationCenter
import java.util.Currency
import java.util.Locale

@Composable
fun FinanceScreen(onBack: () -> Unit, onOpen: (key: String, address: String) -> Unit) {
    val c = LocalContext.current.container
    val vm = containerViewModel { FinanceViewModel(it) }
    val month by vm.month.collectAsStateWithLifecycle()
    val txns by vm.transactions.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    val budgets by vm.budgets.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var budgetDialog by remember { mutableStateOf(false) }

    SimpleScaffold("Finance", onBack) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = vm::previous) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month") }
                    Text(month.monthTitle(), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = vm::next) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month") }
                }
            }
            if (summaries.isEmpty()) {
                item { Text("No transactions found for this month.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(summaries, key = { it.currency }) { s ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(s.currency, style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Spent", style = MaterialTheme.typography.labelMedium)
                                Text(NotificationCenter.formatMoney(s.spentMinor, s.currency), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Received", style = MaterialTheme.typography.labelMedium)
                                Text(NotificationCenter.formatMoney(s.receivedMinor, s.currency), style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                        s.byBucket.take(5).forEach { (bucket, amount) ->
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(bucket, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    Text(NotificationCenter.formatMoney(amount, s.currency))
                                }
                                LinearProgressIndicator(progress = { if (s.spentMinor == 0L) 0f else amount.toFloat() / s.spentMinor }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Budgets", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { budgetDialog = true }) { Icon(Icons.Filled.Add, null); Text(" Add") }
                }
            }
            items(budgets, key = { it.bucket }) { b ->
                val spent = txns.filter {
                    it.kind == TxnKind.DEBIT.name && it.currency == b.currency && (b.bucket == "*" || vm.bucketOf(it).equals(b.bucket, true))
                }.sumOf { it.amountMinor }
                val over = spent > b.limitMinor
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp), colors = CardDefaults.cardColors(containerColor = if (over) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (b.bucket == "*") "Overall" else b.bucket, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            IconButton(onClick = { vm.deleteBudget(b.bucket) }) { Icon(Icons.Filled.Delete, "Delete budget") }
                        }
                        Text("${NotificationCenter.formatMoney(spent, b.currency)} of ${NotificationCenter.formatMoney(b.limitMinor, b.currency)} · alert at ${b.alertPercent}%", style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(progress = { if (b.limitMinor <= 0) 0f else (spent.toFloat() / b.limitMinor).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            item { Text("Transactions", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.titleMedium) }
            items(txns, key = { it.messageId }) { t ->
                ListItem(
                    modifier = Modifier.clickable { scope.launch { c.db.messages().get(t.messageId)?.let { onOpen(it.conversationKey, it.address) } } },
                    headlineContent = { Text(vm.bucketOf(t), maxLines = 1) },
                    supportingContent = { Text(listOfNotNull(t.instrument, t.last4?.let { "•$it" }, formatListTime(t.occurredAt)).joinToString(" · ")) },
                    trailingContent = {
                        val debit = t.kind == TxnKind.DEBIT.name
                        Text((if (debit) "−" else "+") + NotificationCenter.formatMoney(t.amountMinor, t.currency), color = if (debit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }

    if (budgetDialog) {
        BudgetDialog(
            buckets = summaries.flatMap { it.byBucket.map { b -> b.first } }.distinct(),
            defaultCurrency = summaries.firstOrNull()?.currency ?: runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("USD"),
            onSave = { vm.saveBudget(it); budgetDialog = false },
            onDismiss = { budgetDialog = false },
        )
    }
}

@Composable
private fun BudgetDialog(buckets: List<String>, defaultCurrency: String, onSave: (BudgetEntity) -> Unit, onDismiss: () -> Unit) {
    var bucket by remember { mutableStateOf("*") }
    var amount by remember { mutableStateOf("") }
    var percent by remember { mutableFloatStateOf(80f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New monthly budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = bucket == "*", onClick = { bucket = "*" }, label = { Text("Overall") })
                }
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(buckets.take(8)) { b -> FilterChip(selected = bucket == b, onClick = { bucket = b }, label = { Text(b) }, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                OutlinedTextField(value = amount, onValueChange = { amount = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Monthly limit ($defaultCurrency)") }, singleLine = true)
                Text("Alert at ${percent.toInt()}%")
                Slider(value = percent, onValueChange = { percent = it }, valueRange = 50f..100f, steps = 9)
            }
        },
        confirmButton = {
            TextButton(
                enabled = (amount.toDoubleOrNull() ?: 0.0) > 0,
                onClick = { onSave(BudgetEntity(bucket, ((amount.toDouble()) * 100).toLong(), defaultCurrency, percent.toInt())) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RemindersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val reminders by c.db.insights().openReminders().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    SimpleScaffold("Reminders", onBack) { padding ->
        if (reminders.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp)) {
                Text("No upcoming bills, bookings or deliveries.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(reminders, key = { it.id }) { r ->
                    ReminderCard(
                        r,
                        onCalendar = {
                            val intent = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                                .putExtra(CalendarContract.Events.TITLE, "${r.title}: ${r.sender}")
                                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, true)
                                .apply { r.dueAt?.let { putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, it) } }
                            runCatching { context.startActivity(intent) }.onFailure { Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show() }
                        },
                        onDone = { scope.launch { c.db.insights().setReminderStatus(r.id, "done") } },
                        onDismiss = { scope.launch { c.db.insights().setReminderStatus(r.id, "dismissed") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderCard(r: ReminderEntity, onCalendar: () -> Unit, onDone: () -> Unit, onDismiss: () -> Unit) {
    val overdue = r.dueAt != null && r.dueAt < System.currentTimeMillis()
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${r.title} · ${r.sender}", style = MaterialTheme.typography.titleMedium)
            Text(
                r.dueAt?.let { (if (overdue) "Overdue · " else "Due ") + formatDay(it) } ?: "No date found",
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            r.amountMinor?.let { Text(NotificationCenter.formatMoney(it, r.currency), style = MaterialTheme.typography.titleLarge) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.dueAt != null) OutlinedButton(onClick = onCalendar) { Text("Add to calendar") }
                Button(onClick = onDone) { Text("Done") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

@Composable
fun ProtectionScreen(onBack: () -> Unit, onOpen: (key: String, address: String) -> Unit) {
    val c = LocalContext.current.container
    val incidents by c.db.insights().openIncidents().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    SimpleScaffold(
        "Spam and scam alerts", onBack,
        actions = { if (incidents.isNotEmpty()) TextButton(onClick = { scope.launch { c.db.insights().clearIncidents() } }) { Text("Clear all") } },
    ) { padding ->
        if (incidents.isEmpty()) {
            Column(Modifier.padding(padding).fillMaxSize().padding(32.dp)) {
                Text("Nothing suspicious found. Risky messages are flagged here when they arrive.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(incidents, key = { it.messageId }) { i ->
                    val message by produceState<aismo.sms.data.db.MessageEntity?>(null, i.messageId) { value = c.db.messages().get(i.messageId) }
                    val name by rememberContactName(i.sender)
                    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable { message?.let { onOpen(it.conversationKey, it.address) } }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(displayTitle(i.sender, null, name), style = MaterialTheme.typography.titleMedium)
                            Text("Risk ${i.score}/100 · ${i.reasons}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            message?.let { Text(it.body, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { scope.launch { c.repository.deleteMessages(listOf(i.messageId)) } }) { Text("Delete") }
                                TextButton(onClick = {
                                    scope.launch { message?.let { c.repository.blockSender(it.conversationKey) }; c.db.insights().dismissIncident(i.messageId) }
                                }) { Text("Block sender") }
                                TextButton(onClick = {
                                    scope.launch {
                                        message?.let { c.repository.setConversationCategory(it.conversationKey, SmsCategory.OTHER.key) }
                                        c.db.insights().dismissIncident(i.messageId)
                                    }
                                }) { Text("Not spam") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val blockedConversations by c.db.conversations().observeBlocked().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var numbers by remember { mutableStateOf(emptyList<String>()) }
    var addDialog by remember { mutableStateOf(false) }
    val canBlock = remember { c.blocked.canBlock() }
    val reload = { numbers = c.blocked.all() }
    androidx.compose.runtime.LaunchedEffect(blockedConversations.size) { reload() }

    SimpleScaffold("Blocked senders", onBack, actions = { IconButton(onClick = { addDialog = true }, enabled = canBlock) { Icon(Icons.Filled.Add, "Block a number") } }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (!canBlock) item {
                Text("Only the default SMS app can change the block list. Set this app as default to manage it.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            if (blockedConversations.isEmpty() && numbers.isEmpty()) item {
                Text("You haven't blocked anyone.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(blockedConversations, key = { "c:" + it.key }) { conv ->
                ListItem(
                    headlineContent = { Text(conv.title ?: conv.address) },
                    supportingContent = { Text(conv.latestBody, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { TextButton(onClick = { scope.launch { c.repository.unblockSender(conv.key); reload() } }) { Text("Unblock") } },
                )
            }
            val shown = blockedConversations.map { it.address }.toSet()
            items(numbers.filter { it !in shown }, key = { "n:$it" }) { n ->
                ListItem(
                    headlineContent = { Text(n) },
                    trailingContent = { TextButton(onClick = { scope.launch { c.blocked.unblock(n); reload() } }) { Text("Unblock") } },
                )
            }
        }
    }
    if (addDialog) {
        var number by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Block a number") },
            text = { OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Phone number") }, singleLine = true) },
            confirmButton = { TextButton(enabled = number.isNotBlank(), onClick = { c.blocked.block(number.trim()); reload(); addDialog = false }) { Text("Block") } },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Cancel") } },
        )
    }
}
