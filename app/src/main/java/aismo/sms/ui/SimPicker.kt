package aismo.sms.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
