package aismo.sms.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import aismo.sms.data.HeldMessage
import kotlin.math.ceil
import kotlin.math.max

/** Shown above the composer while a sent message waits out its undo window: the seconds left, Undo, and Send now. */
@Composable
fun HeldSendBanner(held: HeldMessage, onUndo: () -> Unit, onSendNow: () -> Unit) {
    var remaining by remember(held.id) { mutableLongStateOf(max(0, held.releaseAt - SystemClock.elapsedRealtime())) }
    LaunchedEffect(held.id) {
        while (remaining > 0) {
            delay(100)
            remaining = max(0, held.releaseAt - SystemClock.elapsedRealtime())
        }
    }
    val total = (held.releaseAt - held.heldAt).coerceAtLeast(1)
    val seconds = ceil(remaining / 1000.0).toInt()
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(progress = { remaining.toFloat() / total }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(if (seconds > 0) "Sending in $seconds s" else "Sending…", style = MaterialTheme.typography.labelLarge)
                Text(preview(held), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onUndo) { Text("Undo") }
            TextButton(onClick = onSendNow) { Text("Send now") }
        }
    }
}

private fun preview(m: HeldMessage): String = m.text.replace('\n', ' ').trim().ifEmpty {
    if (m.attachments.size == 1) "1 attachment" else "${m.attachments.size} attachments"
}
