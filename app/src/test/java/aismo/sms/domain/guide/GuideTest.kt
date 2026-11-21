package aismo.sms.domain.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuideTest {
    private val sections = Guide.readme + Guide.help

    @Test fun everySectionHasAnIdATitleAndSomethingToRead() {
        for (s in sections) {
            assertTrue("${s.id} needs a title", s.title.isNotBlank())
            assertTrue("${s.id} needs content", s.paragraphs.isNotEmpty() || s.steps.isNotEmpty() || s.bullets.isNotEmpty())
            assertTrue("${s.id}: no blank lines", (s.paragraphs + s.steps + s.bullets).none { it.isBlank() })
        }
    }

    @Test fun idsAreUniqueWithinEachPageBecauseTheyKeepTheOpenStateOfAnEntry() {
        assertEquals(Guide.readme.size, Guide.readme.map { it.id }.toSet().size)
        assertEquals(Guide.help.size, Guide.help.map { it.id }.toSet().size)
    }

    @Test fun theHelpStartsWithGettingStartedAndCoversTheFeaturesPeopleAskAbout() {
        assertEquals("getting-started", Guide.help.first().id)
        val ids = Guide.help.map { it.id }.toSet()
        for (needed in listOf("cant-send", "categories", "search", "writing", "undo", "mms", "backup", "notifications", "spam", "appearance", "troubleshooting")) assertTrue("help has $needed", needed in ids)
    }

    // ---------------------------------------------------------------- privacy: the page must say what the app really asks for

    private fun manifestPermissions(): Set<String> {
        val text = File("src/main/AndroidManifest.xml").readText()
        return Regex("<uses-permission\\s[^>]*?/>").findAll(text).map { it.value }
            .filterNot { "tools:node=\"remove\"" in it }
            .mapNotNull { Regex("android:name=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.toSet()
    }

    @Test fun everyPermissionTheManifestAsksForIsExplained() {
        val explained = Guide.permissions.map { it.permission }.toSet()
        val missing = manifestPermissions() - explained
        assertTrue("explain these on the Privacy page: $missing", missing.isEmpty())
    }

    @Test fun thePrivacyPageNeverListsTheInternetPermissionOrAnExplanationlessOne() {
        assertFalse(Guide.permissions.any { it.permission.endsWith(".INTERNET") })
        assertEquals(Guide.permissions.size, Guide.permissions.map { it.permission }.toSet().size)
        assertTrue(Guide.permissions.all { it.label.isNotBlank() && it.why.isNotBlank() })
    }

    @Test fun theManifestStillRemovesTheInternetPermissionThatALibraryTriesToAdd() {
        val text = File("src/main/AndroidManifest.xml").readText()
        assertTrue(Regex("<uses-permission[^>]*android.permission.INTERNET[^>]*tools:node=\"remove\"").containsMatchIn(text))
    }

    // ---------------------------------------------------------------- licences: every file named must be shipped

    @Test fun everyLicenceFileNamedExists() {
        for (lib in Guide.libraries) for (asset in listOfNotNull(lib.licenceAsset, lib.noticesAsset)) {
            assertTrue("${lib.name}: missing ${Guide.ASSET_DIR}/$asset", File("src/main/assets/${Guide.ASSET_DIR}/$asset").isFile)
        }
    }

    @Test fun everyShippedLicenceFileIsListedSoNothingIsShippedUnmentioned() {
        val listed = Guide.libraries.flatMap { listOfNotNull(it.licenceAsset, it.noticesAsset) }.toSet()
        val shipped = File("src/main/assets/${Guide.ASSET_DIR}").listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(shipped, listed)
    }

    @Test fun libraryEntriesAreCompleteAndLinkOverHttps() {
        assertEquals(Guide.libraries.size, Guide.libraries.map { it.name }.toSet().size)
        for (lib in Guide.libraries) {
            assertTrue(lib.name, lib.licence.isNotBlank() && lib.role.isNotBlank())
            assertTrue(lib.name, lib.url.startsWith("https://"))
        }
        assertTrue(Guide.SOURCE_URL.startsWith("https://"))
    }
}
