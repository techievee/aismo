package aismo.sms.domain.mms

import aismo.sms.domain.sms.SmsSegments

/** Everything that decides whether a long text goes out as MMS instead of a chain of SMS segments. */
data class ConversionRules(
    val enabled: Boolean,
    /** Convert when the text needs more than this many SMS segments; 0 turns the segment trigger off. */
    val maxSegments: Int,
    /** The carrier's character-count trigger (convert when longer), or null. */
    val maxChars: Int?,
    /** MMS is enabled by the carrier and mobile data is on, so a converted message can actually be sent. */
    val mmsAvailable: Boolean,
    /** The largest text an MMS can carry, or null for no limit. */
    val maxTextBytes: Int?,
) {
    companion object {
        val OFF = ConversionRules(enabled = false, maxSegments = 0, maxChars = null, mmsAvailable = false, maxTextBytes = null)
    }
}

object LongTextPolicy {
    /** Used when the user has not chosen a threshold and the carrier has not set one: 5+ segments become an MMS. */
    const val DEFAULT_MAX_SEGMENTS = 4

    /**
     * Combines the user's choice with the carrier's configuration. A user-chosen segment count wins outright;
     * "carrier default" (0) uses the carrier's thresholds, or [DEFAULT_MAX_SEGMENTS] if it sets none.
     */
    fun rules(
        enabled: Boolean,
        userSegments: Int,
        carrierSegments: Int?,
        carrierChars: Int?,
        mmsAvailable: Boolean,
        maxTextBytes: Int?,
    ): ConversionRules = when {
        userSegments > 0 -> ConversionRules(enabled, userSegments, null, mmsAvailable, maxTextBytes)
        else -> ConversionRules(
            enabled = enabled,
            maxSegments = carrierSegments?.takeIf { it > 0 } ?: DEFAULT_MAX_SEGMENTS,
            maxChars = carrierChars?.takeIf { it > 0 },
            mmsAvailable = mmsAvailable,
            maxTextBytes = maxTextBytes?.takeIf { it > 0 },
        )
    }

    fun shouldConvert(text: String, rules: ConversionRules): Boolean {
        if (!rules.enabled || !rules.mmsAvailable || text.isEmpty()) return false
        if (rules.maxTextBytes != null && text.toByteArray(Charsets.UTF_8).size > rules.maxTextBytes) return false
        val tooManySegments = rules.maxSegments > 0 && SmsSegments.measure(text).segments > rules.maxSegments
        val tooManyChars = rules.maxChars != null && text.length > rules.maxChars
        return tooManySegments || tooManyChars
    }
}
