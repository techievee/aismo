package aismo.sms.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MessageEntity::class,
        MessageFts::class,
        ConversationEntity::class,
        TransactionEntity::class,
        OtpEntity::class,
        ReminderEntity::class,
        UserRuleEntity::class,
        CustomCategoryEntity::class,
        BudgetEntity::class,
        IncidentEntity::class,
        AttachmentEntity::class,
        MmsDownloadEntity::class,
        MessageEmbeddingEntity::class,
        TranslationEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class AismoDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun conversations(): ConversationDao
    abstract fun insights(): InsightDao
    abstract fun config(): ConfigDao
    abstract fun attachments(): AttachmentDao
    abstract fun mmsDownloads(): MmsDownloadDao
    abstract fun embeddings(): EmbeddingDao
    abstract fun translations(): TranslationDao

    companion object {
        /** MMS support: subject and attachment summary on messages, plus attachment and download tables. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN subject TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaKind TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN attachmentCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS attachments (messageId INTEGER NOT NULL, partId INTEGER NOT NULL, mime TEXT NOT NULL, " +
                        "name TEXT, sizeBytes INTEGER NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(messageId, partId))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_messageId ON attachments (messageId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mms_downloads (messageId INTEGER NOT NULL, contentLocation TEXT NOT NULL, " +
                        "transactionId TEXT NOT NULL, subId INTEGER NOT NULL, sizeBytes INTEGER NOT NULL, expiresAt INTEGER, " +
                        "state INTEGER NOT NULL, attempts INTEGER NOT NULL, lastError INTEGER NOT NULL, createdAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(messageId))",
                )
            }
        }

        /** Search by meaning: one derived vector per message, rebuilt by a background job. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS message_embeddings (messageId INTEGER NOT NULL, model INTEGER NOT NULL, " +
                        "scale REAL NOT NULL, vector BLOB NOT NULL, PRIMARY KEY(messageId))",
                )
            }
        }

        /** Custom categories can have an icon of the user's choice; the ones that exist keep the plain tag. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_categories ADD COLUMN icon TEXT NOT NULL DEFAULT 'label'")
            }
        }

        /** Translation: one derived translation per message and target language. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS message_translations (messageId INTEGER NOT NULL, target TEXT NOT NULL, " +
                        "source TEXT, text TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(messageId, target))",
                )
            }
        }

        /** The conversation list shows a paperclip when the newest message carries attachments. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN latestAttachmentCount INTEGER NOT NULL DEFAULT 0")
                // Fill in what is already known from the existing newest messages, so the icons appear without waiting for new mail.
                db.execSQL(
                    "UPDATE conversations SET latestAttachmentCount = COALESCE(" +
                        "(SELECT m.attachmentCount FROM messages m WHERE m.id = conversations.latestMessageId), 0)",
                )
            }
        }

        fun create(context: Context): AismoDatabase =
            Room.databaseBuilder(context.applicationContext, AismoDatabase::class.java, "aismo.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()

        fun inMemory(context: Context): AismoDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AismoDatabase::class.java).build()
    }
}
