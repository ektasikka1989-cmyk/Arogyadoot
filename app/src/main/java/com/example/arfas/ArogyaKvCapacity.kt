package com.example.arfas

/**
 * 9 Nov 2026 (AD): the phone engine is opened with an 8192-token window, but on some devices/models
 * the GPU runtime only has 4096 KV entries. A prompt longer than that fails before any word is written
 * ("Prefill input length exceeds available state entries (remaining capacity: 4096)") and the answer
 * came out blank. Pure helpers (host-tested): read the real capacity from the engine's error and pick
 * how much evidence fits it, with the same envelope as PocketRagEvidenceAssembler.
 */
object ArogyaKvCapacity {
    private val CAPACITY = Regex("""exceeds available state entries[^0-9]*(?:remaining capacity:\s*)?(\d+)""", RegexOption.IGNORE_CASE)

    /** True when the engine refused the prompt for being longer than its KV cache. */
    fun isCapacityError(error: String?): Boolean = error?.contains("exceeds available state entries", ignoreCase = true) == true

    /** The capacity the engine reported (tokens), or null when the message does not say. */
    fun reported(error: String?): Int? =
        error?.let { CAPACITY.find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it in 512..1_000_000 }

    /** Evidence characters that fit [capacity] (same reserve and chars/token as the evidence assembler). */
    fun evidenceChars(capacity: Int, maxOutput: Int): Int {
        val reserved = 1_050 + maxOutput.coerceIn(0, 768)
        return ((capacity - reserved).coerceAtLeast(768) * 1.55f).toInt().coerceAtLeast(1_800)
    }

    /**
     * How many leading passages to keep so their text fits [budgetChars] (always at least one; the
     * order is the evidence order, best first). [margin] is the per-passage header cost.
     */
    fun keepCount(lengths: List<Int>, budgetChars: Int, margin: Int = 24): Int {
        var used = 0
        var n = 0
        for (len in lengths) {
            val cost = len + margin
            if (n > 0 && used + cost > budgetChars) break
            used += cost; n++
        }
        return n.coerceAtLeast(if (lengths.isEmpty()) 0 else 1)
    }

    /** Prompt text outside the evidence and the question (the app's system prompt), in tokens. */
    const val BASE_PROMPT_TOKENS = 900

    /**
     * Rough token count of [text] for the on-device model: Devanagari ~1.4 characters per token,
     * other text ~3 (OCR'd English guide text is denser than prose). Errs high, never low by much.
     */
    fun estimateTokens(text: String): Int {
        var deva = 0
        for (ch in text) if (ch in '\u0900'..'\u097F') deva++
        return (deva / 1.4 + (text.length - deva) / 3.0).toInt() + 1
    }

    /**
     * Leading passages that fit [capacity] together with [fixedTokens] (question, instructions, base
     * prompt). [passageTokens] in evidence order; at least one passage is always kept.
     */
    fun keepWithin(passageTokens: List<Int>, fixedTokens: Int, capacity: Int, perPassage: Int = 12): Int {
        var used = fixedTokens
        var n = 0
        for (t in passageTokens) {
            if (n > 0 && used + t + perPassage > capacity) break
            used += t + perPassage; n++
        }
        return n.coerceAtLeast(if (passageTokens.isEmpty()) 0 else 1)
    }
}
