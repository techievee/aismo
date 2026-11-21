package aismo.sms.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationKey = :key ORDER BY date DESC, id DESC LIMIT :limit")
    fun latest(key: String, limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationKey = :key ORDER BY date DESC, id DESC LIMIT 1")
    suspend fun newestIn(key: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationKey = :key")
    suspend fun countIn(key: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE conversationKey = :key AND isRead = 0 AND type = 1")
    suspend fun unreadIn(key: String): Int

    @Query("SELECT id FROM messages WHERE id < ${MessageIds.MMS_BASE}")
    suspend fun smsIds(): List<Long>

    @Query("SELECT id FROM messages WHERE id >= ${MessageIds.MMS_BASE}")
    suspend fun mmsIds(): List<Long>

    @Query("SELECT * FROM messages WHERE id IN (:ids)")
    suspend fun getAll(ids: List<Long>): List<MessageEntity>

    /** The SIM (subscription id) the conversation last used, for a message in either direction; null if none is known. */
    @Query("SELECT subId FROM messages WHERE conversationKey = :key AND subId >= 0 ORDER BY date DESC LIMIT 1")
    suspend fun lastSubId(key: String): Int?

    @Query("SELECT subId FROM messages WHERE conversationKey = :key AND subId >= 0 ORDER BY date DESC LIMIT 1")
    fun observeLastSubId(key: String): Flow<Int?>

    @Query("SELECT id FROM messages WHERE conversationKey = :key")
    suspend fun idsIn(key: String): List<Long>

    @Query("SELECT id FROM messages WHERE conversationKey = :key AND isRead = 0 AND type = 1")
    suspend fun unreadIdsIn(key: String): List<Long>

    @Query("SELECT category FROM messages WHERE conversationKey = :key AND type = 1 ORDER BY date DESC LIMIT :limit")
    suspend fun recentIncomingCategories(key: String, limit: Int): List<String>

    @Query("SELECT * FROM messages WHERE conversationKey = :key AND type = 1 ORDER BY date DESC LIMIT 1")
    suspend fun newestIncomingIn(key: String): MessageEntity?

    @Query("SELECT COALESCE(MAX(id), 0) FROM messages WHERE id < ${MessageIds.MMS_BASE}")
    suspend fun maxSmsId(): Long

    /** Highest provider `_id` among MMS rows (already translated back to the provider's numbering). */
    @Query("SELECT COALESCE(MAX(id), ${MessageIds.MMS_BASE}) - ${MessageIds.MMS_BASE} FROM messages WHERE id >= ${MessageIds.MMS_BASE}")
    suspend fun maxMmsRawId(): Long

    @Query("SELECT conversationKey FROM messages WHERE id IN (:ids)")
    suspend fun keysOf(ids: List<Long>): List<String>

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE messages SET isRead = 1 WHERE conversationKey = :key AND isRead = 0")
    suspend fun markConversationRead(key: String)

    @Query("UPDATE messages SET isRead = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: List<Long>, read: Boolean)

    @Query("UPDATE messages SET isStarred = :starred WHERE id = :id")
    suspend fun setStarred(id: Long, starred: Boolean)

    @Query("UPDATE messages SET type = :type, status = :status WHERE id = :id")
    suspend fun setSendState(id: Long, type: Int, status: Int)

    @Query("UPDATE messages SET category = :category WHERE conversationKey = :key")
    suspend fun setCategoryForConversation(key: String, category: String)

    @Query("SELECT * FROM messages WHERE parserVersion < :version ORDER BY id LIMIT :limit")
    suspend fun staleParse(version: Int, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE isStarred = 1 ORDER BY date DESC")
    fun starred(): Flow<List<MessageEntity>>

    @Query(
        "SELECT m.* FROM messages m JOIN messages_fts f ON m.id = f.rowid " +
            "WHERE messages_fts MATCH :match ORDER BY m.date DESC LIMIT :limit"
    )
    suspend fun search(match: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE body LIKE '%' || :needle || '%' ESCAPE '\\' ORDER BY date DESC LIMIT :limit")
    suspend fun searchLike(needle: String, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages")
    fun count(): Flow<Int>

    @Query("SELECT * FROM messages WHERE id > :afterId ORDER BY id LIMIT :limit")
    suspend fun pageAfter(afterId: Long, limit: Int): List<MessageEntity>

    /** MMS rows only (the separate id range), for the one-time conversation re-keying. */
    @Query("SELECT * FROM messages WHERE id >= ${MessageIds.MMS_BASE} AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun mmsAfter(afterId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE id < ${MessageIds.MMS_BASE}")
    suspend fun countSms(): Int

    /** MMS that have content to back up: everything except download placeholders. */
    @Query("SELECT COUNT(*) FROM messages WHERE id >= ${MessageIds.MMS_BASE} AND id NOT IN (SELECT messageId FROM mms_downloads)")
    suspend fun countBackupableMms(): Int

    @Query("SELECT id FROM messages WHERE id >= ${MessageIds.MMS_BASE} AND date BETWEEN :from AND :to")
    suspend fun mmsIdsBetween(from: Long, to: Long): List<Long>

    @Query("SELECT id FROM messages WHERE address = :address AND date = :date AND body = :body LIMIT 1")
    suspend fun findMatching(address: String, date: Long, body: String): Long?
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE `key` = :key")
    suspend fun get(key: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE `key` = :key")
    fun observe(key: String): Flow<ConversationEntity?>

    @Query("DELETE FROM conversations WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("SELECT * FROM conversations WHERE isBlocked = 0 ORDER BY isPinned DESC, latestDate DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isBlocked = 0 AND category = :category ORDER BY isPinned DESC, latestDate DESC")
    fun observeByCategory(category: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE isBlocked = 1 ORDER BY latestDate DESC")
    fun observeBlocked(): Flow<List<ConversationEntity>>

    @Query("SELECT category, COUNT(*) AS conversations, SUM(unreadCount) AS unread FROM conversations WHERE isBlocked = 0 GROUP BY category")
    fun observeCategoryStats(): Flow<List<CategoryStat>>

    @Query("UPDATE conversations SET isBlocked = :blocked WHERE `key` = :key")
    suspend fun setBlocked(key: String, blocked: Boolean)

    @Query("UPDATE conversations SET category = :category, categoryLocked = 1, needsReview = 0 WHERE `key` = :key")
    suspend fun lockCategory(key: String, category: String)

    @Query("UPDATE conversations SET isPinned = :pinned WHERE `key` = :key")
    suspend fun setPinned(key: String, pinned: Boolean)

    @Query("UPDATE conversations SET isMuted = :muted WHERE `key` = :key")
    suspend fun setMuted(key: String, muted: Boolean)

    @Query("SELECT `key` FROM conversations")
    suspend fun allKeys(): List<String>

    @Query("SELECT `key` FROM conversations WHERE category = :category")
    suspend fun keysIn(category: String): List<String>

    /** What the weekly spam reminder reports: spam conversations that are not blocked, and the messages in them. */
    @Query("SELECT COUNT(*) AS conversations, COALESCE(SUM(messageCount), 0) AS messages FROM conversations WHERE isBlocked = 0 AND category = 'spam'")
    suspend fun spamTotals(): SpamTotals

    @Query("DELETE FROM conversations")
    suspend fun clear()
}

data class CategoryStat(val category: String, val conversations: Int, val unread: Int)

@Dao
interface InsightDao {
    // transactions
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTransaction(t: TransactionEntity)

    @Query("DELETE FROM transactions WHERE messageId IN (:ids)")
    suspend fun deleteTransactions(ids: List<Long>)

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC")
    fun transactionsBetween(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC")
    suspend fun transactionsBetweenNow(from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC LIMIT :limit")
    fun recentTransactions(limit: Int): Flow<List<TransactionEntity>>

    @Query("UPDATE transactions SET spendCategory = :bucket WHERE messageId = :id")
    suspend fun setSpendCategory(id: Long, bucket: String?)

    // otps
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOtp(o: OtpEntity)

    @Query("DELETE FROM otps WHERE messageId IN (:ids)")
    suspend fun deleteOtps(ids: List<Long>)

    @Query("SELECT * FROM otps WHERE messageId = :id")
    suspend fun otp(id: Long): OtpEntity?

    @Query("SELECT * FROM otps WHERE messageId IN (:ids)")
    suspend fun otpsFor(ids: List<Long>): List<OtpEntity>

    @Query("SELECT * FROM transactions WHERE messageId IN (:ids)")
    suspend fun transactionsFor(ids: List<Long>): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE messageId = :id")
    suspend fun transaction(id: Long): TransactionEntity?

    @Query("SELECT * FROM otps ORDER BY receivedAt DESC LIMIT :limit")
    fun recentOtps(limit: Int): Flow<List<OtpEntity>>

    @Query("UPDATE otps SET isUsed = 1 WHERE messageId = :id")
    suspend fun markOtpUsed(id: Long)

    @Query("SELECT * FROM otps WHERE (isUsed = 1 AND receivedAt < :usedBefore) OR receivedAt < :anyBefore")
    suspend fun otpsToCleanUp(usedBefore: Long, anyBefore: Long): List<OtpEntity>

    // reminders
    @Insert
    suspend fun insertReminder(r: ReminderEntity): Long

    @Query("DELETE FROM reminders WHERE messageId IN (:ids)")
    suspend fun deleteReminders(ids: List<Long>)

    @Query("SELECT * FROM reminders WHERE status IN ('open', 'notified') ORDER BY CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt")
    fun openReminders(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders WHERE status = 'open' AND dueAt IS NOT NULL AND dueAt BETWEEN :from AND :to")
    suspend fun remindersDueBetween(from: Long, to: Long): List<ReminderEntity>

    @Query("UPDATE reminders SET status = :status WHERE id = :id")
    suspend fun setReminderStatus(id: Long, status: String)

    // incidents
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIncident(i: IncidentEntity)

    @Query("DELETE FROM incidents WHERE messageId IN (:ids)")
    suspend fun deleteIncidents(ids: List<Long>)

    @Query("SELECT * FROM incidents WHERE state = 'open' ORDER BY detectedAt DESC")
    fun openIncidents(): Flow<List<IncidentEntity>>

    @Query("UPDATE incidents SET state = 'dismissed' WHERE messageId = :id")
    suspend fun dismissIncident(id: Long)

    @Query("DELETE FROM incidents")
    suspend fun clearIncidents()
}

@Dao
interface ConfigDao {
    @Insert
    suspend fun insertRule(r: UserRuleEntity): Long

    @Update
    suspend fun updateRule(r: UserRuleEntity)

    @Query("DELETE FROM user_rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Query("SELECT * FROM user_rules ORDER BY id")
    fun observeRules(): Flow<List<UserRuleEntity>>

    @Query("SELECT * FROM user_rules WHERE isEnabled = 1 ORDER BY id")
    suspend fun enabledRules(): List<UserRuleEntity>

    @Query("SELECT * FROM user_rules ORDER BY id")
    suspend fun allRules(): List<UserRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(c: CustomCategoryEntity)

    @Query("DELETE FROM custom_categories WHERE `key` = :key")
    suspend fun deleteCategory(key: String)

    @Query("SELECT * FROM custom_categories ORDER BY name COLLATE NOCASE")
    fun observeCategories(): Flow<List<CustomCategoryEntity>>

    @Query("SELECT * FROM custom_categories")
    suspend fun categories(): List<CustomCategoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudget(b: BudgetEntity)

    @Query("DELETE FROM budgets WHERE bucket = :bucket")
    suspend fun deleteBudget(bucket: String)

    @Query("SELECT * FROM budgets ORDER BY bucket")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets")
    suspend fun budgets(): List<BudgetEntity>

    @Query("UPDATE budgets SET lastAlertMonth = :month WHERE bucket = :bucket")
    suspend fun setBudgetAlertMonth(bucket: String, month: Int)
}

@Dao
interface AttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AttachmentEntity>)

    @Query("DELETE FROM attachments WHERE messageId IN (:ids)")
    suspend fun deleteFor(ids: List<Long>)

    @Query("SELECT * FROM attachments WHERE messageId IN (:ids) ORDER BY messageId, partId")
    suspend fun forMessages(ids: List<Long>): List<AttachmentEntity>

    /** Total size of every stored attachment, for the backup screen's size estimate. */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM attachments WHERE messageId NOT IN (SELECT messageId FROM mms_downloads)")
    suspend fun totalBytes(): Long

    @Query("SELECT * FROM attachments WHERE messageId = :id ORDER BY partId")
    suspend fun forMessage(id: Long): List<AttachmentEntity>
}

@Dao
interface MmsDownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: MmsDownloadEntity)

    @Query("SELECT * FROM mms_downloads")
    fun observeAll(): Flow<List<MmsDownloadEntity>>

    @Query("SELECT * FROM mms_downloads WHERE messageId = :id")
    suspend fun get(id: Long): MmsDownloadEntity?

    @Query("SELECT * FROM mms_downloads WHERE transactionId = :tid LIMIT 1")
    suspend fun byTransaction(tid: String): MmsDownloadEntity?

    @Query("SELECT * FROM mms_downloads WHERE messageId IN (:ids)")
    suspend fun forMessages(ids: List<Long>): List<MmsDownloadEntity>

    @Query("DELETE FROM mms_downloads WHERE messageId IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("UPDATE mms_downloads SET state = :state, attempts = attempts + :attemptDelta, lastError = :error WHERE messageId = :id")
    suspend fun setState(id: Long, state: Int, attemptDelta: Int, error: Int)

    @Query("SELECT * FROM mms_downloads WHERE state = 2 AND attempts < :maxAttempts")
    suspend fun retryable(maxAttempts: Int): List<MmsDownloadEntity>
}

/** A message that has no current vector yet. */
data class PendingEmbedding(val id: Long, val body: String)

/** One stored vector as read back for a search. */
class StoredVector(val messageId: Long, val scale: Float, val vector: ByteArray)

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<MessageEmbeddingEntity>)

    @Query("DELETE FROM message_embeddings WHERE messageId IN (:ids)")
    suspend fun deleteFor(ids: List<Long>)

    @Query("DELETE FROM message_embeddings")
    suspend fun clear()

    /** Vectors whose message is gone (the message was deleted while its vector was being made). */
    @Query("DELETE FROM message_embeddings WHERE messageId NOT IN (SELECT id FROM messages)")
    suspend fun deleteOrphans(): Int

    /** Messages with text and no vector from [model], highest id first, walking down from [before]. */
    @Query(
        "SELECT m.id AS id, m.body AS body FROM messages m LEFT JOIN message_embeddings e ON e.messageId = m.id " +
            "WHERE m.id < :before AND (e.messageId IS NULL OR e.model != :model) AND length(trim(m.body)) > 0 " +
            "ORDER BY m.id DESC LIMIT :limit",
    )
    suspend fun pending(model: Int, before: Long, limit: Int): List<PendingEmbedding>

    @Query("SELECT messageId, scale, vector FROM message_embeddings WHERE model = :model AND messageId > :after ORDER BY messageId LIMIT :limit")
    suspend fun page(model: Int, after: Long, limit: Int): List<StoredVector>

    @Query("SELECT COUNT(*) FROM message_embeddings e JOIN messages m ON m.id = e.messageId WHERE e.model = :model")
    fun observeIndexed(model: Int): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE length(trim(body)) > 0")
    fun observeSearchable(): Flow<Int>
}

@Dao
interface TranslationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: TranslationEntity)

    @Query("DELETE FROM message_translations WHERE messageId IN (:ids)")
    suspend fun deleteFor(ids: List<Long>)

    /** The table is small — only messages that have been translated — so the UI reads it whole and filters. */
    @Query("SELECT * FROM message_translations")
    fun observeAll(): Flow<List<TranslationEntity>>
}

class SpamTotals(val conversations: Int, val messages: Int)
