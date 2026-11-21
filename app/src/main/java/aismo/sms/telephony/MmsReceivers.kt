package aismo.sms.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.telephony.SmsManager
import kotlinx.coroutines.launch
import aismo.sms.container
import aismo.sms.data.mms.MmsEngine

private const val TAG = "TsoMms"

/** The WAP-push that announces an incoming MMS. Only the default SMS app receives this broadcast. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data") ?: return
        val subId = intent.getIntExtra("subscription", -1)
        val pending: PendingResult? = goAsync()
        val c = context.container
        c.scope.launch {
            try { c.mmsEngine.onPush(data, subId) }
            catch (e: Exception) { Log.w(TAG, "onPush", e) } // a bad push must not kill the process
            finally { pending?.finish() }
        }
    }
}

/** The platform finished downloading an MMS into the cache file we gave it. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(MmsEngine.EXTRA_MESSAGE_ID, -1)
        val path = intent.getStringExtra(MmsEngine.EXTRA_PATH) ?: return
        if (id < 0) return
        val result = resultCode
        Log.i(TAG, "download result=$result http=${intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)}")
        val pending: PendingResult? = goAsync()
        val c = context.container
        c.scope.launch {
            try { c.mmsEngine.onDownloaded(id, path, result) }
            catch (e: Exception) { Log.w(TAG, "onDownloaded", e) } // a bad result must not kill the process
            finally { pending?.finish() }
        }
    }
}

/** The platform finished sending an MMS; the MMSC's response (m-send-conf) rides along as an extra. */
class MmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(MmsEngine.EXTRA_MESSAGE_ID, -1)
        val path = intent.getStringExtra(MmsEngine.EXTRA_PATH) ?: return
        if (id < 0) return
        val result = resultCode
        val response = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
        Log.i(TAG, "send result=$result http=${intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0)} response=${response?.size ?: -1}B")
        val pending: PendingResult? = goAsync()
        val c = context.container
        c.scope.launch {
            try { c.mmsEngine.onSent(id, path, result, response) }
            catch (e: Exception) { Log.w(TAG, "onSent", e) } // a bad result must not kill the process
            finally { pending?.finish() }
        }
    }
}
