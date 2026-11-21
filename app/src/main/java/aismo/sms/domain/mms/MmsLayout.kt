package aismo.sms.domain.mms

/** Builds the SMIL layout part that tells receiving phones how to show the parts of an MMS. */
object SmilBuilder {
    /**
     * One slide per media part, with the text shown on the first slide. Files that SMIL cannot display
     * (contact cards, documents) are referenced with a generic `<ref>` so they still arrive as attachments.
     */
    fun build(text: MmsPart?, media: List<MmsPart>): String {
        val sb = StringBuilder()
        sb.append("<smil><head><layout>")
        sb.append("<root-layout width=\"320px\" height=\"480px\"/>")
        sb.append("<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>")
        sb.append("<region id=\"Text\" fit=\"meet\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>")
        sb.append("</layout></head><body>")
        val slides = if (media.isEmpty()) listOf<MmsPart?>(null) else media
        slides.forEachIndexed { i, part ->
            sb.append("<par dur=\"5000ms\">")
            if (part != null) sb.append(element(part))
            if (i == 0 && text != null) sb.append("<text src=\"${esc(text.name.orEmpty())}\" region=\"Text\"/>")
            sb.append("</par>")
        }
        sb.append("</body></smil>")
        return sb.toString()
    }

    private fun element(p: MmsPart): String {
        val src = esc(p.name.orEmpty())
        return when {
            p.contentType.startsWith("image/") -> "<img src=\"$src\" region=\"Image\"/>"
            p.contentType.startsWith("video/") -> "<video src=\"$src\" region=\"Image\"/>"
            p.contentType.startsWith("audio/") -> "<audio src=\"$src\"/>"
            else -> "<ref src=\"$src\"/>"
        }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Splits an MMS size limit between the text and the attachments. */
object MmsBudget {
    /** Bytes reserved for headers and the SMIL part. */
    const val OVERHEAD = 4 * 1024
    const val DEFAULT_MAX = 300 * 1024
    private const val HARD_CAP = 5 * 1024 * 1024

    fun clampMax(carrierMax: Int?): Int = (carrierMax ?: DEFAULT_MAX).coerceIn(50 * 1024, HARD_CAP)

    /**
     * How many bytes each image may use after the text and the non-image attachments are accounted for,
     * or null when even tiny images would not fit.
     */
    fun perImageBytes(maxBytes: Int, textBytes: Int, otherBytes: Long, imageCount: Int): Int? {
        if (imageCount == 0) return if (textBytes + otherBytes + OVERHEAD <= maxBytes) Int.MAX_VALUE else null
        val room = maxBytes - OVERHEAD - textBytes - otherBytes
        val each = room / imageCount
        return if (each >= MIN_IMAGE_BYTES) each.toInt() else null
    }

    private const val MIN_IMAGE_BYTES = 8 * 1024
}
