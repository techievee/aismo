package aismo.sms

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import aismo.sms.data.llm.LiteRtLmGenerator
import aismo.sms.data.llm.LlamaCppGenerator
import aismo.sms.domain.rephrase.RephraseMode
import aismo.sms.domain.rephrase.RephraseOutcome
import aismo.sms.domain.rephrase.RephrasePrompts
import aismo.sms.domain.rephrase.Rephraser
import aismo.sms.domain.llm.TextGenerator
import java.io.File

/**
 * Runs a real model through the app's own [Rephraser] on the 16 drafts of tools/rephrase-eval/drafts.json and reports, per
 * mode, what a user would see: correct suggestions, suggestions that passed the guard but changed the meaning, and safe
 * declines. Not part of normal runs: it needs a model file, passed as
 *   `-e llm_backend llama|litert -e llm_model /data/data/aismo.sms/files/test-models/<file>`.
 * The "correct" judgement uses regular expressions written for these drafts (facts kept, who-does-what kept); they are
 * stricter than the app's guard and exist to measure it. Results go to logcat under RephraseEval.
 */
@RunWith(AndroidJUnit4::class)
class RephraseBackendEvalTest {
    private val app get() = ApplicationProvider.getApplicationContext<AismoApp>()

    private class Draft(val text: String, val facts: List<Regex>, val must: List<Regex>, val mustNot: List<Regex>)

    private fun drafts(): Pair<Int, List<Draft>> {
        val root = Json.parseToJsonElement(InstrumentationRegistry.getInstrumentation().context.assets.open("rephrase/drafts.json").bufferedReader().readText()).jsonObject
        fun regexes(e: kotlinx.serialization.json.JsonElement) = e.jsonArray.map { Regex(it.jsonPrimitive.content, RegexOption.IGNORE_CASE) }
        return root["minShortenable"]!!.jsonPrimitive.content.toInt() to root["drafts"]!!.jsonArray.map {
            val o = it.jsonObject
            Draft(o["draft"]!!.jsonPrimitive.content, regexes(o["facts"]!!), regexes(o["must"]!!), regexes(o["mustNot"]!!))
        }
    }

    private fun correct(mode: RephraseMode, d: Draft, out: String): Boolean {
        val facts = d.facts.all { it.containsMatchIn(out) }
        val roles = d.must.all { it.containsMatchIn(out) } && d.mustNot.none { it.containsMatchIn(out) }
        return facts && roles && (mode != RephraseMode.SHORTER || out.length < d.text.length) && (mode != RephraseMode.POLISH || polished(d.text, out))
    }

    /** "Fix everything" must also leave a capital, a full stop, and none of the text-speak that was in the draft. */
    private fun polished(draft: String, out: String): Boolean {
        val leftover = TEXT_SPEAK.any { Regex("\\b$it\\b").containsMatchIn(draft) && Regex("\\b$it\\b").containsMatchIn(out) }
        return out.firstOrNull()?.isUpperCase() == true && out.trimEnd().lastOrNull() in setOf('.', '!', '?') && !leftover
    }

    private companion object {
        val TEXT_SPEAK = listOf("u", "ur", "pls", "plz", "thx", "cn", "tmrw", "n", "cant", "dont", "wont", "ive", "im", "ill")
    }

    @Test fun evaluateTheBackend() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val backend = args.getString("llm_backend")
        val modelPath = args.getString("llm_model")
        assumeTrue("gated: pass -e llm_backend llama|litert -e llm_model <file>", backend != null && modelPath != null)
        val model = File(modelPath!!)
        assertTrue("model file not found: $modelPath", model.isFile)
        val generator: TextGenerator = when (backend) {
            "llama" -> LlamaCppGenerator(app, model)
            "litert" -> LiteRtLmGenerator(app, model)
            else -> error("unknown backend $backend")
        }
        val prompts = RephrasePrompts.parse(app.assets.open("rephrase/prompts.json").bufferedReader().readText())
        val rephraser = Rephraser(prompts, generator)
        val (minShortenable, all) = drafts()

        val loadStart = System.nanoTime()
        val warm = rephraser.rephrase(RephraseMode.FIX, "cn u call me tomoro")   // loads the model
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        Log.i("RephraseEval", "backend=$backend model=${model.name} first call (load + answer) ${loadMs} ms -> $warm")
        assertTrue("the model could not run: $warm", warm !is RephraseOutcome.Unavailable)

        var totalCorrect = 0; var totalWrong = 0; var totalDeclined = 0; var totalCount = 0; var unavailable = 0
        val allMs = ArrayList<Long>()
        for (mode in RephraseMode.entries) {
            var correct = 0; var wrong = 0; var declined = 0; var same = 0; var n = 0
            val ms = ArrayList<Long>()
            for (d in all) {
                if (mode == RephraseMode.SHORTER && d.text.length < minShortenable) continue
                n++
                val t = System.nanoTime()
                val outcome = rephraser.rephrase(mode, d.text)
                ms += (System.nanoTime() - t) / 1_000_000
                when (outcome) {
                    is RephraseOutcome.Suggestion -> if (correct(mode, d, outcome.text)) correct++ else { wrong++; Log.i("RephraseEval", "  WRONG [${mode.key}] '${d.text.take(48)}' -> '${outcome.text.take(90)}'") }
                    RephraseOutcome.NothingToFix -> same++
                    is RephraseOutcome.Declined -> { declined++; Log.i("RephraseEval", "  declined [${mode.key}] '${d.text.take(48)}' (${outcome.detail})") }
                    is RephraseOutcome.Unavailable -> { unavailable++; Log.i("RephraseEval", "  UNAVAILABLE ${outcome.reason}") }
                    is RephraseOutcome.NoModel -> throw AssertionError("the harness drives the generator directly, so a missing model cannot happen")
                }
            }
            totalCorrect += correct; totalWrong += wrong; totalDeclined += declined; totalCount += n; allMs += ms
            val sorted = ms.sorted()
            Log.i("RephraseEval", "RESULT backend=$backend model=${model.name} mode=${mode.key} n=$n correct=$correct wrongPassedGuard=$wrong declined=$declined nothingToFix=$same " +
                "avgMs=${ms.average().toLong()} p90Ms=${sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.lastIndex)]}")
        }
        val s = allMs.sorted()
        Log.i("RephraseEval", "TOTAL backend=$backend model=${model.name} n=$totalCount correct=$totalCorrect wrongPassedGuard=$totalWrong declined=$totalDeclined unavailable=$unavailable " +
            "avgMs=${allMs.average().toLong()} p90Ms=${s[(s.size * 9 / 10).coerceAtMost(s.lastIndex)]} stats=${(generator as? LlamaCppGenerator)?.lastStats ?: (generator as? LiteRtLmGenerator)?.lastStats}")
        generator.close()
        assertTrue("some rewrites could not run", unavailable == 0)
        Unit
    }
}
