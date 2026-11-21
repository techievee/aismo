package aismo.sms.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import aismo.sms.R
import aismo.sms.container
import aismo.sms.data.db.AttachmentEntity
import aismo.sms.data.db.MmsDownloadEntity
import aismo.sms.domain.ConversationKeys
import aismo.sms.data.db.MessageEntity
import aismo.sms.data.db.TranslationEntity
import aismo.sms.domain.SmsCategory
import aismo.sms.domain.parser.TxnKind
import aismo.sms.domain.translate.LanguageGuesser
import aismo.sms.telephony.NotificationCenter
import aismo.sms.ui.theme.categoryColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(key: String, addressHint: String?, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val vm = containerViewModel(key = "thread:$key") { ThreadViewModel(it, key) }
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    val quickReplies by vm.quickReplies.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val subId by vm.selectedSubId.collectAsStateWithLifecycle()
    val sims by vm.sims.collectAsStateWithLifecycle()
    val custom by vm.customCategories.collectAsStateWithLifecycle()
    val customLabels = remember(custom) { custom.associate { it.key to it.name } }

    val address = conversation?.address ?: addressHint ?: messages.firstOrNull()?.address ?: key
    val title by rememberConversationTitle(address, conversation?.title)
    val isGroup = ConversationKeys.isGroup(address)
    val canReply = ConversationKeys.canReply(address)
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val rules by vm.conversionRules.collectAsStateWithLifecycle()
    val rephraseEnabled by vm.rephraseEnabled.collectAsStateWithLifecycle()
    val held by vm.held.collectAsStateWithLifecycle()
    val translations by vm.translations.collectAsStateWithLifecycle()
    val targetLang by vm.translateTargetLang.collectAsStateWithLifecycle()
    val rephrase = rememberRephraseController()
    // The recent messages of the thread, oldest first, so the writing assistant can craft a reply that fits the
    // conversation. The list is newest first, so it is read back into order; each line is capped so a chatty thread
    // cannot push a small model's context window over the edge.
    val rephraseContext = remember(messages) {
        if (messages.isEmpty()) null
        else messages.take(10).reversed().mapNotNull { m ->
            val body = m.body.replace('\n', ' ').trim()
            if (body.isEmpty()) null
            else (if (m.type == Telephony.Sms.MESSAGE_TYPE_INBOX) "Them" else "You") + ": " + body.take(120)
        }.joinToString("\n").takeIf { it.isNotBlank() }
    }
    val translate = rememberTranslateController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openAttachment = rememberAttachmentOpener()
    val saveAttachment = rememberAttachmentSaver()
    val picker = rememberAttachmentPicker(vm::addAttachments)
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); vm.notice.value = null } }
    // Replying is the full-screen composing case too: the status bar gets out of the way, but the navigation bar stays.
    ImmersiveWhileVisible()
    val isDefault = remember { context.isDefaultSmsApp() }

    DisposableEffect(key) {
        vm.onShown()
        onDispose { vm.onHidden() }
    }
    // New mail arriving while the thread is open is read straight away.
    LaunchedEffect(messages.firstOrNull()?.id) { if ((conversation?.unreadCount ?: 0) > 0) vm.onShown() }

    var menuOpen by remember { mutableStateOf(false) }
    var categoryDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var translateTarget by remember { mutableStateOf<MessageEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val sub = when {
                            isGroup -> "${ConversationKeys.participants(address).size} people"
                            title != address -> address
                            else -> conversation?.category?.let { categoryLabel(it, customLabels) }
                        }
                        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (canReply && !isGroup) {
                        IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(address)))) }) {
                            Icon(Icons.Filled.Call, "Call")
                        }
                        IconButton(onClick = {
                            val opened = context.openWhatsApp(address, draft)
                            if (!opened) scope.launch { snackbar.showSnackbar("Couldn't open WhatsApp") }
                        }) {
                            Icon(ImageVector.vectorResource(R.drawable.ic_whatsapp), "Open in WhatsApp", tint = WhatsAppGreen)
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            val c = conversation
                            Item(if (c?.isPinned == true) "Unpin" else "Pin") { menuOpen = false; vm.pin(c?.isPinned != true) }
                            Item(if (c?.isMuted == true) "Unmute notifications" else "Mute notifications") { menuOpen = false; vm.mute(c?.isMuted != true) }
                            Item("Mark as unread") { menuOpen = false; vm.markUnread(); onBack() }
                            Item("Change category") { menuOpen = false; categoryDialog = true }
                            if (!isGroup) Item(if (c?.isBlocked == true) "Unblock sender" else "Block sender") {
                                menuOpen = false
                                if (c?.isBlocked == true) vm.unblock() else { vm.block(); onBack() }
                            }
                            Item("Delete conversation") { menuOpen = false; confirmDelete = true }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val listState = rememberLazyListState()
            LaunchedEffect(listState.layoutInfo.totalItemsCount, listState.canScrollForward) {
                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (messages.size >= 60 && last >= messages.size - 5) vm.loadMore()
            }
            val newestOutgoingIndex = remember(messages) { messages.indexOfFirst { it.type != Telephony.Sms.MESSAGE_TYPE_INBOX } }
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(messages, key = { _, m -> m.id }) { index, m ->
                    Column {
                        MessageBubble(
                            m = m,
                            isNewestOutgoing = index == newestOutgoingIndex,
                            otp = facts.otps[m.id]?.code,
                            transaction = facts.transactions[m.id],
                            attachments = facts.attachments[m.id].orEmpty(),
                            translations = translations[m.id].orEmpty(),
                            download = facts.downloads[m.id],
                            onOpenAttachment = openAttachment,
                            onDownload = { vm.download(m.id) },
                            onSendAsText = if (m.isMms && m.type == Telephony.Sms.MESSAGE_TYPE_FAILED && m.attachmentCount == 0 && !isGroup && m.body.isNotBlank()) { { vm.resendAsText(m.id) } } else null,
                            onLongClick = { actionTarget = m },
                            onCopyOtp = { code ->
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                                Toast.makeText(context, "Code copied", Toast.LENGTH_SHORT).show()
                                vm.markOtpUsed(m.id)
                            },
                            onRetry = { vm.resend(m.id) },
                        )
                    }
                    if (index == messages.lastIndex || !sameDay(m.date, messages[index + 1].date)) DayHeader(formatDay(m.date))
                }
            }

            held?.let { HeldSendBanner(it, onUndo = vm::undoHeld, onSendNow = vm::sendHeldNow) }

            if (quickReplies.isNotEmpty() && canReply) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickReplies) { reply -> SuggestionChip(onClick = { vm.send(address, quickReply = reply) }, label = { Text(reply) }) }
                }
            }

            when {
                !canReply -> Text(
                    "This sender doesn't accept replies.",
                    Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !isDefault -> Text(
                    "Set aismo as your default SMS app to reply.",
                    Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Composer(
                    text = draft,
                    onText = { vm.draft.value = it },
                    attachments = attachments,
                    onRemoveAttachment = vm::removeAttachment,
                    picker = picker,
                    route = vm.routeFor(address, draft, attachments.isNotEmpty(), rules),
                    sims = sims,
                    selectedSubId = subId,
                    onSim = vm::chooseSim,
                    onSend = { vm.send(address) },
                    onSendNow = { vm.send(address, immediate = true) },
                    rephrase = rephrase.takeIf { rephraseEnabled },
                )
            }
            RephraseSheet(rephrase, draft, context = rephraseContext, onUse = { vm.draft.value = it }, onOpenSettings = onOpenSettings)
        }
    }

    actionTarget?.let { m ->
        MessageActions(
            m = m,
            attachments = facts.attachments[m.id].orEmpty(),
            onSaveAttachment = saveAttachment,
            onDismiss = { actionTarget = null },
            onCopy = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("message", m.body))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            },
            onStar = { vm.star(m.id, !m.isStarred) },
            onDelete = { vm.delete(listOf(m.id)) },
            onRetry = { vm.resend(m.id) },
            onTranslate = if (m.body.isNotBlank()) { { translate.open(); actionTarget = null; translateTarget = m } } else null,
        )
    }

    translateTarget?.let { m ->
        TranslateSheet(
            text = m.body,
            controller = translate,
            defaultFrom = LanguageGuesser.guess(m.body),
            defaultTo = targetLang,
            onTranslated = { from, to, text -> vm.saveTranslation(m.id, from, to, text) },
            onOpenSettings = { translateTarget = null; onOpenSettings() },
            onDismiss = { translateTarget = null },
        )
    }

    if (categoryDialog) {
        CategoryDialog(
            current = conversation?.category, locked = conversation?.categoryLocked == true,
            custom = custom.map { it.key to it.name },
            onPick = { vm.setCategory(it); categoryDialog = false },
            onAuto = { vm.unlock(); categoryDialog = false },
            onDismiss = { categoryDialog = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("All its messages are removed from your phone and can't be recovered.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteConversation(); onBack() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Item(text: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(text) }, onClick = onClick)

@Composable
private fun DayHeader(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    m: MessageEntity,
    isNewestOutgoing: Boolean,
    otp: String?,
    transaction: aismo.sms.data.db.TransactionEntity?,
    attachments: List<AttachmentEntity>,
    translations: List<TranslationEntity>,
    download: MmsDownloadEntity?,
    onOpenAttachment: (AttachmentEntity) -> Unit,
    onDownload: () -> Unit,
    onSendAsText: (() -> Unit)?,
    onLongClick: () -> Unit,
    onCopyOtp: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val incoming = m.type == Telephony.Sms.MESSAGE_TYPE_INBOX
    val failed = m.type == Telephony.Sms.MESSAGE_TYPE_FAILED
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (incoming) Alignment.Start else Alignment.End) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = when {
                failed -> MaterialTheme.colorScheme.errorContainer
                incoming -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            modifier = Modifier.widthIn(max = 300.dp).combinedClickable(onClick = {}, onLongClick = onLongClick),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (download != null) {
                    PendingMmsContent(m, download, onDownload)
                } else {
                    m.subject?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    if (attachments.isNotEmpty()) AttachmentContent(attachments, onOpenAttachment)
                    val text = if (m.subject != null && m.body.startsWith(m.subject + "\n")) m.body.removePrefix(m.subject + "\n") else if (m.body == m.subject) "" else m.body
                    if (text.isNotBlank()) Text(linkified(text), style = MaterialTheme.typography.bodyLarge)
                    TranslationBlock(translations)
                }
                if (otp != null) AssistChip(onClick = { onCopyOtp(otp) }, label = { Text("Copy code $otp") })
                transaction?.let { t ->
                    if (t.kind != TxnKind.OTHER.name) {
                        val sign = if (t.kind == TxnKind.DEBIT.name) "−" else "+"
                        Text(
                            sign + NotificationCenter.formatMoney(t.amountMinor, t.currency) + (t.merchant?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (t.kind == TxnKind.DEBIT.name) MaterialTheme.colorScheme.error else categoryColor(SmsCategory.FINANCE.key),
                        )
                    }
                }
                Text(
                    formatTime(m.date) + (if (m.isStarred) "  ★" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val status = when {
            failed -> "Failed to send"
            m.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "Sending…"
            m.type == Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Queued"
            !incoming && isNewestOutgoing -> if (m.status == Telephony.Sms.STATUS_COMPLETE) "Delivered" else "Sent"
            else -> null
        }
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (failed || m.type == Telephony.Sms.MESSAGE_TYPE_QUEUED) TextButton(onClick = onRetry) { Text("Retry") }
                if (failed && onSendAsText != null) TextButton(onClick = onSendAsText) { Text("Send as text") }
            }
        }
    }
}

private val urlRegex = Regex("(https?://|www\\.)[^\\s]+", RegexOption.IGNORE_CASE)

private fun linkified(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in urlRegex.findAll(text)) {
        append(text.substring(last, match.range.first))
        val url = match.value.trimEnd('.', ',', ')', ';')
        val target = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"
        withLink(LinkAnnotation.Url(target)) {
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(url) }
        }
        append(match.value.substring(url.length))
        last = match.range.last + 1
    }
    append(text.substring(last))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    attachments: List<aismo.sms.data.mms.OutgoingAttachment>,
    onRemoveAttachment: (aismo.sms.data.mms.OutgoingAttachment) -> Unit,
    picker: AttachmentPicker,
    route: aismo.sms.data.SendRoute,
    sims: List<aismo.sms.data.sms.SimInfo>,
    selectedSubId: Int,
    onSim: (Int) -> Unit,
    onSend: () -> Unit,
    onSendNow: () -> Unit,
    rephrase: RephraseController?,
) {
    val (segments, remaining) = smsCounter(text)
    Column {
        if (attachments.isNotEmpty()) AttachmentStrip(attachments, onRemoveAttachment)
        // The field gets a row to itself, so it never competes with the buttons for width.
        OutlinedTextField(
            value = text, onValueChange = onText,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 5,
            placeholder = { Text("Message") },
            supportingText = when {
                route == aismo.sms.data.SendRoute.MMS_LONG_TEXT -> { { Text("Long text: sends as one MMS · $segments SMS parts avoided") } }
                route == aismo.sms.data.SendRoute.MMS -> { { Text("Sends as MMS") } }
                segments > 1 || remaining < 20 && text.isNotEmpty() -> { { Text("$remaining left · $segments SMS") } }
                else -> null
            },
        )
        // The controls share a row of their own under the field.
        BoxWithConstraints {
            // The chip with its number plus every button and the padding needs about 326dp of row; on
            // anything narrower only the icon, so the row never overflows.
            val named = maxWidth >= 326.dp
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (sims.size > 1) {
                    val current = sims.firstOrNull { it.subId == selectedSubId } ?: sims.first()
                    if (named) SimChip(current, sims, onSim)
                    else SimIconButton(current, sims, onSim)
                }
                AttachButton(picker, onPaste = { onText(text + it) })
                if (rephrase != null) RephraseButton(rephrase, text)
                Spacer(Modifier.weight(1f))
                // Tap to send (after the hold, if one is set in Settings); press and hold to send straight away.
                val canSend = text.isNotBlank() || attachments.isNotEmpty()
                val haptics = LocalHapticFeedback.current
                Box(
                    Modifier.size(48.dp).clip(CircleShape).combinedClickable(
                        enabled = canSend, role = Role.Button, onClick = onSend,
                        onLongClickLabel = "Send now, without waiting", onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSendNow() },
                    ),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = LocalContentColor.current.copy(alpha = if (canSend) 1f else 0.38f)) }
            }
        }
    }
}

@Composable
private fun MessageActions(
    m: MessageEntity,
    attachments: List<AttachmentEntity>,
    onSaveAttachment: (AttachmentEntity) -> Unit,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onStar: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onTranslate: (() -> Unit)?,
) {
    var details by remember { mutableStateOf(false) }
    if (details) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Message details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Address: ${m.address}")
                    Text("Time: ${formatDateTime(m.date)}")
                    Text("Type: " + (if (m.isMms) "MMS " else "SMS ") + when (m.type) { 1 -> "received"; 2 -> "sent"; 4 -> "sending"; 5 -> "failed"; 6 -> "queued"; else -> "other" })
                    m.subject?.let { Text("Subject: $it") }
                    Text("Category: ${SmsCategory.fromKey(m.category)?.label ?: "Custom"}")
                    if (m.subId >= 0) Text("SIM subscription: ${m.subId}")
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Message") },
        text = {
            Column {
                if (m.body.isNotBlank()) ActionRow("Copy text") { onCopy(); onDismiss() }
                onTranslate?.let { t -> ActionRow("Translate") { t(); onDismiss() } }
                attachments.forEach { a -> ActionRow("Save ${a.name ?: a.kind}") { onSaveAttachment(a); onDismiss() } }
                ActionRow(if (m.isStarred) "Remove star" else "Star") { onStar(); onDismiss() }
                if (m.type == Telephony.Sms.MESSAGE_TYPE_FAILED) ActionRow("Retry sending") { onRetry(); onDismiss() }
                ActionRow("Details") { details = true }
                ActionRow("Delete") { onDelete(); onDismiss() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun CategoryDialog(
    current: String?,
    locked: Boolean,
    custom: List<Pair<String, String>>,
    onPick: (String) -> Unit,
    onAuto: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Category") },
        text = {
            LazyColumn {
                item {
                    Row(Modifier.fillMaxWidth().clickable(onClick = onAuto), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = !locked, onClick = onAuto)
                        Text("Automatic")
                    }
                }
                items(SmsCategory.entries.map { it.key to it.label } + custom, key = { it.first }) { (key, label) ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(key) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = locked && current == key, onClick = { onPick(key) })
                        CategoryBadge(key, size = 28.dp)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The body of an MMS that has been announced but not downloaded: its state and a way to fetch it. */
@Composable
private fun PendingMmsContent(m: MessageEntity, d: MmsDownloadEntity, onDownload: () -> Unit) {
    val expired = d.expiresAt != null && d.expiresAt < System.currentTimeMillis()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(m.subject ?: "Multimedia message", style = MaterialTheme.typography.titleSmall)
        Text(
            listOfNotNull("MMS", formatSize(d.sizeBytes).ifEmpty { null }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            expired -> Text("This message has expired and can no longer be downloaded.", style = MaterialTheme.typography.bodyMedium)
            d.state == MmsDownloadEntity.DOWNLOADING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Downloading…", style = MaterialTheme.typography.bodyMedium)
            }
            d.state == MmsDownloadEntity.FAILED -> {
                Text("Download failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onDownload) { Text("Retry") }
            }
            else -> Button(onClick = onDownload) { Text("Download") }
        }
    }
}
