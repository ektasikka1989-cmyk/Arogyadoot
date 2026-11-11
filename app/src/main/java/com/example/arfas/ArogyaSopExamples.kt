package com.example.arfas

import kotlin.random.Random

/**
 * 9 Nov 2026: Ask SOP example questions change every time the screen opens. The pool is the questions
 * already tested against the guide (assets/sop/example_questions.txt, one per line: English, Hindi, Hinglish,
 * misspelt, several symptoms). Pure Kotlin, host-tested; the asset is read in SopEmptyState.
 */
object ArogyaSopExamples {
    const val ASSET = "sop/example_questions.txt"
    const val SHOWN = 4
    /** Longer questions do not fit a card nicely. */
    private const val MAX_CHARS = 140

    /** One question per line; blank lines, "#" comments, over-long lines and duplicates are skipped. */
    fun parse(text: String): List<String> =
        text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.length <= MAX_CHARS }
            .distinctBy { it.lowercase() }.toList()

    private fun isHindiScript(q: String) = q.any { it in 'ऀ'..'ॿ' }

    /**
     * [n] different questions, never one of [avoid] (the set shown last time) when the pool allows.
     * At least one in Hindi script and one in Latin script, so both kinds of question get tried.
     */
    fun pick(pool: List<String>, n: Int = SHOWN, avoid: Collection<String> = emptyList(), random: Random = Random.Default): List<String> {
        if (pool.isEmpty() || n <= 0) return emptyList()
        val fresh = pool.filterNot { it in avoid }.let { if (it.size >= n) it else pool }
        val shuffled = fresh.shuffled(random)
        val out = mutableListOf<String>()
        shuffled.firstOrNull { isHindiScript(it) }?.let { out += it }
        shuffled.firstOrNull { !isHindiScript(it) }?.let { out += it }
        shuffled.forEach { if (out.size < n && it !in out) out += it }
        return out.take(n).shuffled(random)
    }

    /** The set shown last, so the next opening shows other questions (kept only while the app runs). */
    @Volatile var lastShown: List<String> = emptyList()
}
