package aismo.sms.domain.translate

import aismo.sms.domain.translate.LanguageGuesser.Script

/**
 * A language the translation can go into or out of. [key] is the ISO 639-1 code, [label] is what the picker shows,
 * and [scripts] are the writing systems the language is normally written in (empty for the Latin script). The list
 * is deliberately short: these are the languages the small on-device model handles, and short texts, not academic
 * languages, are where SMS volume is.
 */
enum class Language(val key: String, val label: String, val scripts: Set<Script> = emptySet()) {
    EN("en", "English"),
    HI("hi", "Hindi", setOf(Script.DEVANAGARI)),
    MR("mr", "Marathi", setOf(Script.DEVANAGARI)),
    BN("bn", "Bengali", setOf(Script.BENGALI)),
    PA("pa", "Punjabi", setOf(Script.GURMUKHI)),
    GU("gu", "Gujarati", setOf(Script.GUJARATI)),
    OR("or", "Oriya", setOf(Script.ORIYA)),
    TA("ta", "Tamil", setOf(Script.TAMIL)),
    TE("te", "Telugu", setOf(Script.TELUGU)),
    ML("ml", "Malayalam", setOf(Script.MALAYALAM)),
    KN("kn", "Kannada", setOf(Script.KANNADA)),
    UR("ur", "Urdu", setOf(Script.ARABIC, Script.PERSIAN_ARABIC)),
    AR("ar", "Arabic", setOf(Script.ARABIC, Script.PERSIAN_ARABIC)),
    FA("fa", "Persian", setOf(Script.ARABIC, Script.PERSIAN_ARABIC)),
    HE("he", "Hebrew", setOf(Script.HEBREW)),
    ZH("zh", "Chinese", setOf(Script.CJK)),
    JA("ja", "Japanese", setOf(Script.CJK, Script.HIRAGANA, Script.KATAKANA)),
    KO("ko", "Korean", setOf(Script.HANGUL)),
    TH("th", "Thai", setOf(Script.THAI)),
    VI("vi", "Vietnamese"),
    ID("id", "Indonesian"),
    MS("ms", "Malay"),
    SI("si", "Sinhala", setOf(Script.SINHALA)),
    AM("am", "Amharic", setOf(Script.ETHIOPIC)),
    SW("sw", "Swahili"),
    ES("es", "Spanish"),
    FR("fr", "French"),
    DE("de", "German"),
    PT("pt", "Portuguese"),
    IT("it", "Italian"),
    NL("nl", "Dutch"),
    SV("sv", "Swedish"),
    NO("no", "Norwegian"),
    DA("da", "Danish"),
    FI("fi", "Finnish"),
    IS("is", "Icelandic"),
    EL("el", "Greek", setOf(Script.GREEK)),
    TR("tr", "Turkish"),
    PL("pl", "Polish"),
    CS("cs", "Czech"),
    RO("ro", "Romanian"),
    HU("hu", "Hungarian"),
    BG("bg", "Bulgarian", setOf(Script.CYRILLIC)),
    RU("ru", "Russian", setOf(Script.CYRILLIC)),
    UK("uk", "Ukrainian", setOf(Script.CYRILLIC));

    companion object {
        fun fromKey(key: String?): Language? = entries.firstOrNull { it.key == key }
    }
}

/** The language to translate into by default: the phone's, when the app knows it, otherwise English. */
fun defaultTarget(languageCode: String): Language = Language.fromKey(languageCode) ?: Language.EN
