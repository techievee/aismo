package aismo.sms.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import aismo.sms.data.settings.AppPalette
import kotlin.math.abs

/**
 * The fixed colour themes. Each is one hue and how strong it is; every colour of the scheme is that hue at a chosen
 * lightness, so text and its background always sit far apart (the unit tests check the contrast of every pair).
 * Surfaces are tinted a little with the hue so cards and backgrounds belong to the theme.
 */
object Palettes {
    /** [primaryLight] is how light the accent is on a light background; greens look lighter than blues at the same value, so they sit lower. */
    private class Seed(val hue: Float, val saturation: Float, val tertiaryHue: Float, val primaryLight: Float = 0.36f)

    private val seeds = mapOf(
        AppPalette.OCEAN to Seed(215f, 0.72f, 275f),
        AppPalette.FOREST to Seed(142f, 0.56f, 40f, primaryLight = 0.29f),
        AppPalette.SUNSET to Seed(22f, 0.88f, 345f),
        AppPalette.ROSE to Seed(338f, 0.68f, 20f),
        AppPalette.VIOLET to Seed(265f, 0.55f, 320f),
        AppPalette.GRAPHITE to Seed(215f, 0.07f, 200f),
    )

    /** The scheme for a fixed palette. [AppPalette.DYNAMIC] has no colours of its own and uses Ocean here. */
    fun scheme(palette: AppPalette, dark: Boolean, pureBlack: Boolean): ColorScheme {
        val seed = seeds[palette] ?: seeds.getValue(AppPalette.OCEAN)
        val scheme = if (dark) dark(seed) else light(seed)
        return if (dark && pureBlack) blackened(scheme) else scheme
    }

    /** The same scheme on a true black background. Cards and sheets keep their dark-theme surfaces so they stay visible against it. */
    fun blackened(scheme: ColorScheme): ColorScheme = scheme.copy(
        background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
    )

    private fun light(s: Seed): ColorScheme {
        val h = s.hue; val sat = s.saturation; val t = s.tertiaryHue
        val primary = hsl(h, sat, s.primaryLight)
        return lightColorScheme(
            primary = primary, onPrimary = Color.White,
            primaryContainer = hsl(h, sat * 0.85f, 0.90f), onPrimaryContainer = hsl(h, sat, 0.13f),
            inversePrimary = hsl(h, sat, 0.78f),
            secondary = hsl(h, sat * 0.35f, 0.36f), onSecondary = Color.White,
            secondaryContainer = hsl(h, sat * 0.55f, 0.90f), onSecondaryContainer = hsl(h, sat * 0.5f, 0.12f),
            tertiary = hsl(t, sat * 0.6f, 0.34f), onTertiary = Color.White,
            tertiaryContainer = hsl(t, sat * 0.6f, 0.90f), onTertiaryContainer = hsl(t, sat * 0.6f, 0.12f),
            background = hsl(h, sat * 0.35f, 0.98f), onBackground = hsl(h, 0.10f, 0.10f),
            surface = hsl(h, sat * 0.35f, 0.98f), onSurface = hsl(h, 0.10f, 0.10f),
            surfaceVariant = hsl(h, sat * 0.30f, 0.90f), onSurfaceVariant = hsl(h, sat * 0.15f, 0.28f),
            surfaceTint = primary,
            inverseSurface = hsl(h, 0.10f, 0.20f), inverseOnSurface = hsl(h, 0.20f, 0.95f),
            outline = hsl(h, sat * 0.10f, 0.42f), outlineVariant = hsl(h, sat * 0.20f, 0.80f),
            surfaceDim = hsl(h, sat * 0.25f, 0.87f), surfaceBright = hsl(h, sat * 0.35f, 0.98f),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = hsl(h, sat * 0.35f, 0.96f), surfaceContainer = hsl(h, sat * 0.32f, 0.94f),
            surfaceContainerHigh = hsl(h, sat * 0.30f, 0.92f), surfaceContainerHighest = hsl(h, sat * 0.28f, 0.90f),
        )
    }

    private fun dark(s: Seed): ColorScheme {
        val h = s.hue; val sat = s.saturation; val t = s.tertiaryHue
        val primary = hsl(h, sat, 0.78f)
        return darkColorScheme(
            primary = primary, onPrimary = hsl(h, sat, 0.14f),
            primaryContainer = hsl(h, sat * 0.80f, 0.26f), onPrimaryContainer = hsl(h, sat, 0.90f),
            inversePrimary = hsl(h, sat, 0.36f),
            secondary = hsl(h, sat * 0.40f, 0.76f), onSecondary = hsl(h, sat * 0.40f, 0.14f),
            secondaryContainer = hsl(h, sat * 0.35f, 0.28f), onSecondaryContainer = hsl(h, sat * 0.50f, 0.90f),
            tertiary = hsl(t, sat * 0.6f, 0.78f), onTertiary = hsl(t, sat * 0.6f, 0.14f),
            tertiaryContainer = hsl(t, sat * 0.5f, 0.26f), onTertiaryContainer = hsl(t, sat * 0.6f, 0.90f),
            background = hsl(h, sat * 0.22f, 0.08f), onBackground = hsl(h, 0.10f, 0.90f),
            surface = hsl(h, sat * 0.22f, 0.08f), onSurface = hsl(h, 0.10f, 0.90f),
            surfaceVariant = hsl(h, sat * 0.15f, 0.26f), onSurfaceVariant = hsl(h, sat * 0.20f, 0.80f),
            surfaceTint = primary,
            inverseSurface = hsl(h, 0.10f, 0.90f), inverseOnSurface = hsl(h, 0.10f, 0.15f),
            outline = hsl(h, sat * 0.10f, 0.60f), outlineVariant = hsl(h, sat * 0.15f, 0.30f),
            surfaceDim = hsl(h, sat * 0.22f, 0.06f), surfaceBright = hsl(h, sat * 0.18f, 0.24f),
            surfaceContainerLowest = hsl(h, sat * 0.22f, 0.05f),
            surfaceContainerLow = hsl(h, sat * 0.22f, 0.10f), surfaceContainer = hsl(h, sat * 0.20f, 0.12f),
            surfaceContainerHigh = hsl(h, sat * 0.18f, 0.16f), surfaceContainerHighest = hsl(h, sat * 0.18f, 0.20f),
        )
    }

    /** Hue in degrees, saturation and lightness from 0 to 1. */
    internal fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
        val h = ((hue % 360f) + 360f) % 360f
        val c = (1f - abs(2f * lightness - 1f)) * saturation
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = lightness - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m)
    }
}
