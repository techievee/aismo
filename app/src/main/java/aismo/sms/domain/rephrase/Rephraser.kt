package aismo.sms.domain.rephrase

import kotlinx.coroutines.CancellationException
import aismo.sms.domain.llm.GenerationException
import aismo.sms.domain.llm.TextGenerator

/**
 * Asks a [TextGenerator] to rewrite a draft and only passes on what [RephraseGuard] accepts. If the first answer is
 * rejected it asks once more with a higher temperature (a different roll of the dice) before giving up.
 */
class Rephraser(private val prompts: RephrasePrompts, private val generator: TextGenerator) {
    /**
     * [variation] 0 is the first request; higher numbers ask again for a different answer ("Try again"). [context] is the
     * recent messages of the thread the reply belongs to, shown to the model so the rewrite fits the conversation.
     */
    suspend fun rephrase(mode: RephraseMode, draft: String, context: String? = null, variation: Int = 0): RephraseOutcome {
        val text = draft.trim()
        if (text.isEmpty()) return RephraseOutcome.Declined("There is nothing to rewrite yet.")
        if (text.length > MAX_CHARS) return RephraseOutcome.Declined("This message is too long to rewrite here (up to $MAX_CHARS characters).")

        val messages = prompts.messages(mode, text, context)
        // A rewrite is about as long as the draft; this stops a model that rambles without cutting off a long draft.
        val maxTokens = minOf(prompts.sampling.maxNewTokens, text.length / 2 + 40)
        // Asking again with the same temperature and seed would give the same answer, so a retry starts hotter.
        val temperatures = if (variation == 0) listOf(prompts.sampling.temperature, prompts.sampling.retryTemperature) else listOf(prompts.sampling.retryTemperature, prompts.sampling.retryTemperature)
        var lastRejection = ""
        for ((attempt, temperature) in temperatures.withIndex()) {
            val raw = try {
                generator.generate(messages, maxTokens, temperature, SEEDS[attempt] + variation * 101)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GenerationException) {
                return RephraseOutcome.Unavailable(e.message ?: "the writing assistant could not run")
            }
            when (val verdict = RephraseGuard.judge(mode, text, raw)) {
                is RephraseGuard.Verdict.Accept -> return RephraseOutcome.Suggestion(verdict.text, attempt + 1)
                RephraseGuard.Verdict.Unchanged -> return RephraseOutcome.NothingToFix
                is RephraseGuard.Verdict.Reject -> lastRejection = verdict.reason
            }
        }
        return RephraseOutcome.Declined(
            if (mode == RephraseMode.SHORTER) "This can't be shortened without losing something, so it was left as it is."
            else "Couldn't rewrite this safely, so it was left as it is. You can try again.",
            lastRejection,
        )
    }

    companion object {
        const val MAX_CHARS = 500
        private val SEEDS = intArrayOf(7, 11)
    }
}
