package aismo.sms.domain.rephrase

/** Tidies what a model says into just the message: no <think> blocks, quotes, labels or "Here is the rewritten message:". */
object RephraseCleaner {
    private val think = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL))
    private val label = Regex("^\\s*(?:here(?:'s| is)(?: the)?(?: [a-z]+){0,3}\\s*:|(?:rewritten |revised |new |shorter |polite |friendlier )?(?:message|text|version)\\s*:)\\s*", RegexOption.IGNORE_CASE)

    fun clean(raw: String): String {
        var text = think.replace(raw, "").trim()
        text = label.replace(text, "").trim()
        for ((open, close) in listOf("\"" to "\"", "“" to "”", "`" to "`", "'" to "'")) {
            if (text.length >= 2 && text.startsWith(open) && text.endsWith(close)) text = text.substring(1, text.length - 1).trim()
        }
        return text
    }
}

/**
 * Decides whether a model's rewrite may be shown. A small model sometimes drops an amount, invents a time, echoes the
 * instruction or changes nothing; these checks catch that. They cannot see a change of meaning (who owes whom), which is
 * why the app always shows the suggestion for the user to accept.
 */
object RephraseGuard {
    sealed interface Verdict {
        data class Accept(val text: String) : Verdict
        /** The model changed nothing, which is the right answer for a correcting mode when there were no mistakes. */
        data object Unchanged : Verdict
        data class Reject(val reason: String) : Verdict
    }

    private val number = Regex("\\d+(?:[.:]\\d+)*")
    private val url = Regex("(?i)\\b(?:https?://|www\\.)[^\\s]+")
    private val email = Regex("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+")
    private val weekday = Regex("(?i)\\b(monday|tuesday|tues|wednesday|thursday|thurs|thur|friday|saturday|sunday)\\b")
    private const val MONTHS = "january|jan|february|feb|march|mar|april|apr|may|june|jun|july|jul|august|aug|september|sept|sep|october|oct|november|nov|december|dec"
    private val monthBeforeNumber = Regex("(?i)\\b($MONTHS)\\b\\.?\\s*\\d")
    private val monthAfterNumber = Regex("(?i)\\d(?:st|nd|rd|th)?\\s*(?:of\\s+)?($MONTHS)\\b")
    private val emoji = Regex("[\\u2600-\\u27BF\\x{1F300}-\\x{1FAFF}\\x{1F000}-\\x{1F2FF}]")
    private val allCaps = Regex("\\b[A-Z]{3,}\\b")
    private val capitalised = Regex("\\b[A-Z][a-z]+(?:'s)?\\b")
    private val titles = setOf("dr", "mr", "mrs", "ms", "miss", "prof", "hi", "hey", "hello", "dear", "thanks", "thank", "please", "sorry", "ok", "okay")
    private val startsAsQuestion = Regex("(?i)(?:^|[.!?\\n]\\s*)(?:can|cn|could|cud|would|wud|will|do|does|did|is|are|shall|should|what|when|where|who|why|how|any)\\b")
    private val askedOfReader = Regex("(?i)\\b(?:can|cn|could|cud|would|wud|will)\\s+(?:you|u|ya)\\b")
    private val dateWords = Regex("(?i)^(?:$MONTHS|monday|tuesday|tues|wednesday|thursday|thurs|thur|friday|saturday|sunday)$")
    private val myWords = Regex("(?i)\\b(?:my|mine|myself)\\b")
    private val firstPerson = Regex("(?i)\\b(?:i|me|my|mine|myself|i'm|i'll|i've|i'd|im)\\b")
    private val secondPerson = Regex("(?i)\\b(?:you|your|yours|you're|you'll|you've|u|ur|ya)\\b")
    private val instructionEcho = listOf("rewrite this message", "fix the spelling and grammar", "make this message shorter", "delete the unnecessary words", "rewrite this as a")

    fun judge(mode: RephraseMode, draft: String, raw: String): Verdict {
        val text = RephraseCleaner.clean(raw)
        if (text.isBlank()) return Verdict.Reject("the model gave no answer")
        val lower = text.lowercase()
        if (instructionEcho.any { it in lower }) return Verdict.Reject("the model repeated the instructions")
        if (sameWords(text, draft)) return if (mode.corrects) Verdict.Unchanged else Verdict.Reject("the model changed nothing")
        facts(draft).filterNot { it in facts(text) }.firstOrNull()?.let { return Verdict.Reject("the rewrite lost \"$it\"") }
        // A time or amount that was not in the draft is an invention.
        numbers(text).filterNot { it in numbers(draft) }.firstOrNull()?.let { return Verdict.Reject("the rewrite added \"$it\"") }
        if (text.length > 3 * draft.length + 40) return Verdict.Reject("the rewrite is far longer than the message")
        if ('\n' !in draft && "\n\n" in text) return Verdict.Reject("the rewrite is more than one message")
        if (emoji.containsMatchIn(text) && !emoji.containsMatchIn(draft)) return Verdict.Reject("the rewrite added an emoji")
        if (mode == RephraseMode.SHORTER && text.length >= draft.length) return Verdict.Reject("the rewrite is not shorter")
        // Names and shouted keywords ("Dr Lee", "STOP") are the details that matter most and the easiest to lose.
        names(draft).filterNot { it in words(text) }.firstOrNull()?.let { return Verdict.Reject("the rewrite lost \"$it\"") }
        // Two cheap signs that the writer and the reader were swapped.
        if (myWords.containsMatchIn(text) && !myWords.containsMatchIn(draft)) return Verdict.Reject("the rewrite speaks as \"my\" where the draft did not")
        if (swapsSpeaker(draft, text)) return Verdict.Reject("the rewrite swaps who is speaking and who is addressed")
        // Fixing or shortening must not turn a statement into a question.
        if ((mode.corrects || mode == RephraseMode.SHORTER) && '?' in text && '?' !in draft && !asksAQuestion(draft)) return Verdict.Reject("the rewrite turned a statement into a question")
        return Verdict.Accept(text)
    }

    /** Numbers as written, without thousands separators, so "1,200" and "1200" agree. */
    private fun numbers(text: String): Set<String> = number.findAll(text.replace(Regex("(?<=\\d),(?=\\d{3})"), "")).map { it.value }.toSet()

    /** What a rewrite must keep: numbers, links, e-mail addresses, weekdays and dated months. */
    private fun facts(text: String): Set<String> = buildSet {
        addAll(numbers(text))
        url.findAll(text).forEach { add(it.value.trimEnd('.', ',', '!', '?', ')').lowercase()) }
        email.findAll(text).forEach { add(it.value.lowercase()) }
        weekday.findAll(text).forEach { add(it.value.lowercase().take(3)) }
        monthBeforeNumber.findAll(text).forEach { add(it.groupValues[1].lowercase().take(3)) }
        monthAfterNumber.findAll(text).forEach { add(it.groupValues[1].lowercase().take(3)) }
    }

    /** Proper nouns and ALL-CAPS words from the middle of a text; the first word of a sentence is capitalised anyway. */
    private fun names(text: String): Set<String> = buildSet {
        allCaps.findAll(text).forEach { add(it.value.lowercase()) }
        for (match in capitalised.findAll(text)) {
            val before = text.substring(0, match.range.first).trimEnd()
            val startsSentence = before.isEmpty() || before.last() in ".!?\n"
            val word = match.value.removeSuffix("'s")
            if (!startsSentence && word.lowercase() !in titles && !dateWords.matches(word)) add(word.lowercase())
        }
    }

    private fun words(text: String): Set<String> = Regex("[\\p{L}\\p{N}']+").findAll(text.lowercase()).map { it.value.removeSuffix("'s") }.toSet()

    /** A draft that only addressed the reader ("ur membership") must not come back speaking only as the writer, or the reverse. */
    private fun swapsSpeaker(draft: String, text: String): Boolean {
        val draftFirst = firstPerson.containsMatchIn(draft); val draftSecond = secondPerson.containsMatchIn(draft)
        val textFirst = firstPerson.containsMatchIn(text); val textSecond = secondPerson.containsMatchIn(text)
        return (draftSecond && !draftFirst && textFirst && !textSecond) || (draftFirst && !draftSecond && textSecond && !textFirst)
    }

    /** Informal questions often have no question mark ("cn u call me"); adding one to those is correct. */
    private fun asksAQuestion(draft: String) = startsAsQuestion.containsMatchIn(draft) || askedOfReader.containsMatchIn(draft)

    private fun sameWords(a: String, b: String) = squash(a) == squash(b)
    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
}
