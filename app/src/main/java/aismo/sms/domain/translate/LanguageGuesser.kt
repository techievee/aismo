package aismo.sms.domain.translate

/**
 * Guesses the language a short message is written in, from the text alone — no model, no network. It is used to
 * decide whether to auto-translate a message and to tell the model where its text comes from. The guess is
 * deliberately conservative: when it cannot tell, it says so, and the user can always pick the language in the
 * dialog.
 */
object LanguageGuesser {

    /** A writing system and the range of Unicode points its characters occupy. */
    enum class Script(val from: Int, val to: Int) {
        GREEK(0x0370, 0x03FF),
        CYRILLIC(0x0400, 0x04FF),
        HEBREW(0x0590, 0x05FF),
        ARABIC(0x0600, 0x06FF),
        DEVANAGARI(0x0900, 0x097F),
        BENGALI(0x0980, 0x09FF),
        GURMUKHI(0x0A00, 0x0A7F),
        GUJARATI(0x0A80, 0x0AFF),
        ORIYA(0x0B00, 0x0B7F),
        TAMIL(0x0B80, 0x0BFF),
        TELUGU(0x0C00, 0x0C7F),
        KANNADA(0x0C80, 0x0CFF),
        MALAYALAM(0x0D00, 0x0D7F),
        SINHALA(0x0D80, 0x0DFF),
        THAI(0x0E00, 0x0E7F),
        HIRAGANA(0x3041, 0x3096),
        KATAKANA(0x30A1, 0x30FA),
        CJK(0x4E00, 0x9FFF),
        HANGUL(0xAC00, 0xD7AF),
        ETHIOPIC(0x1200, 0x137F),
        /** Arabic presentation forms, as used by Persian. */
        PERSIAN_ARABIC(0xFB50, 0xFDFF),
    }

    private fun scriptOf(ch: Char): Script? = Script.entries.firstOrNull { ch.code in it.from..it.to }

    /**
     * The language the text is written in, or null when it cannot be told (which the callers read as "probably the
     * phone's own language"). Non-Latin scripts pin the language outright; for Latin script the most common words
     * of each language are counted, and a language needs at least two distinct hits to win.
     */
    fun guess(text: String): Language? {
        val counts = HashMap<Script, Int>()
        var latinLetters = 0
        for (ch in text) {
            val script = scriptOf(ch)
            if (script != null) counts[script] = (counts[script] ?: 0) + 1
            else if (ch in 'a'..'z' || ch in 'A'..'Z') latinLetters++
        }
        if (latinLetters == 0) {
            val (script, n) = counts.maxByOrNull { it.value } ?: return null
            if (n < 2) return null
            return languageFor(script, counts)
        }
        return guessFromLatinWords(text)
    }

    /** Chinese and Japanese share CJK ideographs; kana is what separates the two. */
    private fun languageFor(script: Script, counts: Map<Script, Int>): Language? = when (script) {
        Script.CJK -> if ((counts[Script.HIRAGANA] ?: 0) + (counts[Script.KATAKANA] ?: 0) >= 2) Language.JA else Language.ZH
        Script.GREEK -> Language.EL
        Script.CYRILLIC -> Language.RU
        Script.HEBREW -> Language.HE
        Script.ARABIC, Script.PERSIAN_ARABIC -> Language.AR
        Script.DEVANAGARI -> Language.HI
        Script.BENGALI -> Language.BN
        Script.GURMUKHI -> Language.PA
        Script.GUJARATI -> Language.GU
        Script.ORIYA -> Language.OR
        Script.TAMIL -> Language.TA
        Script.TELUGU -> Language.TE
        Script.KANNADA -> Language.KN
        Script.MALAYALAM -> Language.ML
        Script.SINHALA -> Language.SI
        Script.THAI -> Language.TH
        Script.HANGUL -> Language.KO
        Script.ETHIOPIC -> Language.AM
        Script.HIRAGANA, Script.KATAKANA -> Language.JA
    }

    /** Words that occur so often in a language that two of them make the language recognizable. */
    private val LATIN_WORDS = mapOf(
        Language.ES to setOf("que", "y", "de", "la", "el", "en", "los", "se", "las", "un", "una", "por", "para", "con", "no", "su", "al", "lo", "como", "más", "pero", "también", "hoy", "mañana", "gracias", "buenos"),
        Language.FR to setOf("je", "tu", "vous", "il", "elle", "on", "est", "sont", "et", "de", "la", "le", "les", "des", "une", "en", "pour", "pas", "avec", "mais", "dans", "sur", "plus", "bien", "très", "aussi", "vraiment", "merci", "bonjour", "demain", "hier"),
        Language.DE to setOf("und", "der", "die", "das", "ist", "nicht", "ein", "eine", "ich", "du", "wir", "sie", "es", "auch", "auf", "den", "dem", "für", "mit", "hat", "haben", "heute", "morgen", "bitte", "danke", "guten"),
        Language.PT to setOf("que", "de", "da", "do", "e", "para", "com", "não", "uma", "em", "por", "mas", "você", "como", "mais", "foi", "ser", "os", "as", "ao", "hoje", "obrigado", "obrigada", "bom", "boa"),
        Language.IT to setOf("che", "di", "la", "il", "e", "non", "un", "una", "in", "per", "si", "come", "sono", "ma", "con", "da", "più", "anche", "oggi", "grazie", "buon", "ciao", "domani"),
        Language.NL to setOf("en", "de", "het", "dat", "is", "niet", "ik", "je", "een", "voor", "met", "op", "om", "van", "maar", "zijn", "naar", "ook", "als", "ze", "vandaag", "morgen", "dank", "goed"),
        Language.SV to setOf("och", "att", "det", "som", "är", "jag", "du", "inte", "på", "för", "med", "till", "en", "ett", "har", "kan", "men", "när", "vad", "idag", "imorgon", "tack", "hej"),
        Language.NO to setOf("og", "at", "det", "som", "er", "jeg", "du", "ikke", "på", "for", "med", "til", "en", "et", "har", "kan", "men", "når", "hva", "i dag", "imorgen", "takk", "hei"),
        Language.DA to setOf("og", "at", "det", "som", "er", "jeg", "du", "ikke", "på", "for", "med", "til", "en", "et", "har", "kan", "men", "når", "hvad", "i dag", "i morgen", "tak", "hej"),
        Language.FI to setOf("ja", "on", "että", "se", "ei", "minä", "sinä", "hän", "ne", "oli", "myös", "kanssa", "mutta", "kuin", "mitä", "jos", "nyt", "vain", "niin", "tänään", "huomenna", "kiitos", "hei"),
        Language.IS to setOf("og", "að", "að", "sem", "er", "ég", "þú", "hann", "hun", "þeir", "en", "í", "á", "með", "til", "eftir", "nú", "í dag", "á morgun", "takk", "halló"),
        Language.TR to setOf("ve", "de", "bu", "bir", "için", "ne", "da", "mi", "ile", "ya", "ama", "çok", "ben", "sen", "var", "yok", "şu", "her", "gibi", "bugün", "yarın", "teşekkürler", "günaydın"),
        Language.PL to setOf("i", "w", "nie", "to", "na", "że", "się", "jest", "jak", "ale", "co", "po", "od", "tak", "za", "też", "bo", "by", "dziś", "jutro", "dziękuję", "dzień"),
        Language.CS to setOf("a", "v", "že", "se", "na", "je", "to", "ne", "jsme", "jsem", "jak", "ale", "co", "po", "od", "tak", "za", "dnes", "zítra", "děkuji", "dobré"),
        Language.RO to setOf("și", "de", "nu", "un", "este", "cu", "în", "pentru", "dar", "la", "se", "ce", "mai", "ca", "fi", "a", "au", "pe", "azi", "mâine", "mulțumesc"),
        Language.HU to setOf("és", "az", "hogy", "de", "nem", "egy", "vagy", "csak", "mert", "már", "így", "is", "azt", "ezt", "ott", "ma", "holnap", "köszönöm", "szia", "jó"),
        Language.ID to setOf("dan", "yang", "di", "ini", "itu", "tidak", "saya", "kamu", "dengan", "untuk", "ada", "sudah", "akan", "bisa", "juga", "dari", "pada", "ke", "kalau", "hari ini", "besok", "terima kasih", "halo"),
        Language.MS to setOf("dan", "yang", "di", "ini", "itu", "tidak", "saya", "anda", "dengan", "untuk", "ada", "sudah", "akan", "boleh", "juga", "dari", "pada", "ke", "kalau", "esok", "terima kasih", "hai"),
        Language.SW to setOf("na", "ya", "ni", "hii", "hiyo", "kwa", "pia", "tu", "wao", "yake", "leo", "kesho", "asante", "habari", "nani", "vipi"),
    )

    private fun guessFromLatinWords(text: String): Language? {
        // Split on anything that is not a letter or a number, so diacritics (yarın, très, não) stay inside their words.
        // Single letters stay in, because a few languages use them as words (y in Spanish, e in Portuguese); two
        // distinct hits are still required, so a stray letter cannot decide a language on its own.
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        var best: Language? = null
        var bestHits = 0
        for ((language, stopWords) in LATIN_WORDS) {
            val hits = stopWords.count { it in words }
            if (hits > bestHits) { best = language; bestHits = hits }
        }
        return if (bestHits >= 2) best else null
    }
}
