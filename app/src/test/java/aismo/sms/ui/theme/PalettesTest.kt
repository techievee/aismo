package aismo.sms.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import aismo.sms.data.settings.AppPalette

class PalettesTest {
    /** WCAG contrast ratio: 4.5 is the minimum for body text, 3 for large text and icons. */
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private val fixed = AppPalette.entries.filter { it != AppPalette.DYNAMIC }

    /** Every look a fixed palette can have: light, dark and dark on pure black. */
    private val looks: List<Triple<String, ColorScheme, Boolean>> =
        fixed.flatMap { p ->
            listOf(
                Triple("${p.key} light", Palettes.scheme(p, dark = false, pureBlack = false), false),
                Triple("${p.key} dark", Palettes.scheme(p, dark = true, pureBlack = false), true),
                Triple("${p.key} black", Palettes.scheme(p, dark = true, pureBlack = true), true),
            )
        }

    private val failures = mutableListOf<String>()

    /** Records a pair that is too close, so one run lists every problem instead of stopping at the first. */
    private fun check(name: String, foreground: Color, background: Color, minimum: Double) {
        val ratio = contrast(foreground, background)
        if (ratio < minimum) failures += "$name: contrast %.2f is below %.1f".format(ratio, minimum)
    }

    private fun assertNoFailures() = assertTrue(failures.joinToString("\n"), failures.isEmpty())

    @Test fun textOnEveryMainSurfaceIsReadable() {
        for ((name, s, _) in looks) {
            check("$name text on surface", s.onSurface, s.surface, 7.0)
            check("$name text on background", s.onBackground, s.background, 7.0)
            check("$name secondary text on surface", s.onSurfaceVariant, s.surface, 4.5)
            check("$name secondary text on cards", s.onSurfaceVariant, s.surfaceContainer, 4.5)
            check("$name secondary text on raised cards", s.onSurfaceVariant, s.surfaceContainerHighest, 4.5)
            check("$name text on cards", s.onSurface, s.surfaceContainerLow, 7.0)
        }
        assertNoFailures()
    }

    @Test fun textOnEveryAccentIsReadable() {
        for ((name, s, _) in looks) {
            check("$name on primary", s.onPrimary, s.primary, 4.5)
            check("$name on primary container", s.onPrimaryContainer, s.primaryContainer, 4.5)
            check("$name on secondary container", s.onSecondaryContainer, s.secondaryContainer, 4.5)
            check("$name on tertiary", s.onTertiary, s.tertiary, 4.5)
            check("$name on tertiary container", s.onTertiaryContainer, s.tertiaryContainer, 4.5)
            check("$name inverse text", s.inverseOnSurface, s.inverseSurface, 4.5)
        }
        assertNoFailures()
    }

    @Test fun theAccentStandsOutAgainstTheBackgroundForHeadingsAndIcons() {
        for ((name, s, _) in looks) {
            check("$name primary on surface", s.primary, s.surface, 4.5)
            check("$name outline on surface", s.outline, s.surface, 3.0)
        }
        assertNoFailures()
    }

    @Test fun pureBlackReallyIsBlackAndCardsStayVisibleAgainstIt() {
        for (p in fixed) {
            val s = Palettes.scheme(p, dark = true, pureBlack = true)
            assertEquals(Color.Black, s.background)
            assertEquals(Color.Black, s.surface)
            assertTrue("${p.key}: a card must differ from the background", s.surfaceContainer.luminance() > s.surface.luminance())
            assertTrue("${p.key}: raised cards are lighter than plain cards", s.surfaceContainerHighest.luminance() > s.surfaceContainer.luminance())
            assertTrue("${p.key}: a settings card is visible on black", contrast(s.surfaceContainerLow, Color.Black) >= 1.1)
            assertTrue("${p.key}: a sheet or bar is visible on black", contrast(s.surfaceContainer, Color.Black) >= 1.15)
        }
    }

    @Test fun pureBlackChangesNothingInTheLightTheme() {
        for (p in fixed) assertEquals(Palettes.scheme(p, dark = false, pureBlack = false).toString(), Palettes.scheme(p, dark = false, pureBlack = true).toString())
    }

    @Test fun everyPaletteLooksDifferentFromTheOthers() {
        val primaries = fixed.map { Palettes.scheme(it, dark = false, pureBlack = false).primary }
        assertEquals(primaries.size, primaries.toSet().size)
    }

    @Test fun anyDarkSchemeIsActuallyDarkAndAnyLightOneLight() {
        for ((name, s, dark) in looks) assertEquals(name, dark, s.surface.luminance() < 0.2f)
    }

    @Test fun palettesAreFoundAgainByTheirStoredKeyAndNeverGuessed() {
        for (p in AppPalette.entries) assertEquals(p, AppPalette.fromKey(p.key))
        assertNull(AppPalette.fromKey(null)); assertNull(AppPalette.fromKey("OCEAN")); assertNull(AppPalette.fromKey("neon"))
        assertEquals(AppPalette.entries.size, AppPalette.entries.map { it.key }.toSet().size)
    }

    @Test fun theDynamicThemeIsTheDefaultSoExistingUsersKeepTheirLook() {
        assertEquals(AppPalette.DYNAMIC, aismo.sms.data.settings.AppSettings().palette)
    }
}
