package aismo.sms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import aismo.sms.R
import aismo.sms.container
import aismo.sms.data.db.MessageEntity
import aismo.sms.data.SendOutcome
import aismo.sms.data.SendRoute
import aismo.sms.domain.mms.ConversionRules
import aismo.sms.data.mms.OutgoingAttachment
import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.SimChoice

/** Common scaffold: a titled top bar with a back arrow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleScaffold(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { actions() },
            )
        },
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(initialTo: String, initialBody: String, onBack: () -> Unit, onOpenThread: (key: String, address: String) -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val c = context.container
    val scope = rememberCoroutineScope()
    var to by remember { mutableStateOf(initialTo) }
    var body by remember { mutableStateOf(initialBody) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val attachments = remember { mutableStateListOf<OutgoingAttachment>() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (initialTo.isBlank()) focus.requestFocus() }
    val snackbar = remember { SnackbarHostState() }
    val rephraseEnabled by remember { c.settings.settings.map { it.rephraseEnabled }.distinctUntilChanged() }.collectAsStateWithLifecycle(true)
    val rephrase = rememberRephraseController()

    val picker = rememberAttachmentPicker { uris ->
        scope.launch {
            val room = MAX_ATTACHMENTS - attachments.size
            val chosen = uris.take(maxOf(room, 0))
            if (uris.size > chosen.size) snackbar.showSnackbar("You can attach up to $MAX_ATTACHMENTS files to one message")
            val imported = chosen.mapNotNull { c.attachmentStore.importUri(it) }
            attachments += imported
            // A read failure used to be silent — a picker with an expired grant left the user with nothing and no reason.
            val failed = chosen.size - imported.size
            if (failed > 0) snackbar.showSnackbar(if (imported.isEmpty()) "Couldn't read the selected files. Pick them again." else "Some files couldn't be read")
        }
    }

    // Recipients are comma-separated; suggestions follow the one being typed.
    val typing = to.substringAfterLast(',').trim()
    val suggestions by produceState(emptyList<aismo.sms.data.sms.ContactDirectory.Match>(), typing) {
        value = if (typing.length < 2) emptyList() else { delay(150); withContext(Dispatchers.IO) { c.contacts.search(typing) } }
    }
    // The people the user writes to most, newest first: a quick row under the To field, instead of retyping the number.
    val recents by produceState(emptyList<aismo.sms.data.sms.ContactDirectory.Match>(), Unit) {
        value = withContext(Dispatchers.IO) {
            buildList {
                for (e in c.db.conversations().observeAll().first()) {
                    if (ConversationKeys.isGroup(e.address)) continue
                    if (any { it.number == e.address }) continue
                    add(aismo.sms.data.sms.ContactDirectory.Match(c.contacts.nameFor(e.address) ?: e.address, e.address))
                    if (size >= 10) break
                }
            }
        }
    }
    val recipients = to.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
    val rules by produceState(ConversionRules.OFF) { value = c.outgoing.rulesFor(c.settings.current().defaultSubId) }
    val route = c.outgoing.routeFor(recipients.size, attachments.isNotEmpty(), body, rules)

    // The SIM a new message goes out on: the one picked here, otherwise the app's/system default.
    val sims = remember { c.sims.active() }
    var pickedSubId by remember { mutableStateOf<Int?>(null) }
    val preferredSubId by remember { c.settings.settings.map { it.defaultSubId }.distinctUntilChanged() }.collectAsStateWithLifecycle(-1)
    val effectiveSubId = remember(sims, pickedSubId, preferredSubId) {
        if (sims.size < 2) preferredSubId
        else SimChoice.resolve(pickedSubId, null, preferredSubId, c.sims.systemDefaultSmsSubId(), sims.map { it.subId })
    }
    val currentSim = sims.firstOrNull { it.subId == effectiveSubId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New message") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = to, onValueChange = { to = it; error = null }, label = { Text("To") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focus), isError = error != null,
                supportingText = { Text(error ?: if (recipients.size > 1) "Group message (MMS)" else "Separate several numbers with commas") },
            )
            if (suggestions.isNotEmpty()) {
                Card {
                    Column {
                        suggestions.forEach { s ->
                            ListItem(
                                modifier = Modifier.clickable {
                                    val before = to.substringBeforeLast(',', "").let { if (it.isEmpty()) "" else "$it, " }
                                    to = "$before${s.number}, "
                                },
                                headlineContent = { Text(s.name, maxLines = 1) },
                                supportingContent = { Text(s.number, maxLines = 1) },
                            )
                        }
                    }
                }
            }
            // Shown only while nothing is being typed, so it never competes with the contact suggestions.
            if (typing.isBlank() && recents.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Recent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recents) { m ->
                            RecentContactChip(m) {
                                val before = to.substringBeforeLast(',', "").let { if (it.isEmpty()) "" else "$it, " }
                                to = "$before${m.number}, "
                            }
                        }
                    }
                }
            }
            if (attachments.isNotEmpty()) AttachmentStrip(attachments, onRemove = { attachments.remove(it); c.attachmentStore.discard(it) })
            // The message takes whatever room is left; long texts scroll inside the field instead of shrinking it.
            OutlinedTextField(
                value = body, onValueChange = { body = it }, placeholder = { Text("Message") },
                modifier = Modifier.fillMaxWidth().weight(1f), minLines = 3,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val (parts, left) = smsCounter(body)
                val routeLabel = when {
                    route == SendRoute.MMS_LONG_TEXT -> "Long text · one MMS"
                    route == SendRoute.MMS -> "Sends as MMS"
                    parts > 0 -> "$left left · $parts SMS"
                    else -> ""
                }
                Text(routeLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // Equal weight with the route label, so a narrow screen shrinks the name instead of the row overflowing.
                if (sims.size > 1 && currentSim != null) SimChip(currentSim, sims, { pickedSubId = it }, Modifier.weight(1f, fill = false))
                // With one number in the To field, the draft can be handed to WhatsApp instead.
                if (recipients.size == 1 && ConversationKeys.isPhoneNumber(recipients.first())) {
                    IconButton(onClick = {
                        val opened = context.openWhatsApp(recipients.first(), body)
                        if (!opened) scope.launch { snackbar.showSnackbar("Couldn't open WhatsApp") }
                    }) {
                        Icon(ImageVector.vectorResource(R.drawable.ic_whatsapp), "Open in WhatsApp, with this text", tint = WhatsAppGreen)
                    }
                }
                AttachButton(picker, enabled = !sending) { body = body + it }
                if (rephraseEnabled) RephraseButton(rephrase, body)
                val canSend = !sending && recipients.isNotEmpty() && (body.isNotBlank() || attachments.isNotEmpty())
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant).clickable(enabled = canSend, role = Role.Button, onClick = {
                        if (recipients.any { !ConversationKeys.isPhoneNumber(it) }) { error = "Enter phone numbers or pick contacts"; return@clickable }
                        if (!context.isDefaultSmsApp()) { error = "Set this app as your default SMS app to send"; return@clickable }
                        sending = true
                        scope.launch {
                            val files = attachments.toList()
                            val address = recipients.joinToString(",")
                            // The type of the first message decides the thread: a picture or a converted long text starts
                            // the multimedia conversation, plain text the SMS one; later messages follow the thread they are sent from.
                            // With "keep together" on there is only one thread per sender, so the type no longer splits it.
                            val grouped = c.settings.current().groupSmsAndMms
                            val key = ConversationKeys.of(address, mms = route != SendRoute.SMS && !grouped)
                            val subId = c.simSelector.forConversation(key, pickedSubId)
                            val holdSeconds = c.settings.current().sendDelaySeconds
                            if (holdSeconds > 0) {
                                // The conversation opens with the message waiting there, where it can be undone or sent now.
                                c.sendHold.hold(key, recipients, body, files, subId, restoresDraft = true, delayMs = holdSeconds * 1000L)
                                sending = false
                                onOpenThread(key, address)
                                return@launch
                            }
                            val failure = try {
                                (c.outgoing.send(recipients, body, files, subId, key) as? SendOutcome.Failed)?.reason
                            } catch (e: Exception) {
                                "Couldn't send. Try again." // an unexpected failure must not crash the app
                            }
                            sending = false
                            if (failure != null) { error = failure; return@launch }
                            files.forEach(c.attachmentStore::discard)
                            onOpenThread(key, address)
                        }
                    }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            RephraseSheet(rephrase, body, onUse = { body = it }, onOpenSettings = onOpenSettings)
        }
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpen: (key: String, address: String) -> Unit) {
    val vm = containerViewModel { SearchViewModel(it) }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val smart by vm.smartSearch.collectAsStateWithLifecycle()
    val useSemantic by vm.useSemantic.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SimpleScaffold("Search", onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it }, singleLine = true,
                placeholder = { Text("Search messages, senders, amounts") },
                modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus),
            )
            // The AI half of the search is opt-in per search: until it is ticked, only the word matching runs.
            if (smart.enabled && smart.problem == null) {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(checked = useSemantic, onCheckedChange = { vm.useSemantic.value = it })
                    // A small AI mark by the label, so it reads at a glance which half of the search the model does.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Search similar in meaning", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            smartSearchNote(smart)?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = if (smart.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // What the AI does here, in one line: it is a small model inside the app, and nothing leaves the phone.
            if (smart.enabled && useSemantic && query.isNotBlank()) {
                Text(
                    "AI search: “Similar in meaning” is found by a small model built into aismo, running entirely on this phone. Nothing is sent anywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                query.isBlank() -> Hint(if (smart.enabled) "Type to search all your messages, by words or by meaning." else "Type to search all your messages.")
                results.keyword.isEmpty() && results.similar.isEmpty() && !results.searching -> Hint("No matches for \"$query\".")
                else -> SearchResultsList(results, query, onOpen)
            }
        }
    }
}

@Composable
fun StarredScreen(onBack: () -> Unit, onOpen: (key: String, address: String) -> Unit) {
    val c = LocalContext.current.container
    val starred by c.db.messages().starred().collectAsStateWithLifecycle(emptyList())
    SimpleScaffold("Starred messages", onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (starred.isEmpty()) Hint("Long-press a message and choose Star to keep it here.") else MessageList(starred, "", onOpen)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One line under the search box about search by meaning, or null when there is nothing to say. */
private fun smartSearchNote(s: SmartSearchStatus): String? = when {
    !s.enabled -> null
    s.problem != null -> "Search by meaning is not available: ${s.problem}"
    s.reading -> "Search by meaning is still reading your messages (${s.indexed} of ${s.searchable}). Newer ones are done first."
    else -> null
}

@Composable
private fun MessageList(items: List<MessageEntity>, query: String, onOpen: (String, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { m -> MessageRow(m, query, onOpen) }
    }
}

@Composable
private fun SearchResultsList(results: SearchResults, query: String, onOpen: (String, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(results.keyword, key = { "k${it.id}" }) { m -> MessageRow(m, query, onOpen) }
        if (results.similar.isNotEmpty() || (results.searching && results.keyword.isNotEmpty())) {
            item(key = "similar-header") {
                Text(
                    if (results.keyword.isEmpty()) "No exact matches. Similar in meaning:" else "Similar in meaning",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
        }
        // Messages found by meaning need not contain the words, so there is nothing to highlight.
        items(results.similar, key = { "s${it.id}" }) { m -> MessageRow(m, "", onOpen) }
    }
}

@Composable
private fun MessageRow(m: MessageEntity, query: String, onOpen: (String, String) -> Unit) {
    val title by rememberConversationTitle(m.address, null)
    ListItem(
        modifier = Modifier.clickable { onOpen(m.conversationKey, m.address) },
        headlineContent = { Text(title, maxLines = 1, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(highlight(m.body.replace('\n', ' '), query), maxLines = 3, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.attachmentCount > 0) MmsBadge()
                Text(formatListTime(m.date), style = MaterialTheme.typography.labelSmall)
            }
        },
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

/** One entry of the "Recent" row under the To field: a first letter on a coloured circle, the name under it. */
@Composable
private fun RecentContactChip(contact: aismo.sms.data.sms.ContactDirectory.Match, onClick: () -> Unit) {
    // The circle colour follows the number, so the same person always gets the same one.
    val (bg, on) = when (kotlin.math.abs(contact.number.hashCode()) % 4) {
        0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        1 -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        2 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Column(
        Modifier.width(72.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(contact.name.first().uppercase(), style = MaterialTheme.typography.titleMedium, color = on, maxLines = 1)
        }
        Text(contact.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Bolds each search word where it appears in the snippet. */
private fun highlight(text: String, query: String): AnnotatedString {
    val words = query.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return AnnotatedString(text)
    val regex = Regex(words.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    return buildAnnotatedString {
        var last = 0
        for (m in regex.findAll(text)) {
            append(text.substring(last, m.range.first))
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(m.value); pop()
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}
