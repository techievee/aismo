package aismo.sms.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsSegmentsTest {
    private fun m(s: String) = SmsSegments.measure(s)

    @Test fun emptyTextIsZeroSegments() = assertEquals(0, m("").segments)

    @Test fun aFullSingleGsmSegmentIs160Characters() {
        val r = m("a".repeat(160)); assertEquals(1, r.segments); assertEquals(0, r.remaining); assertEquals(SmsEncoding.GSM7, r.encoding)
        assertEquals(2, m("a".repeat(161)).segments)
    }

    @Test fun multipartGsmHolds153PerSegment() {
        assertEquals(2, m("a".repeat(306)).segments)
        assertEquals(3, m("a".repeat(307)).segments)
        assertEquals(153 * 4 - 1, m("a".repeat(153 * 4 - 1)).let { it.units })
        assertEquals(1, m("a".repeat(153 * 4 - 1)).remaining)
    }

    @Test fun extensionCharactersCostTwoSeptets() {
        val r = m("€".repeat(80)) // 160 septets
        assertEquals(SmsEncoding.GSM7, r.encoding); assertEquals(160, r.units); assertEquals(1, r.segments)
        assertEquals(2, m("€".repeat(81)).segments)
        assertEquals("brackets and pipes are extension characters too", 2, m("[]{}|\\^~").units / 8)
    }

    @Test fun anEscapePairIsNeverSplitAcrossSegments() {
        // 152 plain septets leave one slot; the euro sign needs two, so it starts the next segment.
        val r = m("a".repeat(152) + "€" + "a".repeat(10))
        assertEquals(2, r.segments)
        assertEquals(153 - 2 - 10, r.remaining)
    }

    @Test fun nonGsmCharactersForceUcs2() {
        val r = m("Hello ✓"); assertEquals(SmsEncoding.UCS2, r.encoding); assertEquals(1, r.segments); assertEquals(70 - 7, r.remaining)
        assertEquals(2, m("é".repeat(60) + "✓".repeat(11)).segments) // 71 units
        assertEquals(1, m("é".repeat(69) + "✓").segments)
    }

    @Test fun accentedLettersInTheGsmAlphabetStayGsm() {
        assertEquals(SmsEncoding.GSM7, m("café à la crème ñ ü ö ä").encoding)
        assertEquals("ç is not in the GSM alphabet (Ç is)", SmsEncoding.UCS2, m("façade").encoding)
    }

    @Test fun anEmojiCountsAsTwoUnitsAndIsNotSplit() {
        val emoji = "😀"
        assertEquals(2, m(emoji).units)
        assertEquals(1, m(emoji.repeat(35)).segments)   // 70 units
        assertEquals(2, m(emoji.repeat(36)).segments)   // 72 units -> 67-unit segments, pairs stay together
        // 66 units, then 34 emoji (68 units): 134 units. Splitting an emoji would give ceil(134/67) = 2 segments,
        // but a pair cannot straddle segments, so the first segment ends at 66 and the emoji need two more.
        assertEquals(3, m("✓" + "a".repeat(65) + emoji.repeat(34)).segments)
    }

    @Test fun newlinesAndCarriageReturnsAreGsm() = assertEquals(SmsEncoding.GSM7, m("line one\r\nline two").encoding)
}
