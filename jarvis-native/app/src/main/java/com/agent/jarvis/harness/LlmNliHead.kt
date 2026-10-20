package com.agent.jarvis.harness

import com.agent.jarvis.agent.LocalPrompt
import com.agent.jarvis.local.LocalModelEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure prompt-building and strict parsing for the on-device NLI gate. No Android, no model:
 * the JVM suite covers every parse branch. The label set is [ClaimVerifier]'s.
 */
object NliPrompt {
    /** A label is one word; eight tokens is room for it plus an end marker. */
    const val MAX_TOKENS = 8
    /** Each side is clipped so a long claim cannot blow the prompt or the latency. */
    const val MAX_SIDE_CHARS = 400

    const val SYSTEM = "You are a strict natural-language-inference classifier. Given a PREMISE and a " +
        "HYPOTHESIS, answer with exactly one word: ENTAILMENT if the premise implies the hypothesis, " +
        "CONTRADICTION if the premise and hypothesis cannot both be true, otherwise NEUTRAL. " +
        "Reply with that one word only."

    private val LABEL = Regex("\\b(ENTAILMENT|NEUTRAL|CONTRADICTION)\\b", RegexOption.IGNORE_CASE)
    private val THINK_BLOCK = Regex("(?s)<think>.*?</think>")
    private val SPACES = Regex("\\s+")

    /** ChatML, no-think (the reply starts at the answer). */
    fun build(premise: String, hypothesis: String): String = LocalPrompt.buildNoThink(
        SYSTEM,
        "PREMISE: ${clip(premise)}\nHYPOTHESIS: ${clip(hypothesis)}\nLabel:",
    )

    /**
     * The first label word in the reply, upper-cased; [ClaimVerifier.NEUTRAL] when there is none
     * (empty, garbage, a refusal). NEUTRAL is the safe default: it never supersedes, fails or
     * reinforces anything.
     */
    fun parse(raw: String?): String {
        if (raw.isNullOrBlank()) return ClaimVerifier.NEUTRAL
        var s = THINK_BLOCK.replace(raw, " ")
        val open = s.indexOf("<think>")
        if (open >= 0) s = s.substring(0, open)
        return LABEL.find(s)?.value?.uppercase() ?: ClaimVerifier.NEUTRAL
    }

    private fun clip(s: String) = s.replace(SPACES, " ").trim().take(MAX_SIDE_CHARS)
}

/**
 * [NliHead] backed by the on-device thinker. [generate] is the model call (production:
 * [LocalModelEngine.generate]); [NliHead.classify] is blocking by contract, so call it off the
 * main thread (MemoryClaims.writeVerified does). Any failure, timeout or unparseable reply
 * is NEUTRAL: a broken model degrades to "no refutation, no reinforcement", never to a wrong write.
 *
 * Not proven on a device: the prompt and parse are unit-tested; the label quality of the
 * loaded model has not been measured.
 */
class LlmNliHead(
    private val timeoutMs: Long = TIMEOUT_MS,
    private val generate: suspend (prompt: String, maxTokens: Int) -> Result<String>,
) : NliHead {

    constructor(local: LocalModelEngine, timeoutMs: Long = TIMEOUT_MS) :
        this(timeoutMs, { prompt, max -> local.generate(prompt, max) })

    override fun classify(premise: String, hypothesis: String): String {
        val raw = try {
            runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    generate(NliPrompt.build(premise, hypothesis), NliPrompt.MAX_TOKENS).getOrNull()
                }
            }
        } catch (e: Exception) {
            null
        }
        return NliPrompt.parse(raw)
    }

    companion object {
        /** Per call; a cold model load can be slow, and the verifier makes up to TOP_K calls. */
        const val TIMEOUT_MS = 15_000L
    }
}
