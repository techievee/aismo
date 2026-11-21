package aismo.sms

import android.app.role.RoleManager
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.MmsConditions
import aismo.sms.data.OutgoingMessages
import aismo.sms.data.SendOutcome
import aismo.sms.data.SendRoute
import aismo.sms.data.db.MessageIds
import aismo.sms.data.mms.OutgoingAttachment
import aismo.sms.domain.mms.MmsCharset
import aismo.sms.domain.mms.MmsPart
import java.io.File

/** Long texts becoming MMS, against the real SMS and MMS providers. */
@RunWith(AndroidJUnit4::class)
class LongTextConversionTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()
    private val c get() = app.container
    private val me = "+61411999000"

    private class Conditions(
        var available: Boolean = true, var segments: Int? = null, var chars: Int? = null, var maxText: Int? = null,
    ) : MmsConditions {
        override fun segmentThreshold(subId: Int) = segments
        override fun charThreshold(subId: Int) = chars
        override fun maxTextBytes(subId: Int) = maxText
        override fun mmsAvailable(subId: Int) = available
    }

    private val conditions = Conditions()
    private val outgoing get() = OutgoingMessages(c.sender, c.mmsEngine, c.mmsProvider, c.repository, c.settings, conditions)

    @Before fun setUp() {
        assumeTrue("needs the default SMS role", app.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS))
        runBlocking {
            c.db.conversations().allKeys().forEach { c.repository.deleteConversation(it) }
            c.provider.delete(c.provider.allIds().orEmpty()); c.mmsProvider.delete(c.mmsProvider.allIds().orEmpty())
            c.settings.update { it.copy(ownNumber = me, autoDownloadMms = false, longTextToMms = true, longTextSegments = 0) }
            c.repository.syncRecent()
        }
    }

    @After fun restore() {
        runBlocking { c.settings.update { it.copy(autoDownloadMms = true, longTextToMms = true, longTextSegments = 0) } }
        awaitRadioResults(c.provider)
    }

    private val longText = ("The quick brown fox jumps over the lazy dog. ").repeat(16)   // 720 chars = 5 SMS segments
    private val to = "+61411000555"

    private fun smsRows() = c.provider.readAfter(0, 100)
    private fun mmsRows() = c.mmsProvider.readAfter(0, 100)

    @Test fun aLongTextGoesOutAsOneMmsWithTheFullTextAndNoSms() = runBlocking {
        val r = outgoing.send(listOf(to), longText, emptyList(), -1)
        assertTrue("got $r", r is SendOutcome.Sent && r.route == SendRoute.MMS_LONG_TEXT)
        assertEquals("no SMS segments were written", 0, smsRows().size)
        val row = mmsRows().single()
        assertEquals(listOf(to), row.to)
        assertTrue("marked as a converted long text", row.transactionId!!.startsWith("tsol"))
        val parts = c.mmsProvider.parts(row.rawId)
        assertEquals(listOf("application/smil", "text/plain"), parts.map { it.mime })
        assertEquals("the text is delivered whole, not truncated", longText.trim(), parts[1].text)
    }

    @Test fun aShortTextStaysAnSms() = runBlocking {
        val r = outgoing.send(listOf(to), "see you at 7", emptyList(), -1)
        assertTrue("got $r", r is SendOutcome.Sent && r.route == SendRoute.SMS)
        assertEquals(0, mmsRows().size)
        assertEquals("see you at 7", smsRows().single().body)
    }

    @Test fun aTextJustUnderTheThresholdStaysAnSmsAndOneSegmentOverConverts() = runBlocking {
        val four = "a".repeat(153 * 4)            // 4 segments: not over the default of 4
        val five = "a".repeat(153 * 4 + 1)        // 5 segments
        assertEquals(SendRoute.SMS, (outgoing.send(listOf(to), four, emptyList(), -1) as SendOutcome.Sent).route)
        assertEquals(SendRoute.MMS_LONG_TEXT, (outgoing.send(listOf("+61411000556"), five, emptyList(), -1) as SendOutcome.Sent).route)
    }

    @Test fun theFeatureCanBeTurnedOff() = runBlocking {
        c.settings.update { it.copy(longTextToMms = false) }
        val r = outgoing.send(listOf(to), longText, emptyList(), -1) as SendOutcome.Sent
        assertEquals(SendRoute.SMS, r.route)
        assertEquals(0, mmsRows().size)
        assertEquals("one multipart SMS holding the whole text", longText.trim(), smsRows().single().body.trim())
    }

    @Test fun nothingIsConvertedWhenMmsCannotBeSent() = runBlocking {
        conditions.available = false // mobile data off, or MMS disabled by the carrier
        val r = outgoing.send(listOf(to), longText, emptyList(), -1) as SendOutcome.Sent
        assertEquals(SendRoute.SMS, r.route)
        assertEquals(0, mmsRows().size)
    }

    @Test fun theUsersThresholdBeatsTheCarriersAndTheCarriersBeatsTheDefault() = runBlocking {
        conditions.segments = 2
        assertEquals("carrier says convert above 2 parts; the text has 5", SendRoute.MMS_LONG_TEXT, outgoing.routeFor(1, false, longText, outgoing.rulesFor(-1)))
        c.settings.update { it.copy(longTextSegments = 8) }
        assertEquals("the user asked for 8", SendRoute.SMS, outgoing.routeFor(1, false, longText, outgoing.rulesFor(-1)))
    }

    @Test fun aCarrierWithATextSizeLimitKeepsOversizeTextsAsSms() = runBlocking {
        conditions.maxText = 300
        assertEquals(SendRoute.SMS, outgoing.routeFor(1, false, longText, outgoing.rulesFor(-1)))
    }

    @Test fun groupsAndAttachmentsAreAlwaysMms() = runBlocking {
        val rules = outgoing.rulesFor(-1)
        assertEquals(SendRoute.MMS, outgoing.routeFor(2, false, "hi", rules))
        assertEquals(SendRoute.MMS, outgoing.routeFor(1, true, "hi", rules))
        assertEquals(SendRoute.SMS, outgoing.routeFor(1, false, "hi", rules))
    }

    @Test fun aFailedConvertedTextCanBeResentAsAnOrdinarySms() = runBlocking {
        // a converted long text that the network refused
        val textPart = MmsPart("text/plain", "text_0.txt", "text_0", MmsCharset.UTF_8, longText.trim().toByteArray())
        val raw = c.mmsProvider.insertOutgoing(listOf(to), null, listOf(textPart), -1, "tsolXYZ")
        c.mmsProvider.updateBox(raw, Telephony.Mms.MESSAGE_BOX_FAILED)
        val id = MessageIds.mms(raw); c.repository.refresh(listOf(id))

        assertTrue(outgoing.resendAsText(id))
        assertNull("the failed MMS is gone", c.db.messages().get(id))
        assertEquals(0, mmsRows().size)
        assertEquals("the same words went out as SMS", longText.trim(), smsRows().single().body.trim())
        assertEquals("and the conversation still holds exactly one message", 1, c.db.conversations().get("p:411000555")?.messageCount)
    }

    @Test fun sendAsTextRefusesMessagesThatWouldLoseSomething() = runBlocking {
        val text = MmsPart("text/plain", "t.txt", "t", MmsCharset.UTF_8, "hello".toByteArray())
        // with an attachment
        val withPhoto = MessageIds.mms(c.mmsProvider.insertOutgoing(listOf(to), null, listOf(text, MmsPart("image/jpeg", "p.jpg", "p", null, ByteArray(100))), -1, "a"))
        // to a group
        val group = MessageIds.mms(c.mmsProvider.insertOutgoing(listOf(to, "+61411000556"), null, listOf(text), -1, "b"))
        c.repository.refresh(listOf(withPhoto, group))
        assertFalse(outgoing.resendAsText(withPhoto))
        assertFalse(outgoing.resendAsText(group))
        assertFalse("an SMS id is not an MMS", outgoing.resendAsText(12345))
        assertNotNull("nothing was deleted", c.mmsProvider.readById(MessageIds.raw(withPhoto)))
        assertEquals(0, smsRows().size)
    }

    @Test fun unicodeTextConvertsSoonerBecauseItNeedsMoreSegments() = runBlocking {
        val unicode = "✓ done. ".repeat(40)  // 320 chars, UCS-2 => 5 segments
        assertEquals(SendRoute.MMS_LONG_TEXT, outgoing.routeFor(1, false, unicode, outgoing.rulesFor(-1)))
        assertEquals(SendRoute.SMS, outgoing.routeFor(1, false, "a".repeat(320), outgoing.rulesFor(-1)))
    }

    @Test fun attachmentsStillGoWithAConvertedTextBecauseThatIsJustAnMms() = runBlocking {
        val f = File(app.cacheDir, "n.bin").also { it.writeBytes(ByteArray(2000)) }
        val r = outgoing.send(listOf(to), longText, listOf(OutgoingAttachment(f, "application/octet-stream", "n.bin")), -1) as SendOutcome.Sent
        assertEquals(SendRoute.MMS, r.route)
        val row = mmsRows().single()
        assertFalse("not marked as a converted long text: it has an attachment", row.transactionId!!.startsWith("tsol"))
        assertEquals(3, c.mmsProvider.parts(row.rawId).size)
    }
}
