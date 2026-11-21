package aismo.sms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import aismo.sms.data.sms.SimInfo

/**
 * Lists each SIM with its name and its number, so two SIMs from the same carrier can still be told apart.
 * [selectedSubId] of -1 selects the "system default" row when [allowSystemDefault] is set.
 */
@Composable
fun SimPickerDialog(
    title: String,
    sims: List<SimInfo>,
    selectedSubId: Int,
    allowSystemDefault: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (allowSystemDefault) SimRow("Use system default", "The SIM chosen in your phone's settings", selectedSubId < 0) { onPick(-1); onDismiss() }
                sims.forEach { sim -> SimRow(sim.title, sim.detail, sim.subId == selectedSubId) { onPick(sim.subId); onDismiss() } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SimRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The "send from" control of a composer row: a filled pill, the same height as the other composer
 * buttons, carrying the SIM's number so two SIMs are told apart at a glance. Tapping opens the
 * picker, where each SIM's full name and number are shown.
 *
 * The chip should be given [Modifier.weight] (it is not one itself, so it can only take a share of
 * the row on a [androidx.compose.foundation.layout.RowScope]); between its minimum and maximum width
 * it simply sizes to its text, so a wide phone shows the full number while a narrow one shrinks it
 * before the text field loses ground.
 */
@Composable
fun SimChip(sim: SimInfo, sims: List<SimInfo>, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier
            .height(48.dp)
            .widthIn(min = 90.dp, max = 150.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp)
            .clickable(role = Role.Button, onClick = { picking = true }),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.SimCard, "Send from ${sim.title}", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(sim.number ?: sim.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
    if (picking) SimPickerDialog("Send from", sims, sim.subId, allowSystemDefault = false, onPick = onPick) { picking = false }
}

/**
 * The narrow-screen form of [SimChip] for a composer row too tight to show the number: the SIM icon
 * in the standard filled composer-button circle, opening the same picker.
 */
@Composable
fun SimIconButton(sim: SimInfo, sims: List<SimInfo>, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = { picking = true }),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.SimCard, "Send from ${sim.title}", Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) }
    if (picking) SimPickerDialog("Send from", sims, sim.subId, allowSystemDefault = false, onPick = onPick) { picking = false }
}
