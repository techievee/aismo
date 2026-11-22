package aismo.sms.ui

import android.provider.Telephony
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import android.net.Uri
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import aismo.sms.AppContainer
import aismo.sms.data.HeldMessage
import aismo.sms.data.db.AttachmentEntity
import aismo.sms.data.db.BudgetEntity
import aismo.sms.data.db.ConversationEntity
import aismo.sms.data.db.CustomCategoryEntity
import aismo.sms.data.db.MessageEntity
import aismo.sms.data.db.MessageIds
import aismo.sms.data.db.MmsDownloadEntity
import aismo.sms.data.db.OtpEntity
import aismo.sms.data.db.TransactionEntity
import aismo.sms.data.db.TranslationEntity
import aismo.sms.data.db.UserRuleEntity
import aismo.sms.data.SendOutcome
import aismo.sms.data.SendRoute
import aismo.sms.data.mms.OutgoingAttachment
import aismo.sms.data.sms.SimInfo
import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.SimChoice
import aismo.sms.data.settings.AppSettings
import aismo.sms.domain.CategoryOrder
import aismo.sms.domain.mms.ConversionRules
import aismo.sms.domain.MonthSummary
import aismo.sms.domain.SmsCategory
import aismo.sms.domain.translate.Language
import aismo.sms.domain.translate.defaultTarget
import aismo.sms.telephony.ActiveConversation
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

private const val STOP_TIMEOUT = 5_000L

class InboxViewModel(private val c: AppContainer) : ViewModel() {
    /** Null means the "All" view. */
    val selectedCategory = MutableStateFlow<String?>(null)
    val importProgress = MutableStateFlow<Int?>(null)

    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)
    val customCategories: StateFlow<List<CustomCategoryEntity>> =
        c.db.config().observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
    val stats = c.db.conversations().observeCategoryStats()
        .map { list -> list.associateBy { it.category } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyMap())
    val securityUnlocked = c.security.unlocked

    /** What the All view shows, so the header count and the list can never disagree. */
    val unreadTotal = combine(stats, settings, c.security.unlocked) { st, s, unlocked ->
        st.values.filter {
            val s2 = s ?: return@filter false
            CategoryOrder.inAll(it.category, s2.hiddenCategories, s2.securedCategories, unlocked, s2.includeSpamInAll)
        }.sumOf { it.unread }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val conversations: StateFlow<List<ConversationEntity>> = combine(selectedCategory, c.settings.settings, c.security.unlocked) { cat, s, unlocked -> Triple(cat, s, unlocked) }
        .flatMapLatest { (cat, s, unlocked) ->
            if (cat == null) c.db.conversations().observeAll().map { list -> list.filter { CategoryOrder.inAll(it.category, s.hiddenCategories, s.securedCategories, unlocked, s.includeSpamInAll) } }
            else c.db.conversations().observeByCategory(cat)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    fun select(category: String?) { selectedCategory.value = category }

    init {
        // A notification can ask for a category (the weekly spam review opens Spam); take the request once. A request
        // for a locked category is dropped back to All: its messages stay hidden until it is unlocked.
        viewModelScope.launch {
            c.requestedCategory.collect { requested ->
                if (requested != null) {
                    val s = c.settings.current()
                    if (c.security.isLocked(s, requested)) Unit else selectedCategory.value = requested
                    c.requestedCategory.value = null
                }
            }
        }
    }

    /** First run (or after permission is granted): import the provider into the local index. */
    fun importIfNeeded() {
        if (importProgress.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            importProgress.value = 0
            runCatching { c.repository.syncNew { importProgress.value = it } }
            runCatching { c.repository.migrateMmsKeys() }
            runCatching { c.repository.maintainGrouping() }
            runCatching { c.repository.reparseStale() }
            importProgress.value = null
        }
    }

    fun markRead(key: String) = viewModelScope.launch { c.repository.markConversationRead(key); c.notifications.cancelConversation(key) }
    fun markUnread(key: String) = viewModelScope.launch { c.repository.markConversationUnread(key) }
    fun delete(key: String) = viewModelScope.launch { c.repository.deleteConversation(key) }
    fun pin(key: String, pinned: Boolean) = viewModelScope.launch { c.repository.setPinned(key, pinned) }
    fun mute(key: String, muted: Boolean) = viewModelScope.launch { c.repository.setMuted(key, muted) }
    fun block(key: String) = viewModelScope.launch { c.repository.blockSender(key) }
    fun setCategory(key: String, category: String) = viewModelScope.launch { c.repository.setConversationCategory(key, category) }
    /** Marks every given conversation read and clears its notification, like [markRead] but for a batch. */
    fun markAllRead(keys: List<String>) = viewModelScope.launch { keys.forEach { c.repository.markConversationRead(it); c.notifications.cancelConversation(it) } }
    fun deleteAll(keys: List<String>) = viewModelScope.launch { keys.forEach { c.repository.deleteConversation(it) } }
}

data class ThreadFacts(
    val otps: Map<Long, OtpEntity> = emptyMap(),
    val transactions: Map<Long, TransactionEntity> = emptyMap(),
    val attachments: Map<Long, List<AttachmentEntity>> = emptyMap(),
    val downloads: Map<Long, MmsDownloadEntity> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModel(private val c: AppContainer, val key: String) : ViewModel() {
    private val limit = MutableStateFlow(PAGE)
    val draft = MutableStateFlow("")
    val sims = MutableStateFlow<List<SimInfo>>(emptyList())
    private val pickedSubId = MutableStateFlow<Int?>(null)

    /**
     * The SIM the next message goes out on: the one picked here, otherwise the SIM this conversation last used, so a
     * thread that was on SIM 2 stays on SIM 2, otherwise the app's or the system's default.
     */
    val selectedSubId: StateFlow<Int> = combine(
        pickedSubId, c.db.messages().observeLastSubId(key), c.settings.settings.map { it.defaultSubId }.distinctUntilChanged(),
    ) { picked, thread, preferred ->
        SimChoice.resolve(picked, thread, preferred, c.sims.systemDefaultSmsSubId(), sims.value.map { it.subId })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, -1)

    fun chooseSim(subId: Int) { pickedSubId.value = subId }

    /** Whether the composer shows the rewrite button. */
    val rephraseEnabled: StateFlow<Boolean> = c.settings.settings.map { it.rephraseEnabled }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), true)

    /** How many of the thread's most recent messages the rewrite is shown as context; 0 passes only the draft. */
    val rephraseContextMessages: StateFlow<Int> = c.settings.settings.map { it.rephraseContextMessages }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), 10)

    /** The language messages are translated into: what the user picked, or the phone's own when the app knows it. */
    val translateTargetLang: StateFlow<Language> = c.settings.settings
        .map { Language.fromKey(it.translateTargetLang) ?: defaultTarget(Locale.getDefault().language) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), Language.EN)

    val conversation: StateFlow<ConversationEntity?> =
        c.db.conversations().observe(key).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), null)
    val customCategories = c.db.config().observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    /** Newest first; the UI shows them with a reversed list. */
    val messages: StateFlow<List<MessageEntity>> = limit.flatMapLatest { c.db.messages().latest(key, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    val facts: StateFlow<ThreadFacts> = combine(messages, c.db.mmsDownloads().observeAll()) { list, downloads -> list to downloads }.mapLatest { (list, downloads) ->
        val ids = list.map { it.id }
        if (ids.isEmpty()) ThreadFacts()
        else ThreadFacts(
            otps = ids.chunked(500).flatMap { c.db.insights().otpsFor(it) }.associateBy { it.messageId },
            transactions = ids.chunked(500).flatMap { c.db.insights().transactionsFor(it) }.associateBy { it.messageId },
            attachments = ids.filter(MessageIds::isMms).chunked(500).flatMap { c.db.attachments().forMessages(it) }.groupBy { it.messageId },
            downloads = downloads.filter { it.messageId in ids.toSet() }.associateBy { it.messageId },
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ThreadFacts())

    /** The translations stored for the visible messages; a bubble shows them under the original text. */
    val translations: StateFlow<Map<Long, List<TranslationEntity>>> = combine(messages, c.db.translations().observeAll()) { list, all ->
        val ids = list.map { it.id }.toSet()
        all.filter { it.messageId in ids }.groupBy { it.messageId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyMap())

    /** Files chosen for the next message; copied into app storage so they outlive the picker's grant. */
    val attachments = MutableStateFlow<List<OutgoingAttachment>>(emptyList())

    /** Carrier- and settings-dependent rules for turning long texts into MMS, refreshed when either changes. */
    val conversionRules: StateFlow<ConversionRules> = combine(c.settings.settings, selectedSubId) { _, sub -> sub }
        .mapLatest { sub -> c.outgoing.rulesFor(sub) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), ConversionRules.OFF)

    /** One-shot problem to show the user (for example "too large for MMS"); the screen clears it. */
    val notice = MutableStateFlow<String?>(null)

    val quickReplies: StateFlow<List<String>> = messages.map { list ->
        val latest = list.firstOrNull() ?: return@map emptyList<String>()
        if (latest.type != Telephony.Sms.MESSAGE_TYPE_INBOX) emptyList()
        else c.parser.parse(latest.address, latest.body, latest.date).quickReplies
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    init {
        sims.value = c.sims.active()
        // A held message that could not be sent comes back into the box with the reason, whether or not this screen was open at the time.
        viewModelScope.launch {
            c.sendHold.failed.map { it[key].orEmpty() }.filter { it.isNotEmpty() }.collect {
                c.sendHold.takeFailures(key).forEach { f -> notice.value = f.reason; putBack(f.message) }
            }
        }
    }

    fun onShown() {
        ActiveConversation.key = key
        c.notifications.cancelConversation(key)
        viewModelScope.launch { c.repository.markConversationRead(key) }
    }

    fun onHidden() {
        if (ActiveConversation.key == key) ActiveConversation.key = null
    }

    fun loadMore() { limit.value += PAGE }

    fun addAttachments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val room = MAX_ATTACHMENTS_PER_MESSAGE - attachments.value.size
            if (uris.size > room) notice.value = "You can attach up to $MAX_ATTACHMENTS_PER_MESSAGE files"
            val imported = uris.take(maxOf(room, 0)).mapNotNull { c.attachmentStore.importUri(it) }
            if (imported.size < uris.take(maxOf(room, 0)).size) notice.value = "Some files couldn't be read"
            attachments.value = attachments.value + imported
        }
    }

    fun removeAttachment(a: OutgoingAttachment) {
        attachments.value = attachments.value - a
        c.attachmentStore.discard(a)
    }

    /**
     * Sends the draft. It goes out as an MMS when there are attachments or several recipients, otherwise as an
     * SMS. [quickReply] sends just that text and leaves the draft alone. With a hold set in Settings the message waits
     * for its undo window first (see [undoHeld] and [sendHeldNow]); [immediate] skips that wait for this message.
     */
    fun send(address: String, quickReply: String? = null, immediate: Boolean = false) {
        val participants = ConversationKeys.participants(address)
        val text = quickReply ?: draft.value
        val files = if (quickReply != null) emptyList() else attachments.value
        if (text.isBlank() && files.isEmpty()) return
        val subId = selectedSubId.value
        val fromComposer = quickReply == null
        if (fromComposer) {
            draft.value = ""
            attachments.value = emptyList()
        }
        viewModelScope.launch {
            val holdSeconds = if (immediate) 0 else c.settings.current().sendDelaySeconds
            if (holdSeconds > 0) {
                c.sendHold.hold(key, participants, text, files, subId, restoresDraft = fromComposer, delayMs = holdSeconds * 1000L)
                return@launch
            }
            try {
                when (val r = c.outgoing.send(participants, text, files, subId, key)) { // a reply stays in this thread, whatever type it goes out as
                    is SendOutcome.Sent -> files.forEach(c.attachmentStore::discard)
                    is SendOutcome.Failed -> { // nothing was stored: give the draft back so the user can fix it
                        notice.value = r.reason
                        if (fromComposer) { draft.value = text; attachments.value = files }
                    }
                }
            } catch (e: Exception) {
                // An unexpected failure must never crash the app or eat the user's words.
                notice.value = "Couldn't send. Try again."
                if (fromComposer) { draft.value = text; attachments.value = files }
            }
        }
    }

    /** The message of this conversation that is waiting out its undo window, if any. */
    val held: StateFlow<HeldMessage?> = c.sendHold.held.map { it[key] }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), c.sendHold.held.value[key])

    /** Takes the held message back into the composer. What was typed since stays, after it. */
    fun undoHeld() { c.sendHold.undo(key)?.let(::putBack) }

    fun sendHeldNow() = c.sendHold.sendNow(key)

    private fun putBack(m: HeldMessage) {
        if (!m.restoresDraft) { m.attachments.forEach(c.attachmentStore::discard); return }
        draft.value = if (draft.value.isBlank()) m.text else m.text + "\n" + draft.value
        attachments.value = m.attachments + attachments.value
    }

    /** How the current draft would be sent, for the composer's hint. */
    /** Pure: [rules] must come from a collected [conversionRules], so the composer recomposes when they change. */
    fun routeFor(address: String, text: String, hasAttachments: Boolean, rules: ConversionRules): SendRoute =
        c.outgoing.routeFor(ConversationKeys.participants(address).size, hasAttachments, text, rules)

    fun resendAsText(id: Long) = viewModelScope.launch {
        val ok = try { c.outgoing.resendAsText(id, key) } catch (e: Exception) { false }
        if (!ok) notice.value = "This message can't be sent as text"
    }

    fun resend(id: Long) = viewModelScope.launch {
        val ok = try {
            if (MessageIds.isMms(id)) c.mmsEngine.resend(id) else c.sender.resend(id)
            true
        } catch (e: Exception) { false }
        if (!ok) notice.value = "Couldn't resend. Try again."
    }
    fun download(id: Long) = viewModelScope.launch { c.mmsEngine.startDownload(id) }
    fun delete(ids: List<Long>) = viewModelScope.launch {
        val ok = try { c.repository.deleteMessages(ids); true } catch (e: Exception) { false }
        if (!ok) notice.value = "Couldn't delete the message"
    }
    fun star(id: Long, starred: Boolean) = viewModelScope.launch { c.repository.setStarred(id, starred) }
    fun markUnread() = viewModelScope.launch { c.repository.markConversationUnread(key) }
    fun mute(muted: Boolean) = viewModelScope.launch { c.repository.setMuted(key, muted) }
    fun pin(pinned: Boolean) = viewModelScope.launch { c.repository.setPinned(key, pinned) }
    fun block() = viewModelScope.launch { c.repository.blockSender(key) }
    fun unblock() = viewModelScope.launch { c.repository.unblockSender(key) }
    fun deleteConversation() = viewModelScope.launch { c.repository.deleteConversation(key) }
    fun setCategory(category: String) = viewModelScope.launch { c.repository.setConversationCategory(key, category) }
    fun unlock() = viewModelScope.launch { c.repository.unlockConversationCategory(key) }
    fun markOtpUsed(id: Long) = viewModelScope.launch { c.db.insights().markOtpUsed(id) }

    /** Stores a translation under a message; a later translation into the same language replaces it. */
    fun saveTranslation(messageId: Long, source: Language?, target: Language, text: String) =
        viewModelScope.launch { c.repository.saveTranslation(messageId, source, target, text) }

    private companion object {
        const val PAGE = 60
        const val MAX_ATTACHMENTS_PER_MESSAGE = 10
    }
}

/** Search by meaning: whether it is on, how much of the inbox it has read, and why it cannot work if it cannot. */
data class SmartSearchStatus(val enabled: Boolean = false, val indexed: Int = 0, val searchable: Int = 0, val problem: String? = null) {
    val reading: Boolean get() = enabled && problem == null && indexed < searchable
}

fun AppContainer.smartSearchStatus(): Flow<SmartSearchStatus> =
    combine(settings.settings.map { it.smartSearch }.distinctUntilChanged(), semanticIndex.coverage, semanticIndex.unavailable) { on, coverage, problem ->
        SmartSearchStatus(on, coverage.indexed, coverage.searchable, problem)
    }

/** What a search shows: messages containing the words, then messages that mean something similar. */
data class SearchResults(val keyword: List<MessageEntity> = emptyList(), val similar: List<MessageEntity> = emptyList(), val searching: Boolean = false)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")

    /** Checked in the search box, and only then does the model run: until it is, a search is plain word matching. */
    val useSemantic = MutableStateFlow(false)

    /** The keyword matches arrive first; the ones found by meaning follow when the model has answered. */
    val results: StateFlow<SearchResults> = combine(query, useSemantic) { q, semantic -> q to semantic }
        .debounce(250).transformLatest { (q, semantic) ->
            if (q.isBlank()) { emit(SearchResults()); return@transformLatest }
            val keyword = c.repository.search(q)
            val smart = semantic && c.settings.current().smartSearch
            emit(SearchResults(keyword, searching = smart))
            if (smart) emit(SearchResults(keyword, c.semanticIndex.similarMessages(q, exclude = keyword.mapTo(HashSet()) { it.id })))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), SearchResults())

    val smartSearch: StateFlow<SmartSearchStatus> = c.smartSearchStatus()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), SmartSearchStatus())
}

@OptIn(ExperimentalCoroutinesApi::class)
class FinanceViewModel(private val c: AppContainer) : ViewModel() {
    val month = MutableStateFlow(LocalDate.now().withDayOfMonth(1))
    val transactions: StateFlow<List<TransactionEntity>> = month.flatMapLatest {
        val (from, to) = c.finance.monthRange(it)
        c.db.insights().transactionsBetween(from, to)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
    val summaries: StateFlow<List<MonthSummary>> = transactions.map { c.finance.summarise(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
    val budgets = c.db.config().observeBudgets().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())

    fun previous() { month.value = month.value.minusMonths(1) }
    fun next() { if (month.value.plusMonths(1) <= LocalDate.now().withDayOfMonth(1)) month.value = month.value.plusMonths(1) }
    fun saveBudget(b: BudgetEntity) = viewModelScope.launch { c.db.config().upsertBudget(b) }
    fun deleteBudget(bucket: String) = viewModelScope.launch { c.db.config().deleteBudget(bucket) }
    fun setSpendCategory(messageId: Long, bucket: String?) = viewModelScope.launch { c.db.insights().setSpendCategory(messageId, bucket) }
    fun bucketOf(t: TransactionEntity) = c.finance.bucketOf(t)
}

class OrganizerViewModel(private val c: AppContainer) : ViewModel() {
    val rules = c.db.config().observeRules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
    val categories = c.db.config().observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), emptyList())
    val reapplying = MutableStateFlow(false)
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT), AppSettings())

    /** Moves a category in the bar; [order] is the order on screen, so every category keeps the place it shows. */
    fun moveCategory(order: List<String>, key: String, steps: Int) = viewModelScope.launch {
        c.settings.update { it.copy(categoryOrder = CategoryOrder.move(order, key, steps)) }
    }

    /**
     * Shows or hides a category in the inbox bar; its messages stay on the phone but leave the All view. Independent
     * of the secured toggle: a category may be both hidden and locked.
     */
    fun setCategoryHidden(key: String, hidden: Boolean) = viewModelScope.launch {
        c.settings.update { it.copy(hiddenCategories = if (hidden) it.hiddenCategories + key else it.hiddenCategories - key) }
    }

    /**
     * Locks (or unlocks) a category behind the fingerprint/PIN gate. Independent of the hidden toggle: a category may
     * be both hidden and locked. An open unlock window is left alone: other categories may still be secured.
     */
    fun setCategorySecured(key: String, secured: Boolean) = viewModelScope.launch {
        c.settings.update { it.copy(securedCategories = if (secured) it.securedCategories + key else it.securedCategories - key) }
    }

    /** Stops (or resumes) notifications for a category, and clears the ones already showing when it is stopped. */
    fun setCategoryNotifications(key: String, notify: Boolean) = viewModelScope.launch {
        c.settings.update { it.copy(mutedCategories = if (notify) it.mutedCategories - key else it.mutedCategories + key) }
        if (!notify) c.db.conversations().keysIn(key).forEach { c.notifications.cancelConversation(it) }
    }

    fun addRule(rule: UserRuleEntity) = viewModelScope.launch { c.db.config().insertRule(rule); reapply() }
    fun toggleRule(rule: UserRuleEntity) = viewModelScope.launch { c.db.config().updateRule(rule.copy(isEnabled = !rule.isEnabled)); reapply() }
    fun deleteRule(id: Long) = viewModelScope.launch { c.db.config().deleteRule(id); reapply() }

    fun addCategory(name: String, icon: String) = viewModelScope.launch {
        val key = SmsCategory.CUSTOM_PREFIX + UUID.randomUUID().toString().replace("-", "").take(8)
        c.db.config().upsertCategory(CustomCategoryEntity(key, name.trim(), icon))
    }

    fun updateCategory(c0: CustomCategoryEntity, name: String, icon: String) = viewModelScope.launch {
        c.db.config().upsertCategory(c0.copy(name = name.trim(), icon = icon))
    }

    fun deleteCategory(key: String) = viewModelScope.launch {
        c.db.config().deleteCategory(key)
        // Conversations in a deleted category fall back to automatic classification.
        c.db.conversations().allKeys().forEach { k ->
            if (c.db.conversations().get(k)?.category == key) c.repository.unlockConversationCategory(k)
        }
    }

    fun reapply() = viewModelScope.launch {
        reapplying.value = true
        runCatching { c.repository.reparseAll() }
        reapplying.value = false
    }
}
