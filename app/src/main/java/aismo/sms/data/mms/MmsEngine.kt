package aismo.sms.data.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Telephony.Mms
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import aismo.sms.data.IngestedMessage
import aismo.sms.data.MessageRepository
import aismo.sms.data.db.MessageIds
import aismo.sms.data.db.MmsDownloadEntity
import aismo.sms.data.db.AismoDatabase
import aismo.sms.data.settings.SettingsRepository
import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.mms.MmsAddress
import aismo.sms.domain.mms.MmsCharset
import aismo.sms.domain.mms.MmsDecoder
import aismo.sms.domain.mms.MmsEncoder
import aismo.sms.domain.mms.MmsPart
import aismo.sms.domain.mms.MmsPdu
import aismo.sms.domain.mms.MmsStatus
import aismo.sms.domain.mms.MmsType
import aismo.sms.domain.mms.SmilBuilder
import aismo.sms.telephony.MmsDownloadedReceiver
import aismo.sms.telephony.MmsSentReceiver
import aismo.sms.work.Workers
import java.io.File
import java.util.UUID

sealed interface MmsSendResult {
    data class Queued(val messageId: Long) : MmsSendResult
    data class Failed(val reason: String) : MmsSendResult
}

/**
 * Runs the MMS protocol on top of the platform MMS service: reacts to WAP-push notifications, downloads
 * and stores messages, acknowledges them to the MMSC, and composes and sends outgoing messages.
 */
class MmsEngine(
    private val context: Context,
    private val db: AismoDatabase,
    private val mms: MmsProvider,
    private val repository: MessageRepository,
    private val settings: SettingsRepository,
    private val ownNumbers: OwnNumbers,
    private val transport: MmsTransport,
    private val notify: suspend (List<IngestedMessage>) -> Unit,
) {
    // ------------------------------------------------------------------ receiving

    /** A WAP-push arrived. Only m-notification-ind starts a download; other PDU types are ignored. */
    suspend fun onPush(data: ByteArray, subId: Int) = withContext(Dispatchers.IO) {
        // The push is network noise, not user input: any failure is swallowed so the app never dies over it.
        runCatching {
            val pdu = runCatching { MmsDecoder.decode(data) }.getOrNull() ?: return@withContext
            if (pdu.type != MmsType.NOTIFICATION_IND) return@withContext
            val tid = pdu.transactionId ?: return@withContext
            if (pdu.contentLocation.isNullOrBlank()) return@withContext
            if (db.mmsDownloads().byTransaction(tid) != null) return@withContext // the network re-sent the same notification

            val raw = mms.insertNotification(pdu, subId, System.currentTimeMillis())
            if (raw < 0) return@withContext
            val id = MessageIds.mms(raw)
            val placeholder = repository.refresh(listOf(id))
            val s = settings.current()
            if (s.autoDownloadMms && (!isRoaming(subId) || s.downloadMmsWhileRoaming)) startDownload(id) else notify(placeholder)
        }
    }

    private fun isRoaming(subId: Int): Boolean = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
        (if (subId >= 0) tm.createForSubscriptionId(subId) else tm).isNetworkRoaming
    }.getOrDefault(false)

    /** Starts (or restarts) the download of a pending MMS. Returns false when there is nothing to download. */
    suspend fun startDownload(messageId: Long): Boolean = withContext(Dispatchers.IO) {
        // The user taps "download" here, but a failure must only mean "not yet", never a crash.
        runCatching {
            val d = db.mmsDownloads().get(messageId) ?: return@withContext false
            if (d.expiresAt != null && System.currentTimeMillis() > d.expiresAt) {
                fail(d, SmsManager.MMS_ERROR_UNSPECIFIED, retry = false)
                return@withContext false
            }
            val file = transport.newFile("dl").also { it.createNewFile() }
            db.mmsDownloads().setState(messageId, MmsDownloadEntity.DOWNLOADING, attemptDelta = 1, error = 0)
            Workers.scheduleMmsWatchdog(context, Workers.KIND_DOWNLOAD, messageId, DOWNLOAD_WATCHDOG_MINUTES)
            runCatching {
                transport.download(d.subId, d.contentLocation, file, resultIntent(MmsDownloadedReceiver::class.java, ACTION_DOWNLOADED, messageId, file))
            }.onFailure {
                file.delete()
                fail(d, SmsManager.MMS_ERROR_UNSPECIFIED, retry = false)
                return@withContext false
            }
            true
        }.getOrDefault(false)
    }

    /** The platform finished a download attempt. */
    suspend fun onDownloaded(messageId: Long, path: String, resultCode: Int) = withContext(Dispatchers.IO) {
        // The platform's result can arrive with a half-deleted file or a missing row; any failure here
        // means "retry later", never a crash.
        runCatching {
            Workers.cancelMmsWatchdog(context, Workers.KIND_DOWNLOAD, messageId)
            val file = File(path)
            val d = db.mmsDownloads().get(messageId)
            if (d == null) { file.delete(); return@withContext }
            if (resultCode != Activity.RESULT_OK) {
                file.delete()
                fail(d, resultCode, retry = isRetryable(resultCode))
                return@withContext
            }
            val pdu = runCatching { MmsDecoder.decode(file.readBytes()) }.getOrNull()
            file.delete()
            if (pdu == null || pdu.type != MmsType.RETRIEVE_CONF) {
                fail(d, SmsManager.MMS_ERROR_UNSPECIFIED, retry = false)
                return@withContext
            }
            val own = ownNumbers.keys(settings.current().ownNumber)
            val others = (listOfNotNull(pdu.from) + pdu.to + pdu.cc).map { it.value }.filter { ConversationKeys.of(it) !in own }.toSet()
            val raw = mms.insertRetrieved(pdu, d.subId, System.currentTimeMillis(), others)
            if (raw < 0) {
                fail(d, SmsManager.MMS_ERROR_UNSPECIFIED, retry = true)
                return@withContext
            }
            val stored = repository.refresh(listOf(MessageIds.mms(raw)))
            repository.deleteMessages(listOf(messageId)) // the "downloading" placeholder is replaced by the real message
            acknowledge(d, MmsStatus.RETRIEVED)
            notify(stored)
        }
    }

    private suspend fun fail(d: MmsDownloadEntity, code: Int, retry: Boolean) {
        db.mmsDownloads().setState(d.messageId, MmsDownloadEntity.FAILED, attemptDelta = 0, error = code)
        val attempts = (db.mmsDownloads().get(d.messageId)?.attempts ?: d.attempts)
        if (retry && attempts < MAX_ATTEMPTS) {
            Workers.scheduleMmsRetry(context)
        } else {
            // Out of automatic attempts: tell the user once, and keep the placeholder so they can retry by hand.
            notify(listOfNotNull(placeholderOf(d)))
        }
    }

    private suspend fun placeholderOf(d: MmsDownloadEntity): IngestedMessage? {
        val m = db.messages().get(d.messageId) ?: return null
        return IngestedMessage(m, aismo.sms.domain.parser.ParsedSms.empty(m.category), isBlocked = false, isMmsPlaceholder = true)
    }

    /** Re-tries downloads that failed for a transient reason (called by WorkManager). */
    suspend fun retryPending() = runCatching {
        for (d in db.mmsDownloads().retryable(MAX_ATTEMPTS)) {
            if (isRetryable(d.lastError)) startDownload(d.messageId)
        }
    }

    private fun isRetryable(code: Int) = code in RETRYABLE

    /** Tells the MMSC the message was retrieved so it stops re-notifying. */
    private fun acknowledge(d: MmsDownloadEntity, status: Int) {
        val file = transport.newFile("ack")
        runCatching {
            file.writeBytes(MmsEncoder.encodeNotifyResp(d.transactionId, status))
            transport.send(d.subId, file, null)
        }.onFailure { file.delete() }
    }

    // -------------------------------------------------------------------- sending

    /**
     * Stores an outgoing MMS in the outbox and hands it to the platform. Recipients are phone numbers or
     * e-mail addresses; with more than one, the message is a group MMS unless the carrier disallows it,
     * in which case each recipient gets their own copy.
     */
    suspend fun send(
        recipients: List<String>, text: String, attachments: List<OutgoingAttachment>, subId: Int, subject: String? = null,
        convertedLongText: Boolean = false, key: String? = null,
    ): MmsSendResult =
        withContext(Dispatchers.IO) {
            val to = recipients.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (to.isEmpty()) return@withContext MmsSendResult.Failed("No recipient")
            if (text.isBlank() && attachments.isEmpty()) return@withContext MmsSendResult.Failed("Nothing to send")
            if (to.size > transport.recipientLimit(subId)) return@withContext MmsSendResult.Failed("Too many recipients for this carrier")

            val bodyParts = try {
                AttachmentPreparer.prepare(attachments, text, transport.maxMessageBytes(subId))
            } catch (e: MmsTooLargeException) {
                return@withContext MmsSendResult.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                // A picked file can vanish or prove unreadable by send time (cleaned cache, revoked grant):
                // that is a failed send with a message, not a crash.
                return@withContext MmsSendResult.Failed("Couldn't prepare the attachments")
            }
            val textPart = text.trim().takeIf { it.isNotEmpty() }?.let { MmsPart("text/plain", "text_0.txt", "text_0", MmsCharset.UTF_8, it.toByteArray(Charsets.UTF_8)) }
            val smil = SmilBuilder.build(textPart, bodyParts)
            val parts = listOf(MmsPart("application/smil", "smil.xml", "smil", null, smil.toByteArray())) + listOfNotNull(textPart) + bodyParts

            val groups = if (to.size > 1 && !transport.groupMmsEnabled(subId)) to.map { listOf(it) } else listOf(to)
            var firstId = -1L
            for (group in groups) {
                val tid = (if (convertedLongText) TID_LONG_TEXT else TID_PREFIX) + UUID.randomUUID().toString().replace("-", "").take(12)
                val raw = mms.insertOutgoing(group, subject, parts, subId, tid)
                if (raw < 0) return@withContext MmsSendResult.Failed("Couldn't store the message")
                val id = MessageIds.mms(raw)
                if (firstId < 0) firstId = id
                repository.refresh(listOf(id), key?.let { mapOf(id to it) } ?: emptyMap())
                dispatch(id)
            }
            MmsSendResult.Queued(firstId)
        }

    /** Re-sends a failed MMS from what is stored in the provider. */
    suspend fun resend(messageId: Long) = withContext(Dispatchers.IO) {
        mms.updateBox(MessageIds.raw(messageId), Mms.MESSAGE_BOX_OUTBOX)
        repository.refresh(listOf(messageId))
        dispatch(messageId)
    }

    private suspend fun dispatch(messageId: Long) {
        val raw = MessageIds.raw(messageId)
        val row = mms.readById(raw)
        val pdu = row?.let { buildSendReq(it) }
        if (row == null || pdu == null) { markFailed(messageId); return }
        val file = transport.newFile("snd")
        Workers.scheduleMmsWatchdog(context, Workers.KIND_SEND, messageId, SEND_WATCHDOG_MINUTES)
        runCatching {
            file.writeBytes(MmsEncoder.encodeSendReq(pdu))
            transport.send(row.subId, file, resultIntent(MmsSentReceiver::class.java, ACTION_SENT, messageId, file))
        }.onFailure { file.delete(); markFailed(messageId) }
    }

    private fun buildSendReq(row: StoredMms): MmsPdu? {
        val tid = row.transactionId ?: return null
        val parts = mms.parts(row.rawId).mapNotNull { p ->
            // A part file that vanished by send time (cleaned cache) is a part we cannot send, not a crash.
            val data = if (p.text != null) p.text.toByteArray(Charsets.UTF_8) else runCatching { mms.openPart(p.partId)?.use { it.readBytes() } }.getOrNull() ?: return@mapNotNull null
            MmsPart(p.mime, p.name, p.name?.substringBeforeLast('.'), if (p.mime.startsWith("text/")) MmsCharset.UTF_8 else null, data)
        }
        return MmsPdu(
            type = MmsType.SEND_REQ, transactionId = tid, dateSeconds = row.dateMs / 1000,
            to = row.to.map { MmsAddress.of(it) }, cc = row.cc.map { MmsAddress.of(it) }, subject = row.subject, parts = parts,
        )
    }

    /** The platform finished a send attempt. [response] is the MMSC's m-send-conf, when the platform returns it. */
    suspend fun onSent(messageId: Long, path: String, resultCode: Int, response: ByteArray?) = withContext(Dispatchers.IO) {
        Workers.cancelMmsWatchdog(context, Workers.KIND_SEND, messageId)
        File(path).delete()
        val raw = MessageIds.raw(messageId)
        if (resultCode == Activity.RESULT_OK) {
            val conf = response?.let { runCatching { MmsDecoder.decode(it) }.getOrNull() }
            val accepted = conf?.responseStatus?.let { it == MmsStatus.RESPONSE_OK } ?: true
            if (accepted) mms.updateAfterSend(raw, Mms.MESSAGE_BOX_SENT, conf?.messageId, conf?.responseStatus)
            else mms.updateAfterSend(raw, Mms.MESSAGE_BOX_FAILED, conf?.messageId, conf?.responseStatus)
        } else {
            mms.updateBox(raw, Mms.MESSAGE_BOX_FAILED)
        }
        repository.refresh(listOf(messageId))
    }

    /**
     * Called by the watchdog when a transfer produced no result for too long: a download still marked
     * "downloading", or a message still in the outbox, is failed so the user can retry it.
     */
    suspend fun expireStale(kind: String, messageId: Long) = withContext(Dispatchers.IO) {
        // A watchdog firing on a row that vanished (or a provider that blinked) must not take the app down.
        runCatching {
            if (kind == Workers.KIND_DOWNLOAD) {
                val d = db.mmsDownloads().get(messageId) ?: return@withContext
                if (d.state == MmsDownloadEntity.DOWNLOADING) fail(d, SmsManager.MMS_ERROR_RETRY, retry = true)
            } else if (mms.readById(MessageIds.raw(messageId))?.box == Mms.MESSAGE_BOX_OUTBOX) {
                markFailed(messageId)
            }
        }
    }

    private suspend fun markFailed(messageId: Long) {
        mms.updateBox(MessageIds.raw(messageId), Mms.MESSAGE_BOX_FAILED)
        repository.refresh(listOf(messageId))
    }

    // -------------------------------------------------------------------- helpers

    private fun resultIntent(receiver: Class<*>, action: String, messageId: Long, file: File): PendingIntent {
        val intent = Intent(context, receiver).setAction(action)
            .putExtra(EXTRA_MESSAGE_ID, messageId).putExtra(EXTRA_PATH, file.absolutePath)
        // Mutable: the platform adds the HTTP status and response data to the intent before delivering it.
        return PendingIntent.getBroadcast(context, (messageId.hashCode() * 31) xor action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    companion object {
        const val EXTRA_MESSAGE_ID = "mms_message_id"
        const val EXTRA_PATH = "mms_file"
        const val ACTION_DOWNLOADED = "aismo.sms.action.MMS_DOWNLOADED"
        const val ACTION_SENT = "aismo.sms.action.MMS_SENT"
        const val MAX_ATTEMPTS = 5
        const val TID_PREFIX = "aism"
        /** Transaction ids of texts that were converted from SMS, so the UI can offer "send as text". */
        const val TID_LONG_TEXT = "aism-long"
        private const val DOWNLOAD_WATCHDOG_MINUTES = 6L
        private const val SEND_WATCHDOG_MINUTES = 10L

        /** Failures that are worth retrying later (no data connection, flaky network). */
        private val RETRYABLE = setOf(
            SmsManager.MMS_ERROR_NO_DATA_NETWORK, SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS, SmsManager.MMS_ERROR_RETRY,
            SmsManager.MMS_ERROR_IO_ERROR, SmsManager.MMS_ERROR_HTTP_FAILURE, SmsManager.MMS_ERROR_DATA_DISABLED,
        )
    }
}
