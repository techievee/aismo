package aismo.sms.domain.rephrase

import aismo.sms.domain.llm.LlmBackend

/** What happened when the user asked for a rewrite. */
sealed interface RephraseOutcome {
    /** A rewrite that passed the checks, for the user to accept or not. */
    data class Suggestion(val text: String, val attempts: Int) : RephraseOutcome

    /** "Fix" found nothing to fix. */
    data object NothingToFix : RephraseOutcome

    /** The model answered but nothing it said was safe to offer. */
    data class Declined(val message: String, val detail: String = "") : RephraseOutcome

    /** No model file is installed for the chosen backend yet; the user needs to set one up in Settings. */
    data class NoModel(val backend: LlmBackend) : RephraseOutcome

    /** The assistant cannot run: no model chosen, a model file that is not there, or a model that failed. */
    data class Unavailable(val reason: String) : RephraseOutcome
}
