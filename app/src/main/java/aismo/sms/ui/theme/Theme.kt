package aismo.sms.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import aismo.sms.data.settings.AppPalette
import aismo.sms.data.settings.ThemeMode
import aismo.sms.domain.SmsCategory

@Composable
fun AismoTheme(mode: ThemeMode, palette: AppPalette, pureBlack: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        palette == AppPalette.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val wallpaper = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            if (dark && pureBlack) Palettes.blackened(wallpaper) else wallpaper
        }
        else -> Palettes.scheme(palette, dark, pureBlack) // Material You on a phone older than Android 12 falls back to Ocean
    }
    // The status and navigation bar icons follow this theme, which can differ from the system's light or dark setting.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** A stable accent per category for avatars and tags. */
fun categoryColor(key: String): Color = when (SmsCategory.fromKey(key)) {
    SmsCategory.PERSONAL -> Color(0xFF5C6BC0)
    SmsCategory.OTP -> Color(0xFF00897B)
    SmsCategory.FINANCE -> Color(0xFF2E7D32)
    SmsCategory.BILL -> Color(0xFFEF6C00)
    SmsCategory.ORDER -> Color(0xFF8E24AA)
    SmsCategory.TRAVEL -> Color(0xFF0277BD)
    SmsCategory.GOVERNMENT -> Color(0xFF6D4C41)
    SmsCategory.HEALTH -> Color(0xFFD81B60)
    SmsCategory.EDUCATION -> Color(0xFF3949AB)
    SmsCategory.JOB -> Color(0xFF546E7A)
    SmsCategory.SERVICE -> Color(0xFF039BE5)
    SmsCategory.PROMO -> Color(0xFFF9A825)
    SmsCategory.SPAM -> Color(0xFFC62828)
    SmsCategory.OTHER -> Color(0xFF757575)
    null -> Color(0xFF26A69A)
}
