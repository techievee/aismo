package aismo.sms.domain.rephrase

/**
 * The ways the writing assistant can change a draft, in the order the sheet lists them. [key] is the name used in
 * prompts.json. A [corrects] mode only repairs what is wrong: an answer identical to the draft means "nothing to fix", and
 * it must not turn a statement into a question.
 */
enum class RephraseMode(val key: String, val label: String, val description: String, val corrects: Boolean = false) {
    POLISH("polish", "Fix everything", "Spelling, grammar, punctuation, capitals and text-speak in one go", corrects = true),
    FIX("fix", "Fix spelling and grammar", "Corrects mistakes and leaves the wording alone", corrects = true),
    POLITE("polite", "More polite", "Sounds polite and professional"),
    FRIENDLY("friendly", "Friendlier", "Sounds warmer and more relaxed"),
    SHORTER("shorter", "Shorter", "Says the same in fewer words");

    companion object {
        fun fromKey(key: String): RephraseMode? = entries.firstOrNull { it.key == key }
    }
}
