package aismo.sms.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import aismo.sms.container
import aismo.sms.data.settings.AppPalette
import aismo.sms.data.settings.AppSettings
import aismo.sms.data.settings.ThemeMode
import aismo.sms.ui.theme.Palettes

/** Choose light, dark or the system's setting, a true black background for dark, and one of the colour themes. Changes show at once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle(AppSettings())
    fun update(f: (AppSettings) -> AppSettings) { scope.launch { c.settings.update(f) } }
    val systemDark = isSystemInDarkTheme()
    val dark = when (s.theme) { ThemeMode.SYSTEM -> systemDark; ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    SimpleScaffold("Appearance", onBack) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            val accent = SettingsAccent.General
            SettingsGroup("Brightness", Icons.Filled.Contrast, accent) {
                val modes = listOf(
                    Triple(ThemeMode.SYSTEM, "System", Icons.Filled.SettingsBrightness),
                    Triple(ThemeMode.LIGHT, "Light", Icons.Filled.LightMode),
                    Triple(ThemeMode.DARK, "Dark", Icons.Filled.DarkMode),
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    modes.forEachIndexed { index, (mode, label, icon) ->
                        SegmentedButton(
                            selected = s.theme == mode, onClick = { update { it.copy(theme = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                            icon = { SegmentedButtonDefaults.Icon(active = s.theme == mode) { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) } },
                        ) { Text(label) }
                    }
                }
                SwitchRow(Icons.Filled.Contrast, accent, "Pure black", "In the dark theme, use a true black background. Saves battery on OLED screens.", s.pureBlack) { v -> update { it.copy(pureBlack = v) } }
            }

            SettingsGroup("Colour theme", Icons.Filled.Palette, SettingsAccent.Writing) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (pair in AppPalette.entries.chunked(2)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (palette in pair) {
                                val usable = palette != AppPalette.DYNAMIC || dynamicAvailable
                                PaletteCard(
                                    palette, selected = (s.palette == palette && usable) || (s.palette == AppPalette.DYNAMIC && !dynamicAvailable && palette == AppPalette.OCEAN),
                                    enabled = usable, dark = dark, pureBlack = s.pureBlack, modifier = Modifier.weight(1f),
                                ) { update { it.copy(palette = palette) } }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteCard(palette: AppPalette, selected: Boolean, enabled: Boolean, dark: Boolean, pureBlack: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val preview: ColorScheme = if (palette == AppPalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)).let { if (dark && pureBlack) Palettes.blackened(it) else it }
    } else Palettes.scheme(palette, dark, pureBlack)
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.alpha(if (enabled) 1f else 0.5f).clip(shape)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
    ) {
        // A small conversation in the theme's own colours.
        Column(Modifier.fillMaxWidth().height(104.dp).background(preview.surface).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(72.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(preview.surfaceContainerHigh))
            Box(Modifier.align(Alignment.End).width(88.dp).height(20.dp).clip(RoundedCornerShape(10.dp)).background(preview.primary))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                for (color in listOf(preview.primary, preview.secondaryContainer, preview.tertiary)) Box(Modifier.size(18.dp).clip(CircleShape).background(color))
            }
        }
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(palette.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                paletteNote(palette, enabled)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun paletteNote(palette: AppPalette, enabled: Boolean): String? = when {
    palette == AppPalette.DYNAMIC && !enabled -> "Needs Android 12"
    palette == AppPalette.DYNAMIC -> "From your wallpaper"
    else -> null
}
