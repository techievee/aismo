package aismo.sms.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRecordCompatTest {
    private val oldJson = """{"t":"settings","theme":0,"includeSpamInAll":false,"otpNotifications":true,"otpAutoDelete":false,"otpAutoDeleteHours":24,""" +
        """"otpDeleteAfterCopy":false,"transactionPreviews":true,"sendSound":true,"deliveryNotifications":false,"reminderNotifications":true,""" +
        """"budgetAlerts":true,"protectionAlerts":true}"""

    @Test fun aSettingsRecordFromBeforeTheCategorySettingsStillReads() {
        val r = BackupFormat.decodeRecord(oldJson.toByteArray()) as SettingsRecord
        assertNull("absent means 'leave the current value', not 'reset it'", r.categoryOrder)
        assertNull(r.mutedCategories)
        assertNull(r.spamReviewReminder)
        assertEquals(24, r.otpAutoDeleteHours)
    }

    @Test fun theNewFieldsRoundTrip() {
        val record = SettingsRecord(0, false, true, false, 24, false, true, true, false, true, true, true, listOf("spam", "otp"), listOf("promo"), false)
        val back = BackupFormat.decodeRecord(BackupFormat.encodeRecord(record)) as SettingsRecord
        assertEquals(listOf("spam", "otp"), back.categoryOrder)
        assertEquals(listOf("promo"), back.mutedCategories)
        assertEquals(false, back.spamReviewReminder)
    }

    @Test fun anEmptyOrderIsKeptDistinctFromAbsent() {
        val back = BackupFormat.decodeRecord(BackupFormat.encodeRecord(SettingsRecord(0, false, true, false, 24, false, true, true, false, true, true, true, emptyList(), emptyList(), true))) as SettingsRecord
        assertTrue(back.categoryOrder!!.isEmpty())
        assertTrue(back.mutedCategories!!.isEmpty())
    }

    @Test fun anOlderRecordHasNoAppearanceOrHiddenCategoriesAndLeavesTheCurrentOnesAlone() {
        val r = BackupFormat.decodeRecord(oldJson.toByteArray()) as SettingsRecord
        assertNull(r.palette)
        assertNull(r.pureBlack)
        assertNull(r.hiddenCategories)
    }

    @Test fun appearanceAndHiddenCategoriesRoundTrip() {
        val record = SettingsRecord(0, false, true, false, 24, false, true, true, false, true, true, true, null, null, null, "forest", true, listOf("promo", "spam"))
        val back = BackupFormat.decodeRecord(BackupFormat.encodeRecord(record)) as SettingsRecord
        assertEquals("forest", back.palette)
        assertEquals(true, back.pureBlack)
        assertEquals(listOf("promo", "spam"), back.hiddenCategories)
    }

    @Test fun aCategoryRecordFromBeforeIconsHasNoIconAndTheRestoreChoosesTheDefault() {
        val r = BackupFormat.decodeRecord("""{"t":"cat","key":"custom:abc12345","name":"Family"}""".toByteArray()) as CategoryRecord
        assertEquals("Family", r.name)
        assertNull(r.icon)
    }

    @Test fun aCategoryIconRoundTrips() {
        val back = BackupFormat.decodeRecord(BackupFormat.encodeRecord(CategoryRecord("custom:abc12345", "Family", "family"))) as CategoryRecord
        assertEquals("family", back.icon)
    }

    @Test fun anOlderRecordHasNoSendDelayAndASendDelayRoundTrips() {
        assertNull((BackupFormat.decodeRecord(oldJson.toByteArray()) as SettingsRecord).sendDelaySeconds)
        val record = SettingsRecord(0, false, true, false, 24, false, true, true, false, true, true, true, null, null, null, null, null, null, 10)
        assertEquals(10, (BackupFormat.decodeRecord(BackupFormat.encodeRecord(record)) as SettingsRecord).sendDelaySeconds)
    }

    @Test fun anOlderRecordHasNoSecuredOrUnlockSettings() {
        val r = BackupFormat.decodeRecord(oldJson.toByteArray()) as SettingsRecord
        assertNull(r.securedCategories)
        assertNull(r.unlockWindowMinutes)
        assertNull(r.unlockMethod)
    }

    @Test fun securedAndUnlockSettingsRoundTrip() {
        val record = SettingsRecord(
            0, false, true, false, 24, false, true, true, false, true, true, true,
            null, null, null, null, null, null, null, true, null,
            listOf("finance", "custom:abc12345"), 10, "pin",
        )
        val back = BackupFormat.decodeRecord(BackupFormat.encodeRecord(record)) as SettingsRecord
        assertEquals(listOf("finance", "custom:abc12345"), back.securedCategories)
        assertEquals(10, back.unlockWindowMinutes)
        assertEquals("pin", back.unlockMethod)
    }

    @Test fun anOlderRecordHasNoGroupingChoice() {
        assertNull((BackupFormat.decodeRecord(oldJson.toByteArray()) as SettingsRecord).groupSmsAndMms)
    }

    @Test fun theGroupingChoiceRoundTripsAndAnExplicitFalseIsNotAbsent() {
        fun record(v: Boolean) = SettingsRecord(0, false, true, false, 24, false, true, true, false, true, true, true, groupSmsAndMms = v)
        assertEquals(true, (BackupFormat.decodeRecord(BackupFormat.encodeRecord(record(true))) as SettingsRecord).groupSmsAndMms)
        assertEquals("an explicit false must not decode as absent", false, (BackupFormat.decodeRecord(BackupFormat.encodeRecord(record(false))) as SettingsRecord).groupSmsAndMms)
    }
}
