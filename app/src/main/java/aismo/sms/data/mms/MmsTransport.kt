package aismo.sms.data.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import aismo.sms.domain.mms.MmsBudget
import aismo.sms.telephony.smsManagerFor
import java.io.File

/**
 * The thin layer over the platform MMS service. The service reads and writes PDUs through content URIs,
 * so every PDU lives in a cache file exposed by a FileProvider that the MMS service is granted access to.
 */
class MmsTransport(private val context: Context) {
    private val authority = "${context.packageName}.mmsfiles"
    private val dir: File get() = File(context.cacheDir, "mms").also { it.mkdirs() }

    fun newFile(prefix: String): File = File(dir, "$prefix-${System.nanoTime()}.dat")

    /** Deletes PDU files old enough that no transfer can still be using them. */
    fun cleanOldFiles(maxAgeMs: Long = 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, authority, file).also(::grant)

    /** The framework grants access itself when the caller owns the provider; this is belt and braces. */
    private fun grant(uri: Uri) {
        for (pkg in listOf("com.android.phone", "com.android.mms.service")) {
            runCatching {
                context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
    }

    private fun carrierConfig(subId: Int): Bundle? = runCatching { context.smsManagerFor(subId).carrierConfigValues }.getOrNull()

    fun maxMessageBytes(subId: Int): Int =
        MmsBudget.clampMax(carrierConfig(subId)?.takeIf { it.containsKey(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE) }?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE))

    fun groupMmsEnabled(subId: Int): Boolean = carrierConfig(subId)?.getBoolean(SmsManager.MMS_CONFIG_GROUP_MMS_ENABLED, true) ?: true

    /** The carrier's "convert SMS to MMS after N segments" value; null when it sets none (-1). */
    fun smsToMmsSegments(subId: Int): Int? = carrierConfig(subId)?.getInt(SmsManager.MMS_CONFIG_SMS_TO_MMS_TEXT_THRESHOLD, -1)?.takeIf { it > 0 }

    /** The carrier's "convert SMS to MMS above N characters" value; null when it sets none. */
    fun smsToMmsChars(subId: Int): Int? = carrierConfig(subId)?.getInt(SmsManager.MMS_CONFIG_SMS_TO_MMS_TEXT_LENGTH_THRESHOLD, -1)?.takeIf { it > 0 }

    /** The largest text part the carrier allows in an MMS; null for no limit. */
    fun maxTextBytes(subId: Int): Int? = carrierConfig(subId)?.getInt(SmsManager.MMS_CONFIG_MESSAGE_TEXT_MAX_SIZE, -1)?.takeIf { it > 0 }

    fun mmsEnabledByCarrier(subId: Int): Boolean = carrierConfig(subId)?.getBoolean(SmsManager.MMS_CONFIG_MMS_ENABLED, true) ?: true

    /** Mobile data is on for the subscription; assumed true when it cannot be read. */
    fun mobileDataEnabled(subId: Int): Boolean = runCatching {
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java)
        (if (subId >= 0) tm.createForSubscriptionId(subId) else tm).isDataEnabled
    }.getOrDefault(true)

    fun recipientLimit(subId: Int): Int = carrierConfig(subId)?.getInt(SmsManager.MMS_CONFIG_RECIPIENT_LIMIT, Int.MAX_VALUE)?.takeIf { it > 0 } ?: Int.MAX_VALUE

    /** Asks the platform to fetch [contentLocation] into [file]; the result arrives on [result]. */
    fun download(subId: Int, contentLocation: String, file: File, result: PendingIntent) {
        context.smsManagerFor(subId).downloadMultimediaMessage(context, contentLocation, uriFor(file), null, result)
    }

    /** Asks the platform to POST the PDU in [file] to the MMSC; the response arrives on [result], if given. */
    fun send(subId: Int, file: File, result: PendingIntent?) {
        context.smsManagerFor(subId).sendMultimediaMessage(context, uriFor(file), null, null, result)
    }
}
