package aismo.sms.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import aismo.sms.domain.CategoryIconKeys
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import aismo.sms.domain.SendDelay
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import aismo.sms.domain.CategoryOrder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import aismo.sms.container
import aismo.sms.data.db.CustomCategoryEntity
import aismo.sms.data.db.UserRuleEntity
import aismo.sms.data.settings.AppSettings
import aismo.sms.data.settings.ThemeMode
import aismo.sms.domain.SmsCategory
import aismo.sms.work.Workers

/** One category of settings: its key, the name and icon it wears, and a line about what is inside. */
private data class SettingsGroupInfo(
    val key: String,
    val title: String,
    val icon: ImageVector,
    val accent: Color,
    val summary: String,
)

/** The categories of the settings page, in order. The first page lists them; each one opens its own page of settings. */
private val settingsGroups = listOf(
    SettingsGroupInfo("general", "General", Icons.Filled.Settings, SettingsAccent.General, "The default app and SIM, the look of the app, and spam in the inbox"),
    SettingsGroupInfo("sending", "Sending", Icons.AutoMirrored.Filled.Send, SettingsAccent.Sending, "Hold a message after Send so you can undo it"),
    SettingsGroupInfo("notifications", "Notifications", Icons.Filled.NotificationsActive, SettingsAccent.Notifications, "Which messages raise an alert, and for whom"),
    SettingsGroupInfo("media", "Multimedia messages (MMS)", Icons.Filled.PermMedia, SettingsAccent.Media, "Threads, downloads, and long texts"),
    SettingsGroupInfo("search", "Search", Icons.Filled.Search, SettingsAccent.Search, "Find messages by what they mean, on this phone"),
    SettingsGroupInfo("writing", "Writing assistant", Icons.Filled.AutoFixHigh, SettingsAccent.Writing, "Rewrite a reply with the model on this phone"),
    SettingsGroupInfo("translation", "Translation", Icons.Filled.Language, SettingsAccent.Translation, "Translate incoming messages on this phone"),
    SettingsGroupInfo("codes", "One-time codes", Icons.Filled.Password, SettingsAccent.Codes, "Show codes with a copy button, and remove them when they are old"),
    SettingsGroupInfo("organizer", "Organizer", Icons.Filled.Category, SettingsAccent.Organizer, "Inbox categories, custom categories, and rules"),
    SettingsGroupInfo("security", "Security", Icons.Filled.Lock, SettingsAccent.Security, "Lock categories behind a fingerprint or a PIN"),
    SettingsGroupInfo("data", "Data", Icons.Filled.Storage, SettingsAccent.Data, "Backups, and why nothing ever leaves this phone"),
    SettingsGroupInfo("help", "Help and about", Icons.Filled.Info, SettingsAccent.About, "Help, what is inside, privacy, and licences"),
)

/** The first settings page: one row per category, each opening the page of that category's settings. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenGroup: (String) -> Unit) {
    SimpleScaffold("Settings", onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 24.dp)) {
            SettingsCard {
                settingsGroups.forEach { g ->
                    NavRow(g.icon, g.accent, g.title, g.summary, chevron = true) { onOpenGroup(g.key) }
                }
            }
        }
    }
}

/** The second settings page: the settings of one category, reached from the list above. */
@Composable
fun SettingsGroupScreen(
    group: String,
    onBack: () -> Unit, onCategories: () -> Unit, onCategoryOrder: () -> Unit, onRules: () -> Unit, onBackup: () -> Unit, onAppearance: () -> Unit,
    onHelp: () -> Unit, onAbout: () -> Unit, onPrivacy: () -> Unit,
) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val org = containerViewModel { OrganizerViewModel(it) }
    val reapplying by org.reapplying.collectAsStateWithLifecycle()
    val sims = remember { c.sims.active() }
    fun update(f: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(f) } }

    var hoursDialog by remember { mutableStateOf(false) }
    var holdDialog by remember { mutableStateOf(false) }
    var simDialog by remember { mutableStateOf(false) }
    var numberDialog by remember { mutableStateOf(false) }
    var segmentsDialog by remember { mutableStateOf(false) }

    val info = remember(group) { settingsGroups.firstOrNull { it.key == group } ?: settingsGroups.first() }
    SimpleScaffold(info.title, onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            if (group == "general") {
                val general = SettingsAccent.General
                SettingsGroup("General", Icons.Filled.Settings, general) {
                    NavRow(Icons.Filled.Sms, general, "Default SMS app", if (context.isDefaultSmsApp()) "This app is your default" else "Another app is the default. Set this one from the inbox banner.") {}
                    NavRow(Icons.Filled.Palette, general, "Appearance", appearanceSummary(s), chevron = true, onClick = onAppearance)
                    SwitchRow(Icons.Filled.Report, general, "Show spam in All", "Spam conversations appear in the main inbox", s.includeSpamInAll) { v -> update { it.copy(includeSpamInAll = v) } }
                    if (sims.size > 1) {
                        NavRow(Icons.Filled.SimCard, general, "Default SIM", sims.firstOrNull { it.subId == s.defaultSubId }?.label ?: "Use system default") { simDialog = true }
                    }
                }
            }

            if (group == "sending") {
                val sending = SettingsAccent.Sending
                SettingsGroup("Sending", Icons.AutoMirrored.Filled.Send, sending) {
                    NavRow(Icons.AutoMirrored.Filled.Undo, sending, "Undo send", sendHoldSummary(s.sendDelaySeconds)) { holdDialog = true }
                }
            }

            if (group == "notifications") {
                val alerts = SettingsAccent.Notifications
                SettingsGroup("Notifications", Icons.Filled.NotificationsActive, alerts) {
                    SwitchRow(Icons.Filled.Key, alerts, "One-time code alerts", "Show codes with a copy button", s.otpNotifications) { v -> update { it.copy(otpNotifications = v) } }
                    SwitchRow(Icons.Filled.Receipt, alerts, "Transaction previews", "Summarise bank alerts in the notification", s.transactionPreviews) { v -> update { it.copy(transactionPreviews = v) } }
                    SwitchRow(Icons.Filled.GppMaybe, alerts, "Spam and scam alerts", "Warn when a risky message arrives", s.protectionAlerts) { v -> update { it.copy(protectionAlerts = v) } }
                    SwitchRow(Icons.Filled.Alarm, alerts, "Reminder alerts", "Bills, trips and deliveries due soon", s.reminderNotifications) { v -> update { it.copy(reminderNotifications = v) } }
                    SwitchRow(Icons.Filled.Savings, alerts, "Budget alerts", "When spending nears a budget", s.budgetAlerts) { v -> update { it.copy(budgetAlerts = v) } }
                    SwitchRow(Icons.Filled.DoneAll, alerts, "Delivery confirmations", "Tell me when a message is delivered", s.deliveryNotifications) { v -> update { it.copy(deliveryNotifications = v) } }
                    SwitchRow(Icons.Filled.VolumeUp, alerts, "Send sound", "Play a short tone after sending", s.sendSound) { v -> update { it.copy(sendSound = v) } }
                    SwitchRow(Icons.Filled.CleaningServices, alerts, "Weekly spam review", "A reminder every Sunday morning to look through spam and delete it", s.spamReviewReminder) { v -> update { it.copy(spamReviewReminder = v) } }
                    SwitchRow(Icons.Filled.Person, alerts, "Alert only for contacts", "No notifications from senders that are not in your contacts. Their messages still arrive and are sorted.", s.notifyContactsOnly) { v -> update { it.copy(notifyContactsOnly = v) } }
                }
            }

            if (group == "media") {
                val media = SettingsAccent.Media
                SettingsGroup("Multimedia messages (MMS)", Icons.Filled.PermMedia, media) {
                    SwitchRow(Icons.Filled.Merge, media, "Keep texts and pictures together", "On, SMS and MMS from the same sender share one thread; off, they stay in separate threads. Inbox categories follow the choice.", s.groupSmsAndMms) { v ->
                        scope.launch {
                            c.settings.update { it.copy(groupSmsAndMms = v) }
                            launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.repository.maintainGrouping() } }
                        }
                    }
                    SwitchRow(Icons.Filled.CloudDownload, media, "Download automatically", "Fetch pictures and videos as soon as they arrive", s.autoDownloadMms) { v -> update { it.copy(autoDownloadMms = v) } }
                    SwitchRow(Icons.Filled.Public, media, "Download while roaming", "May cost extra when you are away from your network", s.downloadMmsWhileRoaming, enabled = s.autoDownloadMms) { v -> update { it.copy(downloadMmsWhileRoaming = v) } }
                    SwitchRow(Icons.Filled.Description, media, "Send long texts as MMS", "One multimedia message instead of a chain of text parts", s.longTextToMms) { v -> update { it.copy(longTextToMms = v) } }
                    NavRow(Icons.Filled.Tune, media, "Convert after", if (s.longTextSegments == 0) "Carrier default" else "More than ${s.longTextSegments} text parts", enabled = s.longTextToMms) { segmentsDialog = true }
                    NavRow(Icons.Filled.Smartphone, media, "My phone number", s.ownNumber.ifBlank { "Detected automatically" }) { numberDialog = true }
                }
            }

            if (group == "search") {
                val search = SettingsAccent.Search
                SettingsGroup("Search", Icons.Filled.Search, search) {
                    val smart by remember { c.smartSearchStatus() }.collectAsStateWithLifecycle(SmartSearchStatus())
                    SwitchRow(Icons.Filled.Psychology, search, "Search by meaning", smartSearchSummary(smart), s.smartSearch) { on ->
                        scope.launch {
                            c.settings.update { it.copy(smartSearch = on) }
                            if (on) {
                                Workers.scheduleEmbedding(context, immediately = true)
                            } else {
                                Workers.cancelEmbedding(context)
                                c.semanticIndex.clear()
                                c.embedder.close()
                            }
                        }
                    }
                }
            }

            if (group == "writing") WritingAssistantSection(s)

            if (group == "translation") TranslationSection(s)

            if (group == "codes") {
                val codes = SettingsAccent.Codes
                SettingsGroup("One-time codes", Icons.Filled.Password, codes) {
                    SwitchRow(Icons.Filled.AutoDelete, codes, "Auto-delete codes", "Remove code messages after they are used or old", s.otpAutoDelete) { v -> update { it.copy(otpAutoDelete = v) } }
                    NavRow(Icons.Filled.Timer, codes, "Delete after", "${s.otpAutoDeleteHours} hours", enabled = s.otpAutoDelete) { hoursDialog = true }
                    SwitchRow(Icons.Filled.ContentCopy, codes, "Delete after copying", "Remove the message as soon as you copy its code", s.otpDeleteAfterCopy) { v -> update { it.copy(otpDeleteAfterCopy = v) } }
                }
            }

            if (group == "organizer") {
                val organizer = SettingsAccent.Organizer
                SettingsGroup("Organizer", Icons.Filled.Category, organizer) {
                    NavRow(Icons.Filled.ViewList, organizer, "Inbox categories", "Choose which categories show in the inbox, their order, and which may notify you", chevron = true, onClick = onCategoryOrder)
                    NavRow(Icons.Filled.Label, organizer, "Custom categories", "Create your own inbox categories", chevron = true, onClick = onCategories)
                    NavRow(Icons.Filled.FilterAlt, organizer, "Rules", "Always file a sender or phrase under a category", chevron = true, onClick = onRules)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedButton(onClick = { org.reapply() }, enabled = !reapplying) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Re-organize all messages")
                        }
                        if (reapplying) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }

            if (group == "security") SecuritySection(s)

            if (group == "data") {
                val data = SettingsAccent.Data
                SettingsGroup("Data", Icons.Filled.Storage, data) {
                    NavRow(Icons.Filled.PrivacyTip, data, "Private by design", "Everything is processed on this phone. aismo has no internet permission, so nothing is ever sent anywhere.", chevron = true, onClick = onPrivacy)
                    NavRow(Icons.Filled.Backup, data, "Backup and restore", "Encrypted copy of your messages and settings", chevron = true, onClick = onBackup)
                }
            }

            if (group == "help") {
                val about = SettingsAccent.About
                SettingsGroup("Help and about", Icons.Filled.Info, about) {
                    NavRow(Icons.AutoMirrored.Filled.HelpOutline, about, "Help and FAQ", "How to do things, and what to try when something does not work", chevron = true, onClick = onHelp)
                    NavRow(Icons.Filled.Info, about, "About aismo", "Version ${versionLabel(context)}, read me, privacy and licences", chevron = true, onClick = onAbout)
                }
            }
        }
    }

    if (holdDialog) {
        ChoiceDialog(
            "Hold before sending", SendDelay.OPTIONS.map(SendDelay::label), SendDelay.OPTIONS.indexOf(s.sendDelaySeconds).coerceAtLeast(0),
            { i -> update { it.copy(sendDelaySeconds = SendDelay.OPTIONS[i]) } },
        ) { holdDialog = false }
    }
    if (hoursDialog) {
        val options = listOf(1, 6, 12, 24, 48, 72)
        ChoiceDialog("Delete codes after", options.map { "$it hours" }, options.indexOf(s.otpAutoDeleteHours), { i -> update { it.copy(otpAutoDeleteHours = options[i]) } }) { hoursDialog = false }
    }
    if (segmentsDialog) {
        val options = listOf(0, 2, 3, 4, 5, 6, 8, 10)
        ChoiceDialog(
            "Convert long texts after",
            options.map { if (it == 0) "Carrier default (or 4 parts)" else "More than $it parts (about ${it * 153} characters)" },
            options.indexOf(s.longTextSegments).coerceAtLeast(0),
            { i -> update { it.copy(longTextSegments = options[i]) } },
        ) { segmentsDialog = false }
    }
    if (numberDialog) {
        var number by remember { mutableStateOf(s.ownNumber) }
        AlertDialog(
            onDismissRequest = { numberDialog = false },
            title = { Text("My phone number") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = number, onValueChange = { number = it }, label = { Text("Phone number") }, singleLine = true)
                    Text("Used to tell you apart from the other people in a group message. Leave blank to detect it from your SIM.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { update { it.copy(ownNumber = number.trim()) }; numberDialog = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { numberDialog = false }) { Text("Cancel") } },
        )
    }
    if (simDialog) SimPickerDialog("Default SIM", sims, s.defaultSubId, allowSystemDefault = true, onPick = { id -> update { it.copy(defaultSubId = id) } }) { simDialog = false }
}

private fun sendHoldSummary(seconds: Int): String =
    if (seconds <= 0) "Off. A message is sent as soon as you tap Send."
    else "Held for $seconds seconds after you tap Send, so you can undo it or send it now. Press and hold Send to skip the wait. Leaving the app sends it straight away."

/** "Ocean · Dark · Pure black": the current look in a few words. */
private fun appearanceSummary(s: AppSettings): String = buildList {
    add(s.palette.label)
    add(when (s.theme) { ThemeMode.SYSTEM -> "Follows system"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" })
    if (s.pureBlack) add("Pure black")
}.joinToString(" · ")

private fun smartSearchSummary(s: SmartSearchStatus): String = when {
    !s.enabled -> "Find messages by what they mean, on this phone"
    s.problem != null -> "Not available: ${s.problem}"
    s.reading -> "Reading your messages in the background: ${s.indexed} of ${s.searchable}"
    s.searchable > 0 -> "Ready. Finds messages by what they mean, on this phone. ${s.searchable} messages searchable."
    else -> "Finds messages by what they mean, on this phone"
}

@Composable
internal fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    /** The one entry that fits this phone best: a sparkles mark and the accent colour, so it is picked out at a glance. */
    recommended: Int? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, label ->
                    val isRecommended = i == recommended
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(i); onDismiss() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = i == selected, onClick = { onPick(i); onDismiss() })
                        if (isRecommended) {
                            Icon(Icons.Filled.AutoAwesome, "Recommended for this phone", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(label, color = if (isRecommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The categories of the inbox bar: which ones are listed, in what order, whether each may raise notifications, and
 * which are secured. Reordering moves one place at a time with the arrows, which also works with a screen reader.
 * A category that is not listed keeps its messages, but they no longer appear under All; a secured category stays in
 * the bar with a padlock and its messages only open after the fingerprint or the PIN. The two are independent: a
 * category may be both hidden and locked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryOrderScreen(onBack: () -> Unit) {
    val vm = containerViewModel { OrganizerViewModel(it) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val custom by vm.categories.collectAsStateWithLifecycle()
    val c = LocalContext.current.container
    val unlocked by c.security.unlocked.collectAsStateWithLifecycle()
    val methods = remember(s) { c.security.availableMethods(s) }
    var unlocking by remember { mutableStateOf(false) }
    // The enablement menu asks for the fingerprint or the PIN; while the window runs, the page re-opens itself.
    val gated = methods.isNotEmpty() && !unlocked
    val labels = remember(custom) { SmsCategory.entries.associate { it.key to it.label } + custom.associate { it.key to it.name } }
    val order = remember(s.categoryOrder, custom) { CategoryOrder.arrange(s.categoryOrder, CategoryOrder.DEFAULT + custom.map { it.key }) }
    SimpleScaffold("Inbox categories", onBack) { padding ->
        if (gated) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("This page is locked", style = MaterialTheme.typography.titleMedium)
                    Text("Unlock with your fingerprint or your PIN to manage the categories.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { unlocking = true }) { Text("Unlock") }
                }
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item {
                    Text(
                        "Choose which categories are listed along the bottom of the inbox and in what order. A category you hide keeps its messages, but they no longer appear under All. " +
                            "Turn alerts off for a category and its messages never raise a notification; they still arrive. " +
                            "Lock a category and it stays in the bar with a padlock: its messages only open after your fingerprint or your PIN, for the window you choose. " +
                            "Hiding and locking are separate, so a category can be both.",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                itemsIndexed(order, key = { _, key -> key }) { index, key ->
                    val name = labels[key] ?: key
                    val listed = key !in s.hiddenCategories
                    val notifies = key !in s.mutedCategories
                    val secured = key in s.securedCategories
                    ListItem(
                        leadingContent = { CategoryBadge(key) },
                        headlineContent = { Text(name, fontWeight = FontWeight.Medium) },
                        supportingContent = {
                            // The chips do not all fit in a row on a phone, so the row scrolls instead of squeezing one.
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = listed, onClick = { vm.setCategoryHidden(key, hidden = listed) },
                                    label = { Text(if (listed) "In inbox" else "Hidden") },
                                    leadingIcon = { Icon(if (listed) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    modifier = Modifier.semantics { contentDescription = "Show $name in the inbox" },
                                )
                                FilterChip(
                                    selected = notifies, onClick = { vm.setCategoryNotifications(key, !notifies) },
                                    label = { Text(if (notifies) "Alerts on" else "Alerts off") },
                                    leadingIcon = { Icon(if (notifies) Icons.Filled.Notifications else Icons.Filled.NotificationsOff, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    modifier = Modifier.semantics { contentDescription = "Notifications for $name" },
                                )
                                FilterChip(
                                    selected = secured, onClick = { vm.setCategorySecured(key, !secured) },
                                    label = { Text(if (secured) "Secured" else "Not secured") },
                                    leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    modifier = Modifier.semantics { contentDescription = "Lock $name behind the PIN or the fingerprint" },
                                )
                            }
                        },
                        trailingContent = {
                            Column {
                                IconButton(enabled = index > 0, onClick = { vm.moveCategory(order, key, -1) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.ArrowUpward, "Move $name up") }
                                IconButton(enabled = index < order.lastIndex, onClick = { vm.moveCategory(order, key, 1) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Filled.ArrowDownward, "Move $name down") }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    if (unlocking) {
        SecurityPromptDialog(onUnlock = { unlocking = false }, onDismiss = { unlocking = false })
    }
}

@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val vm = containerViewModel { OrganizerViewModel(it) }
    val categories by vm.categories.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CustomCategoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    SimpleScaffold("Custom categories", onBack, actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add category") } }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (categories.isEmpty()) item {
                Text("Add a category such as Subscriptions or Family, pick an icon for it, then file conversations into it from the thread menu.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(categories, key = { it.key }) { cat ->
                ListItem(
                    leadingContent = { CategoryBadge(cat.key) },
                    headlineContent = { Text(cat.name) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { editing = cat }) { Icon(Icons.Filled.Edit, "Edit ${cat.name}") }
                            IconButton(onClick = { vm.deleteCategory(cat.key) }) { Icon(Icons.Filled.Delete, "Delete ${cat.name}") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    if (adding) CategoryDialog("New category", "", CategoryIconKeys.DEFAULT, { name, icon -> vm.addCategory(name, icon) }) { adding = false }
    editing?.let { cat -> CategoryDialog("Edit category", cat.name, cat.icon, { name, icon -> vm.updateCategory(cat, name, icon) }) { editing = null } }
}

/** A name and an icon from the fixed set; a new category starts on the plain tag. */
@Composable
private fun CategoryDialog(title: String, initialName: String, initialIcon: String, onSave: (name: String, icon: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var icon by remember { mutableStateOf(initialIcon.takeIf { it in CategoryIconKeys.ALL } ?: CategoryIconKeys.DEFAULT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Text("Icon", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), modifier = Modifier.heightIn(max = 240.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(CategoryIconChoices, key = { it.key }) { choice ->
                        val chosen = choice.key == icon
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .border(if (chosen) 2.dp else 0.dp, if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                .selectable(selected = chosen, role = Role.RadioButton, onClick = { icon = choice.key })
                                .semantics { contentDescription = choice.label },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(choice.icon, contentDescription = null, tint = if (chosen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, icon); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RulesScreen(onBack: () -> Unit) {
    val vm = containerViewModel { OrganizerViewModel(it) }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val labels = remember(categories) { categories.associate { it.key to it.name } }
    SimpleScaffold("Rules", onBack, actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, "Add rule") } }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (rules.isEmpty()) item {
                Text("A rule files matching messages under a category, whatever the automatic parser thinks.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rules, key = { it.id }) { r ->
                ListItem(
                    headlineContent = { Text(r.name, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(listOfNotNull(r.senderContains?.let { "Sender contains \"$it\"" }, r.bodyContains?.let { "Text contains \"$it\"" }, "→ " + categoryLabel(r.categoryKey, labels)).joinToString(" · "))
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = r.isEnabled, onCheckedChange = { vm.toggleRule(r) })
                            IconButton(onClick = { vm.deleteRule(r.id) }) { Icon(Icons.Filled.Delete, "Delete rule") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    if (adding) RuleDialog(categories.map { it.key to it.name }, { vm.addRule(it) }) { adding = false }
}

@Composable
private fun RuleDialog(custom: List<Pair<String, String>>, onSave: (UserRuleEntity) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var sender by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(SmsCategory.PROMO.key) }
    var menu by remember { mutableStateOf(false) }
    val all = SmsCategory.entries.map { it.key to it.label } + custom
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New rule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = sender, onValueChange = { sender = it }, label = { Text("Sender contains") }, singleLine = true)
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Text contains") }, singleLine = true)
                OutlinedTextField(value = merchant, onValueChange = { merchant = it }, label = { Text("Show as (optional)") }, singleLine = true)
                Box { OutlinedButton(onClick = { menu = true }) { Text("Category: " + (all.firstOrNull { it.first == category }?.second ?: "Other")) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        all.forEach { (k, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { category = k; menu = false }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (sender.isNotBlank() || body.isNotBlank()),
                onClick = {
                    onSave(UserRuleEntity(name = name.trim(), senderContains = sender.trim().ifEmpty { null }, bodyContains = body.trim().ifEmpty { null }, categoryKey = category, merchant = merchant.trim().ifEmpty { null }))
                    onDismiss()
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Box(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Box(content = { content() })
