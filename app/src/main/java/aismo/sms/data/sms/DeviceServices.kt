package aismo.sms.data.sms

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BlockedNumberContract
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.util.LruCache
import androidx.core.content.ContextCompat
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil

/** One active SIM: [name] is what the SIM calls itself (usually the carrier), [number] its phone number if it reports one. */
data class SimInfo(val subId: Int, val slot: Int, val name: String, val number: String?) {
    /** "SIM 1 · Optus", or just "SIM 1" when the SIM has no name of its own. */
    val title: String get() = if (name.isBlank() || name.equals("SIM $slot", ignoreCase = true)) "SIM $slot" else "SIM $slot · $name"

    /** The number, or a plain note that the SIM does not report one. */
    val detail: String get() = number ?: "Number not available"

    /** Everything on one line, for places with room for one line. */
    val label: String get() = listOfNotNull(title, number).joinToString(" · ")
}

/** Active SIM subscriptions, for the dual-SIM picker. Returns an empty list without READ_PHONE_STATE. */
class SimDirectory(private val context: Context) {
    @SuppressLint("MissingPermission")
    fun active(): List<SimInfo> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            sm.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }.map {
                SimInfo(
                    subId = it.subscriptionId,
                    slot = it.simSlotIndex + 1,
                    name = (it.displayName ?: it.carrierName)?.toString().orEmpty(),
                    number = numberOf(sm, it),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** The SIM the system sends texts from unless told otherwise, or null if it has none. */
    fun systemDefaultSmsSubId(): Int? =
        SubscriptionManager.getDefaultSmsSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun numberOf(sm: SubscriptionManager, info: android.telephony.SubscriptionInfo): String? {
        val raw = runCatching { if (Build.VERSION.SDK_INT >= 33) sm.getPhoneNumber(info.subscriptionId) else info.number }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { info.number }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        return prettyNumber(raw, info.countryIso)
    }

    /** International format when the number can be read, so "+15555215554" shows as "+1 555-521-5554"; the raw text otherwise. */
    private fun prettyNumber(raw: String, countryIso: String?): String = runCatching {
        val parsed = phoneNumbers.parse(raw, countryIso?.uppercase()?.takeIf { it.length == 2 })
        phoneNumbers.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
    }.getOrDefault(raw)

    private val phoneNumbers by lazy { PhoneNumberUtil.createInstance(context) }
}

/** Resolves addresses to contact names and photos, and searches contacts by name or number. */
class ContactDirectory(private val context: Context) {
    data class Match(val name: String, val number: String)

    private val names = LruCache<String, String>(512)
    private val misses = LruCache<String, Boolean>(512)

    private fun granted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun nameFor(address: String): String? {
        if (!granted() || address.isBlank()) return null
        names.get(address)?.let { return it }
        if (misses.get(address) == true) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        if (name != null) names.put(address, name) else misses.put(address, true)
        return name
    }

    fun invalidate() {
        names.evictAll()
        misses.evictAll()
    }

    fun isInContacts(address: String) = nameFor(address) != null

    fun search(query: String, limit: Int = 8): List<Match> {
        if (!granted() || query.isBlank()) return emptyList()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI.buildUpon().appendPath(query).build()
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC",
            )?.use { c ->
                buildList {
                    while (c.moveToNext() && size < limit) add(Match(c.getString(0).orEmpty(), c.getString(1).orEmpty()))
                }.distinctBy { it.number.filter(Char::isDigit) }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
}

/**
 * The system blocked-numbers list. Only the default SMS/dialer app may read or write it; every call
 * degrades to "not blocked" when we lack that role.
 */
class BlockedNumbers(private val context: Context) {
    fun canBlock(): Boolean = runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    fun isBlocked(number: String): Boolean =
        runCatching { BlockedNumberContract.isBlocked(context, number) }.getOrDefault(false)

    fun block(number: String): Boolean = runCatching {
        val values = ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
        context.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, values) != null
    }.getOrDefault(false)

    fun unblock(number: String): Boolean = runCatching { BlockedNumberContract.unblock(context, number) > 0 }.getOrDefault(false)

    fun all(): List<String> = runCatching {
        context.contentResolver.query(
            BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0).orEmpty()) } } ?: emptyList()
    }.getOrDefault(emptyList())
}
