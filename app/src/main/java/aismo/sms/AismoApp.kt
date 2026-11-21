package aismo.sms

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.provider.Telephony
import androidx.biometric.BiometricManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import aismo.sms.backup.BackupService
import aismo.sms.backup.KeystoreVault
import aismo.sms.data.IngestedMessage
import aismo.sms.data.MessageRepository
import aismo.sms.data.OutgoingMessages
import aismo.sms.data.PlatformMmsConditions
import aismo.sms.data.db.AismoDatabase
import aismo.sms.data.SendHold
import aismo.sms.data.security.CategorySecurity
import aismo.sms.data.security.VaultPinStore
import aismo.sms.data.SpamReviewReminder
import aismo.sms.data.ai.OnnxTextEmbedder
import aismo.sms.data.ai.SemanticIndex
import aismo.sms.data.llm.LiteRtLmGenerator
import aismo.sms.data.llm.LlamaCppGenerator
import aismo.sms.data.llm.ModelStore
import aismo.sms.data.llm.RephraseService
import aismo.sms.data.mms.AttachmentStore
import aismo.sms.data.sms.SimSelector
import aismo.sms.data.mms.MmsEngine
import aismo.sms.data.mms.MmsProvider
import aismo.sms.data.mms.MmsTransport
import aismo.sms.data.mms.OwnNumbers
import aismo.sms.data.settings.SettingsRepository
import aismo.sms.data.sms.BlockedNumbers
import aismo.sms.data.sms.ContactDirectory
import aismo.sms.data.sms.SimDirectory
import aismo.sms.data.sms.SmsProvider
import aismo.sms.domain.FinanceService
import aismo.sms.domain.parser.SmsParser
import aismo.sms.domain.llm.LlmBackend
import aismo.sms.domain.rephrase.RephrasePrompts
import aismo.sms.domain.translate.Language
import aismo.sms.domain.translate.LanguageGuesser
import aismo.sms.domain.translate.TranslationPrompts
import aismo.sms.domain.translate.TranslationResult
import aismo.sms.domain.translate.defaultTarget
import aismo.sms.telephony.ActiveConversation
import aismo.sms.telephony.IncomingProcessor
import aismo.sms.telephony.NotificationCenter
import aismo.sms.telephony.SmsSender
import aismo.sms.work.Workers
import java.io.File
import java.util.Currency
import java.util.Locale

/** Hand-rolled dependency container; one instance lives as long as the process. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: AismoDatabase by lazy { AismoDatabase.create(app) }
    val settings by lazy { SettingsRepository(app) }
    val provider by lazy { SmsProvider(app) }
    val contacts by lazy { ContactDirectory(app) }
    val blocked by lazy { BlockedNumbers(app) }
    val sims by lazy { SimDirectory(app) }
    val notifications by lazy { NotificationCenter(app) }
    val parser by lazy { SmsParser(dollarCurrency = dollarCurrencyForLocale()) }
    val mmsProvider by lazy { MmsProvider(app) }
    val ownNumbers by lazy { OwnNumbers(app) }
    val repository by lazy { MessageRepository(db, provider, mmsProvider, ownNumbers, blocked, settings, parser) }
    val finance by lazy { FinanceService(db, settings, notifications) }
    val sender by lazy { SmsSender(app, provider, repository, settings) }
    val incoming by lazy {
        IncomingProcessor(
            provider, repository, contacts, settings, notifications, finance,
            onNewMessages = { Workers.scheduleEmbedding(app) },
            onTranslate = { m -> scope.launch { translateIncoming(m) } },
        )
    }
    val mmsTransport by lazy { MmsTransport(app) }
    val attachmentStore by lazy { AttachmentStore(app, mmsProvider) }
    val mmsEngine by lazy { MmsEngine(app, db, mmsProvider, repository, settings, ownNumbers, mmsTransport) { incoming.notifyFor(it) } }
    val outgoing by lazy { OutgoingMessages(sender, mmsEngine, mmsProvider, repository, settings, PlatformMmsConditions(mmsTransport)) }
    val simSelector by lazy { SimSelector(db, settings, { sims.active().map { it.subId } }, { sims.systemDefaultSmsSubId() }) }
    val spamReview by lazy { SpamReviewReminder(db, settings, notifications) }

    /** Messages waiting out their undo window. Owned here, not by a screen, so leaving a conversation neither cancels nor loses one. */
    val sendHold by lazy {
        SendHold(
            scope, now = SystemClock::elapsedRealtime,
            dispatch = { m -> outgoing.send(m.recipients, m.text, m.attachments, m.subId, m.conversationKey) }, // the message goes out into the thread it was held in
            onDelivered = { m -> m.attachments.forEach(attachmentStore::discard) },
            onFailed = { f ->
                // A conversation that is open claims the failure itself and puts the text back; otherwise say so in a notification.
                if (ActiveConversation.key != f.message.conversationKey) scope.launch(Dispatchers.IO) {
                    val address = f.message.recipients.joinToString(", ") { contacts.nameFor(it) ?: it }
                    notifications.showSendFailed(f.message.conversationKey, address, f.reason)
                }
            },
        )
    }

    /** A category the inbox should show next (set when a notification asks for one), cleared once the inbox has taken it. */
    val requestedCategory = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val embedder by lazy { OnnxTextEmbedder(app) }
    val semanticIndex by lazy { SemanticIndex(db, embedder) }
    val vault by lazy { KeystoreVault(app) }
    val security by lazy {
        CategorySecurity(
            scope,
            now = SystemClock::elapsedRealtime,
            pin = VaultPinStore(vault),
            // The androidx library (not the framework class, which Android 17 reworked): the default prompt never
            // offers the device credential, so this is true only when a biometric is actually enrolled.
            biometricEnrolled = { BiometricManager.from(app).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS },
        )
    }
    val backup by lazy { BackupService(app, db, provider, mmsProvider, repository, settings, ownNumbers) }

    /** The writing assistant's model files live in app-private storage that is neither backed up nor synced. */
    val modelStore by lazy { ModelStore(File(app.noBackupFilesDir, "llm")) }
    private val rephrasePrompts by lazy { RephrasePrompts.parse(app.assets.open("rephrase/prompts.json").bufferedReader().use { it.readText() }) }
    private val translationPrompts by lazy { TranslationPrompts.parse(app.assets.open("translate/prompts.json").bufferedReader().use { it.readText() }) }
    val rephrase by lazy {
        RephraseService(
            modelStore,
            chosenBackend = { settings.current().rephraseBackend },
            prompts = { rephrasePrompts },
            translationPrompts = translationPrompts,
            newGenerator = { backend, file ->
                when (backend) {
                    LlmBackend.LLAMA_CPP -> LlamaCppGenerator(app, file)
                    LlmBackend.LITERT_LM -> LiteRtLmGenerator(app, file)
                }
            },
            scope = scope,
        )
    }

    /**
     * An incoming message that is not in the user's language is translated on this phone and stored under the
     * message; the original text is never touched. Messages that are already in the user's language, or phones
     * without a model, are left alone.
     */
    private fun translateIncoming(m: IngestedMessage) {
        scope.launch {
            val s = settings.current()
            val target = Language.fromKey(s.translateTargetLang) ?: defaultTarget(Locale.getDefault().language)
            val guess = LanguageGuesser.guess(m.message.body) ?: Language.EN
            if (guess == target || modelStore.installed(s.rephraseBackend) == null) return@launch
            when (val r = rephrase.translate(m.message.body, guess, target)) {
                is TranslationResult.Success -> repository.saveTranslation(m.message.id, guess, target, r.text)
                else -> Unit
            }
        }
    }

    private fun dollarCurrencyForLocale(): String = runCatching {
        val code = Currency.getInstance(Locale.getDefault()).currencyCode
        if (code in DOLLARS) code else "USD"
    }.getOrDefault("USD")

    private companion object {
        val DOLLARS = setOf("USD", "AUD", "CAD", "NZD", "SGD", "HKD")
    }
}

class AismoApp : Application() {
    lateinit var container: AppContainer
        private set

    private var syncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifications.createChannels()
        container.scope.launch { runCatching { container.mmsTransport.cleanOldFiles(); container.attachmentStore.cleanOldFiles() } }
        Workers.schedule(this)
        observeProvider()
        // A held message must not wait where nobody can undo it: when the app leaves the screen it goes out.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            // The unlock window is measured, not counted: a phone that slept past it is locked again on return.
            override fun onStart(owner: LifecycleOwner) = container.security.recheck()
            override fun onStop(owner: LifecycleOwner) = container.sendHold.flushAll()
        })
    }

    /** A language model is the largest thing this app holds in memory, so it goes first when the system wants memory back. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) container.scope.launch { runCatching { container.rephrase.releaseAll() } }
    }

    /** Keeps the local index in step when another app or the system changes the SMS provider. */
    private fun observeProvider() {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                syncJob?.cancel()
                syncJob = container.scope.launch {
                    delay(700) // coalesce bursts of provider writes
                    runCatching { container.repository.syncRecent() }
                    Workers.scheduleEmbedding(this@AismoApp)
                }
            }
        }
        runCatching { contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer) }
    }
}

val Context.container: AppContainer get() = (applicationContext as AismoApp).container
