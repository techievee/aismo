package aismo.sms

import android.provider.Telephony
import aismo.sms.data.sms.SmsProvider

/**
 * The emulated modem reports a multipart SMS one part at a time, seconds apart, and each report rewrites the
 * stored row by id. Provider ids restart after the table is emptied, so reports still arriving would hit
 * unrelated rows in the next test. Call this at the end of a test that sent texts: it lets every send finish
 * (sent and delivered, or failed) first.
 */
fun awaitRadioResults(provider: SmsProvider) {
    fun rows() = provider.readAfter(0, 100)
    if (rows().isEmpty()) return
    val deadline = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < deadline) {
        val open = rows().any {
            it.type == Telephony.Sms.MESSAGE_TYPE_OUTBOX || it.type == Telephony.Sms.MESSAGE_TYPE_QUEUED ||
                (it.type == Telephony.Sms.MESSAGE_TYPE_SENT && it.status != Telephony.Sms.STATUS_COMPLETE)
        }
        if (!open) break
        Thread.sleep(250)
    }
    Thread.sleep(1_000) // the receiver re-indexes the row after its last update
}
