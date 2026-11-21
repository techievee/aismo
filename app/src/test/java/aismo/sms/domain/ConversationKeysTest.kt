package aismo.sms.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationKeysTest {
    @Test fun numberVariantsShareOneKey() {
        val forms = listOf("+61 412 345 678", "61412345678", "0061 412 345 678", "0412345678", "412345678")
        forms.forEach { assertEquals("every spelling of the number lands in the same conversation", "p:412345678", ConversationKeys.of(it)) }
    }

    @Test fun aShortCodeKeepsItsWholeNumber() {
        // Too few digits to be a number: the whole code is the key.
        assertEquals("s:1078", ConversationKeys.of("1078"))
        assertEquals("s:ALERT", ConversationKeys.of("ALERT"))
        // A digit-only code of five or more characters is keyed like a (short) number.
        assertEquals("p:222222", ConversationKeys.of("222222"))
    }

    @Test fun aBusinessSenderIsKeyedByItsNameToken() {
        assertEquals("s:HDFCBK", ConversationKeys.of("VM-HDFCBK"))
        assertEquals("s:HDFCBK", ConversationKeys.of("vm-hdfcbk"))
    }

    @Test fun anEmptyAddressIsUnknown() {
        assertEquals("unknown", ConversationKeys.of(""))
        assertEquals("unknown", ConversationKeys.of("   "))
    }

    @Test fun aGroupIsKeyedByItsPeopleRegardlessOfOrder() {
        assertEquals(ConversationKeys.of("+61412345678, 0400111222"), ConversationKeys.of("0400111222,+61412345678"))
        assertTrue(ConversationKeys.of("+61412345678, 0400111222").startsWith("g:"))
        assertEquals("p:412345678", ConversationKeys.of("0412345678, ")) // one real participant: the plain key of that person
    }

    @Test fun mmsFromTheSameSenderSharesOneKeyButNotTheSmsOne() {
        listOf("+61 412 345 678", "61412345678", "0412345678").forEach {
            assertEquals("m:412345678", ConversationKeys.of(it, mms = true))
            assertEquals("p:412345678", ConversationKeys.of(it, mms = false))
        }
        assertFalse(ConversationKeys.isMmsKey(ConversationKeys.of("+61412345678")))
        assertTrue(ConversationKeys.isMmsKey(ConversationKeys.of("+61412345678", mms = true)))
    }

    @Test fun mmsFromABusinessSenderSharesTheToken() {
        assertEquals("m:HDFCBK", ConversationKeys.of("VM-HDFCBK", mms = true))
        assertEquals("m:222222", ConversationKeys.of("222222", mms = true))
    }

    @Test fun aGroupMmsIsKeyedSeparatelyFromTheGroupSms() {
        val sms = ConversationKeys.of("+61412345678, 0400111222")
        val mms = ConversationKeys.of("+61412345678, 0400111222", mms = true)
        assertEquals("g:p:400111222|p:412345678", sms)
        assertEquals("gm:p:400111222|p:412345678", mms)
        assertEquals(mms, ConversationKeys.of("0400111222,+61412345678", mms = true)) // order-independent, like the SMS key
    }

    @Test fun theHelpersSeeThroughBothForms() {
        val address = "VM-HDFCBK"
        assertTrue(ConversationKeys.isPhoneNumber("+61412345678"))
        assertTrue(ConversationKeys.isGroup("+61412345678, 0400111222"))
        assertFalse(ConversationKeys.canReply(address)) // a brand name is not a number a reply can go to
        assertTrue(ConversationKeys.canReply("+61412345678, 0400111222"))
        assertEquals("HDFCBK", ConversationKeys.senderToken("VM-HDFCBK"))
    }
}
