package aismo.sms.domain.mms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTextPolicyTest {
    private fun rules(
        enabled: Boolean = true, userSegments: Int = 0, carrierSegments: Int? = null, carrierChars: Int? = null,
        available: Boolean = true, maxBytes: Int? = null,
    ) = LongTextPolicy.rules(enabled, userSegments, carrierSegments, carrierChars, available, maxBytes)

    private val short = "a".repeat(160)
    private val fiveSegments = "a".repeat(153 * 4 + 1)   // 5 segments
    private val fourSegments = "a".repeat(153 * 4)       // 4 segments

    @Test fun shortTextsAreNeverConverted() = assertFalse(LongTextPolicy.shouldConvert(short, rules()))

    @Test fun theBuiltInDefaultConvertsFromTheFifthSegment() {
        assertFalse(LongTextPolicy.shouldConvert(fourSegments, rules()))
        assertTrue(LongTextPolicy.shouldConvert(fiveSegments, rules()))
        assertEquals(LongTextPolicy.DEFAULT_MAX_SEGMENTS, rules().maxSegments)
    }

    @Test fun theCarrierThresholdIsUsedWhenTheUserHasNoPreference() {
        val r = rules(carrierSegments = 2)
        assertTrue(LongTextPolicy.shouldConvert("a".repeat(307), r))   // 3 segments > 2
        assertFalse(LongTextPolicy.shouldConvert("a".repeat(306), r))  // 2 segments
    }

    @Test fun aCarrierThatReportsMinusOneMeansNoCarrierThreshold() {
        assertEquals(LongTextPolicy.DEFAULT_MAX_SEGMENTS, rules(carrierSegments = -1).maxSegments)
        assertNull(rules(carrierChars = -1).maxChars)
    }

    @Test fun theCarrierCharacterThresholdAlsoTriggers() {
        val r = rules(carrierChars = 500)
        assertTrue(LongTextPolicy.shouldConvert("a".repeat(501), r))
        assertFalse(LongTextPolicy.shouldConvert("a".repeat(500), r))
    }

    @Test fun aUserChoiceOverridesTheCarrier() {
        val r = rules(userSegments = 8, carrierSegments = 2, carrierChars = 100)
        assertFalse(LongTextPolicy.shouldConvert("a".repeat(153 * 7), r)) // 7 segments, well over the carrier's limits
        assertTrue(LongTextPolicy.shouldConvert("a".repeat(153 * 8 + 1), r))
    }

    @Test fun nothingIsConvertedWhenTheFeatureIsOffOrMmsIsUnavailable() {
        assertFalse(LongTextPolicy.shouldConvert(fiveSegments, rules(enabled = false)))
        assertFalse("mobile data off or MMS disabled by the carrier", LongTextPolicy.shouldConvert(fiveSegments, rules(available = false)))
        assertFalse(LongTextPolicy.shouldConvert(fiveSegments, ConversionRules.OFF))
    }

    @Test fun aTextTooBigForTheCarriersMmsTextLimitStaysAnSms() {
        assertFalse(LongTextPolicy.shouldConvert(fiveSegments, rules(maxBytes = 500)))
        assertTrue(LongTextPolicy.shouldConvert(fiveSegments, rules(maxBytes = 100_000)))
    }

    @Test fun unicodeTextConvertsSoonerBecauseItNeedsMoreSegments() {
        val unicode = "✓".repeat(67 * 4 + 1) // 5 UCS-2 segments but only ~269 characters
        assertTrue(LongTextPolicy.shouldConvert(unicode, rules()))
        assertFalse("the same number of characters in GSM is only 2 segments", LongTextPolicy.shouldConvert("a".repeat(269), rules()))
    }
}
