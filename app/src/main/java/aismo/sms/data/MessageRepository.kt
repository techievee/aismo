package aismo.sms.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import aismo.sms.data.db.AttachmentEntity
import aismo.sms.data.db.ConversationEntity
import aismo.sms.data.db.IncidentEntity
import aismo.sms.data.db.MessageEntity
import aismo.sms.data.db.MessageIds
import aismo.sms.data.db.MmsDownloadEntity
import aismo.sms.data.db.AismoDatabase
import aismo.sms.data.db.OtpEntity
import aismo.sms.data.db.ReminderEntity
import aismo.sms.data.db.TransactionEntity
import aismo.sms.data.db.TranslationEntity
import aismo.sms.data.mms.MmsProvider
import aismo.sms.data.mms.OwnNumbers
import aismo.sms.data.mms.attachmentKind
import aismo.sms.data.mms.toRaw
import aismo.sms.data.settings.SettingsRepository
import aismo.sms.data.sms.BlockedNumbers
import aismo.sms.data.sms.RawSms
import aismo.sms.data.sms.SmsProvider
import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.SmsCategory
import aismo.sms.domain.parser.ParsedSms
import aismo.sms.domain.parser.SenderBrands
import aismo.sms.domain.parser.SmsParser
import aismo.sms.domain.parser.UserRule
import aismo.sms.domain.translate.Language

/** A freshly stored incoming message with what the parser understood about it. */
data class IngestedMessage(
    val message: MessageEntity,
    val parsed: ParsedSms,
    val isBlocked: Boolean,
    /** True for the "you have a multimedia message" notification that has not been downloaded yet. */
    val isMmsPlaceholder: Boolean = false,
)

/**
 * Keeps the Room index in step with the system SMS provider (the source of truth), and derives
 * conversations, OTPs, transactions, reminders and spam incidents from message text.
 */
class MessageRepository(
    private val db: AismoDatabase,
    private val provider: SmsProvider,
    private val mms: MmsProvider,
    private val ownNumbers: OwnNumbers,
    private val blocked: BlockedNumbers,
    private val settings: SettingsRepository,
    private val parser: SmsParser,
) {
    private val syncLock = Mutex()
    private val messages get() = db.messages()
    private val conversations get() = db.conversations()
    private val insights get() = db.insights()

    // ---------------------------------------------------------------- import and sync

    /** Imports every provider row (SMS and MMS) newer than what we have. Safe to call repeatedly. Returns rows added. */
    suspend fun syncNew(onProgress: (imported: Int) -> Unit = {}): Int = syncLock.withLock {
        withContext(Dispatchers.IO) {
            var total = 0
            var cursor = messages.maxSmsId()
            while (true) {
                val batch = provider.readAfter(cursor, BATCH)
                if (batch.isEmpty()) break
                ingest(batch)
                cursor = batch.last().id
                total += batch.size
                onProgress(total)
            }
            var mmsCursor = messages.maxMmsRawId()
            while (true) {
                val rows = mms.readAfter(mmsCursor, MMS_BATCH)
                if (rows.isEmpty()) break
                ingest(mmsToRaw(rows))
                mmsCursor = rows.last().rawId
                total += rows.size
                onProgress(total)
            }
            settings.setImportCompleted(true)
            total
        }
    }

    /** Re-reads the newest rows to pick up read/status changes and drops rows deleted elsewhere. */
    suspend fun syncRecent(window: Int = 300): Unit = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val newest = provider.readNewest(window)
            if (newest.isNotEmpty()) ingest(newest.reversed())
            val newestMms = mms.readNewest(window)
            if (newestMms.isNotEmpty()) ingest(mmsToRaw(newestMms.reversed()))
            reconcileDeletions()
        }
    }

    private suspend fun reconcileDeletions() {
        // A provider that cannot be read (null) must never be mistaken for "everything was deleted".
        provider.allIds()?.let { live ->
            val gone = messages.smsIds().filter { it !in live }
            if (gone.isNotEmpty()) removeLocal(gone)
        }
        mms.allIds()?.let { live ->
            val gone = messages.mmsIds().filter { MessageIds.raw(it) !in live }
            if (gone.isNotEmpty()) removeLocal(gone)
        }
    }

    /**
     * Re-reads specific rows (SMS or MMS, by index id) after the app wrote or changed them. Never throws:
     * the provider is the source of truth, so a failure here (provider hiccup, index trouble) is dropped
     * and picked up by the next sync instead of crashing the send button or a radio result receiver.
     */
    suspend fun refresh(ids: Collection<Long>): List<IngestedMessage> = refresh(ids, emptyMap())

    /**
     * Re-reads specific rows (SMS or MMS, by index id) after the app wrote or changed them. Never throws:
     * the provider is the source of truth, so a failure here (provider hiccup, index trouble) is dropped
     * and picked up by the next sync instead of crashing the send button or a radio result receiver.
     * [keyHints] files a row that is not in the index yet under a given conversation: a reply is stored in
     * the thread it was sent from, which may differ from the key its address alone would derive (a text
     * reply sent from an MMS thread, a photo sent from an SMS one).
     */
    suspend fun refresh(ids: Collection<Long>, keyHints: Map<Long, String>): List<IngestedMessage> = try {
        syncLock.withLock {
            withContext(Dispatchers.IO) {
                val (mmsIds, smsIds) = ids.partition(MessageIds::isMms)
                val rows = provider.readByIds(smsIds) + mmsToRaw(mms.readByIds(mmsIds.map(MessageIds::raw)))
                ingest(rows, keyHints = keyHints)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun mmsToRaw(rows: List<aismo.sms.data.mms.StoredMms>): List<RawSms> {
        if (rows.isEmpty()) return emptyList()
        val own = ownNumbers.keys(settings.current().ownNumber)
        return rows.filter { it.box != 3 }.map { it.toRaw(if (it.isNotification) emptyList() else mms.parts(it.rawId), own) }
    }

    /** Re-parses messages written by an older parser revision, in small batches. */
    suspend fun reparseStale(): Int = syncLock.withLock {
        withContext(Dispatchers.IO) {
            var total = 0
            while (true) {
                val stale = messages.staleParse(PARSER_VERSION, BATCH)
                if (stale.isEmpty()) break
                ingest(stale.map { it.toRaw() }, forceParse = true)
                total += stale.size
            }
            settings.setParserVersionApplied(PARSER_VERSION)
            total
        }
    }

    /**
     * One-time step for indexes built before SMS and MMS got separate threads: it runs the split once and
     * stamps the index, so it never runs again.
     */
    suspend fun migrateMmsKeys(): Int {
        if (settings.mmsKeyVersion() >= MMS_KEY_VERSION) return 0
        val moved = splitMmsKeys()
        settings.setMmsKeyVersion(MMS_KEY_VERSION)
        return moved
    }

    /**
     * Moves the MMS messages of every mixed conversation to the MMS form of its key and opens the MMS twin of
     * that conversation with what the user had chosen for the old one (a manually locked category, pin and
     * mute) carried across. Re-runnable: a row already in the MMS form is left alone, and a twin that exists
     * is not replaced.
     */
    suspend fun splitMmsKeys(): Int = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val moved = mutableMapOf<String, String>() // old conversation key -> MMS twin key
            var cursor = 0L
            while (true) {
                val page = messages.mmsAfter(cursor, BATCH)
                if (page.isEmpty()) break
                val rekeyed = page.map { it to ConversationKeys.of(it.address, mms = true) }
                    .filter { (m, newKey) -> !ConversationKeys.isMmsKey(m.conversationKey) && newKey != m.conversationKey }
                if (rekeyed.isNotEmpty()) {
                    messages.upsertAll(rekeyed.map { (m, newKey) -> m.copy(conversationKey = newKey) })
                    rekeyed.forEach { (m, newKey) -> moved[m.conversationKey] = newKey }
                }
                cursor = page.last().id
            }
            for ((old, twinKey) in moved) {
                val source = conversations.get(old)
                if (source != null && conversations.get(twinKey) == null) {
                    // The latest-*, unread and count fields are recomputed below; the rest carries the user's choices over.
                    conversations.upsert(
                        ConversationEntity(
                            key = twinKey, address = source.address, title = source.title,
                            latestMessageId = 0, latestBody = "", latestDate = 0, latestType = 0,
                            unreadCount = 0, messageCount = 0,
                            category = source.category, categoryLocked = source.categoryLocked, needsReview = false,
                            isPinned = source.isPinned, isMuted = source.isMuted, isBlocked = source.isBlocked,
                        ),
                    )
                }
                recompute(twinKey)
                recompute(old) // a conversation left with no SMS at all deletes itself here
            }
            moved.size
        }
    }

    /**
     * The other direction of [splitMmsKeys], for the "keep together" choice: moves the MMS messages of every
     * MMS conversation back to the shared form of its key and merges the MMS conversation into the one its
     * SMS messages use — pin, mute, block and a manually locked category carried across. Re-runnable: a row
     * already in the shared form is left alone.
     */
    suspend fun groupMmsKeys(): Int = syncLock.withLock {
        withContext(Dispatchers.IO) {
            val merged = mutableMapOf<String, String>() // MMS conversation key -> shared key it merges into
            var cursor = 0L
            while (true) {
                val page = messages.mmsAfter(cursor, BATCH)
                if (page.isEmpty()) break
                val rekeyed = page.map { it to ConversationKeys.of(it.address, mms = false) }
                    .filter { (m, newKey) -> ConversationKeys.isMmsKey(m.conversationKey) && newKey != m.conversationKey }
                if (rekeyed.isNotEmpty()) {
                    messages.upsertAll(rekeyed.map { (m, newKey) -> m.copy(conversationKey = newKey) })
                    rekeyed.forEach { (m, newKey) -> merged[m.conversationKey] = newKey }
                }
                cursor = page.last().id
            }
            for ((mmsKey, sharedKey) in merged) {
                val mmsConv = conversations.get(mmsKey)
                val sharedConv = conversations.get(sharedKey)
                when {
                    mmsConv != null && sharedConv != null -> {
                        // The category the user locked by hand wins; an unlocked one re-votes below over the merged messages.
                        val locked = if (sharedConv.categoryLocked) sharedConv else if (mmsConv.categoryLocked) mmsConv else null
                        conversations.upsert(
                            sharedConv.copy(
                                isPinned = sharedConv.isPinned || mmsConv.isPinned,
                                isMuted = sharedConv.isMuted || mmsConv.isMuted,
                                isBlocked = sharedConv.isBlocked || mmsConv.isBlocked,
                                categoryLocked = locked != null,
                                category = locked?.category ?: sharedConv.category,
                            ),
                        )
                    }
                    mmsConv != null -> conversations.upsert(mmsConv.copy(key = sharedKey)) // no shared conversation yet: this one simply becomes it
                }
                conversations.delete(mmsKey)
                recompute(sharedKey)
            }
            merged.size
        }
    }

    /**
     * Moves the stored keys to the shape the current "keep together or separate" choice wants, merging or
     * splitting conversations as needed. A no-op when they already match, so it is safe to run whenever the
     * setting may have changed, including at startup after an interrupted run.
     */
    suspend fun maintainGrouping(): Int {
        val grouped = settings.current().groupSmsAndMms
        if (settings.groupingApplied() == grouped) return 0
        val moved = if (grouped) groupMmsKeys() else splitMmsKeys()
        settings.setGroupingApplied(grouped)
        return moved
    }

    /** Re-parses every message with the current rules (after rules or categories change). */
    suspend fun reparseAll(): Int = syncLock.withLock {
        withContext(Dispatchers.IO) {
            var total = 0
            var cursor = 0L
            while (true) {
                val page = messages.pageAfter(cursor, BATCH)
                if (page.isEmpty()) break
                ingest(page.map { it.toRaw() }, forceParse = true)
                cursor = page.last().id
                total += page.size
            }
            total
        }
    }

    // ----------------------------------------------------------------------- ingest

    private suspend fun ingest(raws: List<RawSms>, forceParse: Boolean = false, keyHints: Map<Long, String> = emptyMap()): List<IngestedMessage> {
        if (raws.isEmpty()) return emptyList()
        val rules = db.config().enabledRules().map { UserRule(it.senderContains, it.bodyContains, it.categoryKey, it.merchant) }
        // A picture message takes the MMS form of its key only while SMS and MMS are kept in separate threads.
        val grouped = settings.current().groupSmsAndMms
        val existing = messages.getAll(raws.map { it.id }).associateBy { it.id }
        val ingested = mutableListOf<IngestedMessage>()
        val entities = ArrayList<MessageEntity>(raws.size)
        val reparsed = mutableListOf<Pair<MessageEntity, ParsedSms>>()
        val placeholders = mutableListOf<MessageEntity>()
        val retextedIds = mutableListOf<Long>()
        val keys = LinkedHashSet<String>()

        for (r in raws) {
            if (r.type == 3) continue // drafts live only in the provider
            // A not-yet-stored row goes where it was sent from; a row the index already knows keeps its conversation.
            val key = keyHints[r.id]?.takeIf { existing[r.id] == null } ?: ConversationKeys.of(r.address, MessageIds.isMms(r.id) && !grouped)
            keys += key
            val prev = existing[r.id]
            val attachments = r.attachments
            val kind = attachments?.firstOrNull()?.let { attachmentKind(it.mime) } ?: prev?.mediaKind
            val count = attachments?.size ?: prev?.attachmentCount ?: 0
            if (prev != null && !forceParse && prev.body == r.body && prev.parserVersion == PARSER_VERSION) {
                // Same text, same parser: only provider-side state (date, read, type, status, attachments) can have changed.
                // The date does change: the platform stamps the real send time on an MMS once it has gone out.
                entities += prev.copy(
                    threadId = r.threadId, date = r.date, type = r.type, isRead = r.read, subId = r.subId, status = r.status,
                    subject = r.subject ?: prev.subject, mediaKind = kind, attachmentCount = count,
                )
                continue
            }
            if (prev != null && prev.body != r.body) retextedIds += r.id // its stored meaning no longer describes it
            val placeholder = r.download != null
            val parsed = if (r.type == 1 && !placeholder) parser.parse(r.address, r.body, r.date, rules) else null
            val category = parsed?.categoryKey ?: conversations.get(key)?.category ?: SmsCategory.PERSONAL.key
            val entity = MessageEntity(
                id = r.id, threadId = r.threadId, address = r.address, conversationKey = key, body = r.body, date = r.date,
                type = r.type, isRead = r.read, subId = r.subId, status = r.status,
                isStarred = prev?.isStarred ?: false, category = category, parserVersion = PARSER_VERSION,
                subject = r.subject ?: prev?.subject, mediaKind = kind, attachmentCount = count,
            )
            entities += entity
            if (parsed != null) reparsed += entity to parsed
            if (placeholder) placeholders += entity
        }

        db.withTransaction {
            messages.upsertAll(entities)
            retextedIds.chunked(500).forEach { db.embeddings().deleteFor(it); db.translations().deleteFor(it) } // a re-texted message has no current meaning or translation
            val derivedIds = reparsed.map { it.first.id }
            if (derivedIds.isNotEmpty()) {
                derivedIds.chunked(500).forEach { chunk ->
                    insights.deleteTransactions(chunk); insights.deleteOtps(chunk)
                    insights.deleteIncidents(chunk)
                }
                // Reminders keep their open/done state, so only replace those of messages whose text changed.
                derivedIds.chunked(500).forEach { insights.deleteReminders(it) }
            }
            for ((m, p) in reparsed) persistFacts(m, p)

            val withAttachments = raws.filter { it.attachments != null && it.type != 3 }
            if (withAttachments.isNotEmpty()) {
                withAttachments.map { it.id }.chunked(500).forEach { db.attachments().deleteFor(it) }
                db.attachments().upsertAll(
                    withAttachments.flatMap { r ->
                        r.attachments.orEmpty().map { AttachmentEntity(r.id, it.partId, it.mime, it.name, it.sizeBytes, attachmentKind(it.mime)) }
                    },
                )
            }
            for (r in raws) {
                val d = r.download ?: continue
                val prevDownload = db.mmsDownloads().get(r.id)
                db.mmsDownloads().upsert(
                    MmsDownloadEntity(
                        messageId = r.id, contentLocation = d.contentLocation, transactionId = d.transactionId, subId = r.subId,
                        sizeBytes = d.sizeBytes, expiresAt = d.expiresAtMs, state = prevDownload?.state ?: MmsDownloadEntity.WAITING,
                        attempts = prevDownload?.attempts ?: 0, lastError = prevDownload?.lastError ?: 0,
                        createdAt = prevDownload?.createdAt ?: System.currentTimeMillis(),
                    ),
                )
            }
            for (key in keys) recompute(key)
        }

        val blockedKeys = keys.filter { conversations.get(it)?.isBlocked == true }.toSet()
        for ((m, p) in reparsed) {
            if (existing[m.id] == null && m.type == 1 && !m.isRead) ingested += IngestedMessage(m, p, m.conversationKey in blockedKeys)
        }
        for (m in placeholders) {
            if (existing[m.id] == null && !m.isRead) {
                ingested += IngestedMessage(m, ParsedSms.empty(m.category), m.conversationKey in blockedKeys, isMmsPlaceholder = true)
            }
        }
        return ingested
    }

    private suspend fun persistFacts(m: MessageEntity, p: ParsedSms) {
        p.otp?.let { o ->
            insights.upsertOtp(
                OtpEntity(m.id, o.code, o.purpose, m.address, m.date, o.validForMinutes?.let { m.date + it * 60_000L }),
            )
        }
        p.transaction?.let { t ->
            insights.upsertTransaction(
                TransactionEntity(m.id, t.kind.name, t.amountMinor, t.currency, t.merchant, t.instrument, t.last4, t.balanceMinor, m.date, null),
            )
        }
        for (r in p.reminders) {
            insights.insertReminder(
                ReminderEntity(
                    messageId = m.id, kind = r.kind.name, title = r.title, sender = p.merchant ?: m.address,
                    dueAt = r.dueAt, amountMinor = r.amountMinor, currency = r.currency,
                ),
            )
        }
        if (p.spamScore >= SPAM_INCIDENT_SCORE) {
            insights.upsertIncident(IncidentEntity(m.id, m.address, p.spamScore, p.spamReasons.joinToString("; "), m.date))
        }
    }

    // ------------------------------------------------------------------ conversations

    private suspend fun recompute(key: String) {
        val newest = messages.newestIn(key)
        if (newest == null) {
            conversations.delete(key)
            return
        }
        val existing = conversations.get(key)
        val category = if (existing?.categoryLocked == true) existing.category else voteCategory(key)
        val phone = ConversationKeys.isPhoneNumber(newest.address)
        val group = ConversationKeys.isGroup(newest.address)
        val isBlocked = existing?.isBlocked ?: (!group && blocked.isBlocked(newest.address))
        conversations.upsert(
            ConversationEntity(
                key = key,
                address = newest.address,
                title = if (phone || group) null else SenderBrands.displayName(ConversationKeys.senderToken(newest.address)),
                latestMessageId = newest.id,
                latestBody = previewOf(newest),
                latestDate = newest.date,
                latestType = newest.type,
                latestAttachmentCount = newest.attachmentCount,
                unreadCount = messages.unreadIn(key),
                messageCount = messages.countIn(key),
                category = category,
                categoryLocked = existing?.categoryLocked ?: false,
                needsReview = existing?.categoryLocked != true && category == SmsCategory.OTHER.key,
                isPinned = existing?.isPinned ?: false,
                isMuted = existing?.isMuted ?: false,
                isBlocked = isBlocked,
            ),
        )
    }

    /** What the conversation list shows for the newest message: its text, or a label for a bare attachment. */
    private suspend fun previewOf(m: MessageEntity): String {
        if (m.body.isNotBlank()) return m.body
        if (m.isMms && db.mmsDownloads().get(m.id) != null) return "Multimedia message"
        return when (m.mediaKind) {
            "image" -> "Photo"
            "video" -> "Video"
            "audio" -> "Audio"
            "contact" -> "Contact card"
            null -> m.body
            else -> "Attachment"
        }
    }

    /**
     * Conversation category from the sender's recent incoming mail. OTPs and spam do not outvote
     * a sender's regular category, so a bank's login codes do not turn its thread into "OTP".
     */
    private suspend fun voteCategory(key: String): String {
        val recent = messages.recentIncomingCategories(key, 30)
        if (recent.isEmpty()) return SmsCategory.PERSONAL.key
        if (recent.take(3).count { it == SmsCategory.SPAM.key } >= 2) return SmsCategory.SPAM.key
        val regular = recent.filter { it != SmsCategory.OTP.key && it != SmsCategory.SPAM.key }
        if (regular.isNotEmpty()) {
            val counts = regular.groupingBy { it }.eachCount()
            val best = counts.values.max()
            return regular.first { counts[it] == best } // ties go to the most recent
        }
        return if (recent.any { it == SmsCategory.OTP.key }) SmsCategory.OTP.key else SmsCategory.SPAM.key
    }

    suspend fun setConversationCategory(key: String, category: String) = db.withTransaction {
        conversations.lockCategory(key, category)
        messages.setCategoryForConversation(key, category)
    }

    suspend fun unlockConversationCategory(key: String) = db.withTransaction {
        conversations.get(key)?.let { conversations.upsert(it.copy(categoryLocked = false)) }
        recompute(key)
    }

    suspend fun setPinned(key: String, pinned: Boolean) = conversations.setPinned(key, pinned)
    suspend fun setMuted(key: String, muted: Boolean) = conversations.setMuted(key, muted)

    // ------------------------------------------------------------------- mutations

    suspend fun markConversationRead(key: String) = withContext(Dispatchers.IO) {
        val ids = messages.unreadIdsIn(key)
        if (ids.isEmpty()) return@withContext
        markInProviders(ids, read = true)
        db.withTransaction {
            messages.markConversationRead(key)
            recompute(key)
        }
    }

    suspend fun markConversationUnread(key: String) = withContext(Dispatchers.IO) {
        val newest = messages.newestIncomingIn(key) ?: return@withContext
        markInProviders(listOf(newest.id), read = false)
        db.withTransaction {
            messages.setRead(listOf(newest.id), false)
            recompute(key)
        }
    }

    private fun markInProviders(ids: List<Long>, read: Boolean) {
        val (mmsIds, smsIds) = ids.partition(MessageIds::isMms)
        provider.markRead(smsIds, read)
        mms.markRead(mmsIds.map(MessageIds::raw), read)
    }

    private fun deleteFromProviders(ids: List<Long>) {
        val (mmsIds, smsIds) = ids.partition(MessageIds::isMms)
        provider.delete(smsIds)
        mms.delete(mmsIds.map(MessageIds::raw))
    }

    suspend fun deleteMessages(ids: List<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        deleteFromProviders(ids)
        removeLocal(ids)
    }

    suspend fun deleteConversation(key: String) = withContext(Dispatchers.IO) {
        val ids = messages.idsIn(key)
        deleteFromProviders(ids)
        db.withTransaction {
            removeLocal(ids)
            conversations.delete(key)
        }
    }

    /** Stores one translation of a message; a later translation into the same language replaces it. */
    suspend fun saveTranslation(messageId: Long, source: Language?, target: Language, text: String) = withContext(Dispatchers.IO) {
        db.translations().upsert(TranslationEntity(messageId, target.key, source?.key, text, System.currentTimeMillis()))
    }

    private suspend fun removeLocal(ids: List<Long>) = db.withTransaction {
        val keys = ids.chunked(500).flatMap { messages.keysOf(it) }.distinct()
        ids.chunked(500).forEach {
            messages.deleteByIds(it)
            insights.deleteTransactions(it); insights.deleteOtps(it); insights.deleteReminders(it); insights.deleteIncidents(it)
            db.attachments().deleteFor(it); db.mmsDownloads().delete(it); db.embeddings().deleteFor(it); db.translations().deleteFor(it)
        }
        keys.forEach { recompute(it) }
    }

    suspend fun setStarred(id: Long, starred: Boolean) = messages.setStarred(id, starred)

    /** The category a conversation is filed under now, including a choice the user made; null if it is not known. */
    suspend fun categoryOf(conversationKey: String): String? = conversations.get(conversationKey)?.category

    suspend fun blockSender(key: String): Boolean = withContext(Dispatchers.IO) {
        val c = conversations.get(key) ?: return@withContext false
        if (ConversationKeys.isGroup(c.address)) return@withContext false // blocking applies to one sender at a time
        val ok = blocked.block(c.address)
        // Even if the system list refuses (we may not be the default app) hide the thread locally.
        conversations.setBlocked(key, true)
        ok
    }

    suspend fun unblockSender(key: String): Boolean = withContext(Dispatchers.IO) {
        val c = conversations.get(key) ?: return@withContext false
        val ok = blocked.unblock(c.address)
        conversations.setBlocked(key, false)
        ok
    }

    // ----------------------------------------------------------------------- search

    suspend fun search(query: String, limit: Int = 200): List<MessageEntity> = withContext(Dispatchers.IO) {
        // Split exactly where the FTS tokenizer does (anything that is not a letter or digit), so
        // "4500.00" searches for the tokens "4500" and "00" instead of a non-existent "450000".
        val tokens = query.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return@withContext emptyList()
        val fts = runCatching { messages.search(tokens.joinToString(" ") { "$it*" }, limit) }.getOrDefault(emptyList())
        if (fts.isNotEmpty()) return@withContext fts
        val escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        messages.searchLike(escaped, limit)
    }

    /** Attachments and downloads stay as they are (null / absent): only the text is being re-parsed. */
    private fun MessageEntity.toRaw() = RawSms(id, threadId, address, body, date, type, isRead, subId, status, subject = subject)

    companion object {
        /** Bump when parser rules change so stored messages are re-parsed. */
        const val PARSER_VERSION = 1
        /** Bump when the conversation key scheme changes so stored conversations are re-keyed once. */
        const val MMS_KEY_VERSION = 1
        private const val BATCH = 500
        private const val MMS_BATCH = 100
        private const val SPAM_INCIDENT_SCORE = 55
    }
}
