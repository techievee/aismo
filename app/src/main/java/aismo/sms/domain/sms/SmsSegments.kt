package aismo.sms.domain.sms

enum class SmsEncoding { GSM7, UCS2 }

/**
 * How a text is split into SMS segments.
 * [units] are septets (GSM-7) or UTF-16 code units (UCS-2); [remaining] is what is left in the last segment.
 */
data class SmsLength(val encoding: SmsEncoding, val segments: Int, val units: Int, val remaining: Int)

/** Counts SMS segments the way a handset does: GSM 03.38 septets when possible, otherwise UCS-2. */
object SmsSegments {
    // GSM 03.38 default alphabet; position 0x1B is the escape into the extension table.
    private const val BASIC =
        "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"

    /** Characters reached through ESC; each costs two septets. */
    private const val EXTENSION = "\u000c^{}\\[~]|€"

    private val basic: Set<Char> = BASIC.toSet() - '\u001b'
    private val extension: Set<Char> = EXTENSION.toSet()

    fun measure(text: String): SmsLength {
        if (text.isEmpty()) return SmsLength(SmsEncoding.GSM7, 0, 0, SINGLE_GSM)
        return if (text.all { it in basic || it in extension }) gsm(text) else ucs2(text)
    }

    private fun gsm(text: String): SmsLength {
        val costs = text.map { if (it in extension) 2 else 1 }
        val total = costs.sum()
        if (total <= SINGLE_GSM) return SmsLength(SmsEncoding.GSM7, 1, total, SINGLE_GSM - total)
        // Multipart: 153 septets per segment, and an escape pair is never split across two segments.
        return pack(costs, MULTI_GSM, SmsEncoding.GSM7, total)
    }

    private fun ucs2(text: String): SmsLength {
        if (text.length <= SINGLE_UCS2) return SmsLength(SmsEncoding.UCS2, 1, text.length, SINGLE_UCS2 - text.length)
        // A surrogate pair (an emoji, say) is two units that must stay in one segment.
        val costs = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            if (Character.isHighSurrogate(text[i]) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) { costs += 2; i += 2 } else { costs += 1; i++ }
        }
        return pack(costs, MULTI_UCS2, SmsEncoding.UCS2, text.length)
    }

    private fun pack(costs: List<Int>, capacity: Int, encoding: SmsEncoding, units: Int): SmsLength {
        var segments = 1
        var used = 0
        for (c in costs) {
            if (used + c > capacity) { segments++; used = 0 }
            used += c
        }
        return SmsLength(encoding, segments, units, capacity - used)
    }

    const val SINGLE_GSM = 160
    const val MULTI_GSM = 153
    const val SINGLE_UCS2 = 70
    const val MULTI_UCS2 = 67
}
