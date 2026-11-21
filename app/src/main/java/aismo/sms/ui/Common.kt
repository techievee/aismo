package aismo.sms.ui

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import aismo.sms.AppContainer
import aismo.sms.container
import aismo.sms.domain.ConversationKeys
import aismo.sms.domain.SmsCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun Context.isDefaultSmsApp(): Boolean =
    getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true

fun Context.hasPermission(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

fun Context.canReadSms() = hasPermission(Manifest.permission.READ_SMS)

val RuntimePermissions: Array<String> = arrayOf(
    Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
    Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE, Manifest.permission.POST_NOTIFICATIONS,
)

@Composable
inline fun <reified VM : ViewModel> containerViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val c = LocalContext.current.container
    return viewModel(key = key, factory = viewModelFactory { initializer { create(c) } })
}

/** Looks up a contact name off the main thread; emits the cached or raw fallback straight away. */
@Composable
fun rememberContactName(address: String): State<String?> {
    val contacts = LocalContext.current.container.contacts
    return produceState<String?>(initialValue = null, address) {
        value = withContext(Dispatchers.IO) { contacts.nameFor(address) }
    }
}

fun displayTitle(address: String, brandTitle: String?, contactName: String?): String =
    contactName ?: brandTitle ?: address

/** Contact name, brand or number; a group is its members' names (first three, then "+N"). */
fun conversationTitle(contacts: aismo.sms.data.sms.ContactDirectory, address: String, brandTitle: String?): String {
    if (!ConversationKeys.isGroup(address)) return displayTitle(address, brandTitle, contacts.nameFor(address))
    val names = ConversationKeys.participants(address).map { contacts.nameFor(it) ?: it }
    return if (names.size <= 3) names.joinToString(", ") else names.take(3).joinToString(", ") + " +${names.size - 3}"
}

@Composable
fun rememberConversationTitle(address: String, brandTitle: String?): State<String> {
    val contacts = LocalContext.current.container.contacts
    return produceState(initialValue = brandTitle ?: address, address, brandTitle) {
        value = withContext(Dispatchers.IO) { conversationTitle(contacts, address, brandTitle) }
    }
}

fun categoryLabel(key: String, custom: Map<String, String>): String =
    SmsCategory.fromKey(key)?.label ?: custom[key] ?: "Other"

private val zone: ZoneId get() = ZoneId.systemDefault()

fun formatListTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val d = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = d.toLocalDate()
    return when {
        day == today -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(d)
        day == today.minusDays(1) -> "Yesterday"
        day.isAfter(today.minusDays(7)) -> DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(d)
        day.year == today.year -> DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()).format(d)
        else -> DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(d)
    }
}

fun formatTime(millis: Long): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Instant.ofEpochMilli(millis).atZone(zone))

fun formatDay(millis: Long, now: Long = System.currentTimeMillis()): String {
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(day)
    }
}

fun formatDateTime(millis: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(Instant.ofEpochMilli(millis).atZone(zone))

fun sameDay(a: Long, b: Long) = Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()

fun LocalDate.monthTitle(): String = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(this)

/** SMS segments and characters left in the last one, counted the way a handset does (GSM-7 vs UCS-2). */
fun smsCounter(text: String): Pair<Int, Int> = aismo.sms.domain.sms.SmsSegments.measure(text).let { it.segments to it.remaining }

fun isPhone(address: String) = ConversationKeys.isPhoneNumber(address)

/**
 * Hides the status bar while [on] is true so the content reaches the top of the display, but deliberately keeps the
 * navigation bar (back / home / recents) permanently visible so it is always reachable. The status bar is restored
 * when [on] is false or the screen is disposed; a swipe from the top edge brings it back sooner.
 */
@Composable
fun ImmersiveWhileVisible(on: Boolean = true) {
    val view = LocalView.current
    val activity = view.context as? Activity
    DisposableEffect(on, activity) {
        val controller = activity?.let { WindowInsetsControllerCompat(it.window, view) }
        if (on && controller != null) controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { if (controller != null) controller.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

/** A small "MMS" pill for the conversation and search lists — at a glance that the newest message carries media. */
@Composable
fun MmsBadge() {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(Icons.Filled.PermMedia, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
        Text("MMS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}
