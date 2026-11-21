package aismo.sms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The accent each group of settings wears: its heading, and the round background behind the icon of each row. */
object SettingsAccent {
    val General = Color(0xFF1E88E5)
    val Sending = Color(0xFF00ACC1)
    val Notifications = Color(0xFFFB8C00)
    val Media = Color(0xFF00897B)
    val Search = Color(0xFF8E24AA)
    val Writing = Color(0xFFD81B60)
    val Translation = Color(0xFF0288D1)
    val Codes = Color(0xFF43A047)
    val Organizer = Color(0xFF5C6BC0)
    val Security = Color(0xFFE53935)
    val Data = Color(0xFF6D4C41)
    val About = Color(0xFF607D8B)
}

/** The accent on a dark theme is lightened so it still reads against the darker background. */
@Composable
private fun readable(accent: Color): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) lerp(accent, Color.White, 0.35f) else accent

/** An icon in its accent colour on a soft round background. */
@Composable
fun IconChip(icon: ImageVector, accent: Color, enabled: Boolean = true) {
    val color = if (enabled) readable(accent) else MaterialTheme.colorScheme.outline
    Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
    }
}

/** A titled group of settings: a heading with the group's icon, then the rows together on one rounded card. */
@Composable
fun SettingsGroup(title: String, icon: ImageVector, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, tint = readable(accent), modifier = Modifier.size(18.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = readable(accent))
        }
        Card(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
    }
}

private val RowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
fun SwitchRow(icon: ImageVector, accent: Color, title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
        colors = RowColors,
        leadingContent = { IconChip(icon, accent, enabled) },
        headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
    )
}

/** A row that opens something. [chevron] marks one that goes to another screen. */
@Composable
fun NavRow(icon: ImageVector, accent: Color, title: String, subtitle: String, enabled: Boolean = true, chevron: Boolean = false, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = RowColors,
        leadingContent = { IconChip(icon, accent, enabled) },
        headlineContent = { Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline) },
        supportingContent = { Text(subtitle) },
        trailingContent = if (chevron) {
            { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
    )
}
