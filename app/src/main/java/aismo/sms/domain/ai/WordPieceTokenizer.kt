package aismo.sms.domain.ai

import java.text.Normalizer
import java.util.Locale

/**
 * The uncased BERT tokenizer that the bundled embedding model was trained with: clean the text, space out CJK
 * characters, strip accents, lowercase, split on whitespace and punctuation, then greedy longest-match WordPiece.
 * The result is `[CLS] pieces [SEP]`, cut to [maxTokens].
 *
 * One deliberate difference from the reference implementation: a literal "[CLS]" or "[SEP]" in a message is
 * tokenized as ordinary text instead of being read as a special token.
 */
class WordPieceTokenizer(private val vocab: Map<String, Int>, private val maxTokens: Int = DEFAULT_MAX_TOKENS) {
    private val unk = id(UNK)
    private val cls = id(CLS)
    private val sep = id(SEP)

    init {
        require(maxTokens >= 3) { "room for [CLS], [SEP] and one piece is needed" }
    }

    private fun id(token: String) = vocab[token] ?: throw IllegalArgumentException("vocabulary has no $token")

    fun encode(text: String): IntArray {
        val ids = ArrayList<Int>(minOf(maxTokens, 32))
        ids += cls
        val room = maxTokens - 2
        var pieces = 0
        for (word in words(normalize(text))) {
            if (pieces >= room) break
            val wordPieces = wordPieces(word)
            for (p in wordPieces) {
                if (pieces >= room) break
                ids += p
                pieces++
            }
        }
        ids += sep
        return ids.toIntArray()
    }

    /** Cleaning, CJK spacing, accent stripping and lowercasing, in the order the reference normalizer applies them. */
    private fun normalize(text: String): String {
        val cleaned = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) continue
            val c = if (isWhitespace(cp)) ' '.code else cp
            if (isCjk(c)) { cleaned.append(' ').appendCodePoint(c).append(' ') } else cleaned.appendCodePoint(c)
        }
        val decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        i = 0
        while (i < decomposed.length) {
            val cp = decomposed.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
            if (cp < 0x80) out.append(if (cp in 'A'.code..'Z'.code) (cp + 32).toChar() else cp.toChar())
            // Per code point and not per string, so the final-sigma rule of String.lowercase never applies.
            else out.append(String(Character.toChars(cp)).lowercase(Locale.ROOT))
        }
        return out.toString()
    }

    /** Splits on whitespace; every punctuation character is a word of its own. */
    private fun words(text: String): List<String> {
        val words = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            when {
                isWhitespace(cp) -> flush(current, words)
                isPunctuation(cp) -> { flush(current, words); words += String(Character.toChars(cp)) }
                else -> current.appendCodePoint(cp)
            }
        }
        flush(current, words)
        return words
    }

    private fun flush(current: StringBuilder, words: MutableList<String>) {
        if (current.isNotEmpty()) { words += current.toString(); current.setLength(0) }
    }

    /** Greedy longest match, `##` on continuations; a word with no complete split, or a very long one, is one [UNK]. */
    private fun wordPieces(word: String): List<Int> {
        if (word.codePointCount(0, word.length) > MAX_WORD_CHARS) return listOf(unk)
        val pieces = ArrayList<Int>(2)
        var start = 0
        while (start < word.length) {
            var end = word.length
            var found = -1
            while (end > start) {
                val candidate = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
                val hit = vocab[candidate]
                if (hit != null) { found = hit; break }
                end = word.offsetByCodePoints(end, -1)
            }
            if (found < 0) return listOf(unk)
            pieces += found
            start = end
        }
        return pieces
    }

    // The reference uses the Unicode White_Space property, which Character.isWhitespace does not match
    // (it excludes the non-breaking spaces, and includes some control characters).
    private fun isWhitespace(cp: Int) = when (cp) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** Control, format, private-use, surrogate and unassigned characters are dropped, except the three that count as spaces. */
    private fun isControl(cp: Int): Boolean {
        if (cp == '\t'.code || cp == '\n'.code || cp == '\r'.code) return false
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> true
            else -> false
        }
    }

    private fun isPunctuation(cp: Int): Boolean {
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCjk(cp: Int) = cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x20000..0x2A6DF ||
        cp in 0x2A700..0x2B73F || cp in 0x2B740..0x2B81F || cp in 0x2B820..0x2CEAF || cp in 0xF900..0xFAFF || cp in 0x2F800..0x2FA1F

    companion object {
        const val DEFAULT_MAX_TOKENS = 128
        private const val MAX_WORD_CHARS = 100
        private const val UNK = "[UNK]"
        private const val CLS = "[CLS]"
        private const val SEP = "[SEP]"

        /** One token per line, the line number being its id (the `vocab.txt` layout). */
        fun fromVocabulary(lines: List<String>, maxTokens: Int = DEFAULT_MAX_TOKENS): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(lines.size * 2)
            lines.forEachIndexed { index, token -> vocab[token] = index }
            return WordPieceTokenizer(vocab, maxTokens)
        }
    }
}
