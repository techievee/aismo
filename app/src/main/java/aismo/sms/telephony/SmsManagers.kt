package aismo.sms.telephony

import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/** The SmsManager for a subscription (-1 means the system default), across API levels. */
@Suppress("DEPRECATION")
fun Context.smsManagerFor(subId: Int): SmsManager =
    if (Build.VERSION.SDK_INT >= 31) {
        val base = getSystemService(SmsManager::class.java)
        if (subId >= 0) base.createForSubscriptionId(subId) else base
    } else if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
