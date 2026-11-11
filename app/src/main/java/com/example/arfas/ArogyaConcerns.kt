package com.example.arfas

/**
 * 9 Nov 2026: evidence for every problem in a question, without word lists.
 *
 * The on-device model restates the question and writes one search line per problem it asks about
 * ("What should be done when a child is vomiting?"). The normal evidence is built first. A line whose
 * most specific word — judged by how rare the word is in the installed guide ([GenericKbKeyFacts.focusStem],
 * no list) — appears in none of the passage windows the model will read gets one short search of its own
 * (its own re-ranker question, so scores stay comparable). Its best passages that name that word are placed
 * after the normal passages, and its best fact is put first so the passage gets a window.
 *
 * Retrieval only: the question, the prompt and the answer limits are unchanged; no answer text is written
 * here. Pure Kotlin, host-tested. Gated by ArogyaFlags.perConcernSearch.
 */
object ArogyaConcerns {
    /** Most extra searches per question (each costs one short search on the phone). */
    const val MAX_EXTRA_SEARCHES = 3
    /** Passages kept from one extra search. */
    const val PER_CONCERN = 2
    /** Only this many of an extra search's best hits are considered. */
    const val PICK_DEPTH = 4
    /** Candidate pool of one extra search (smaller than a full question's: fewer re-ranker pairs). */
    const val SEARCH_POOL = 8
    /** An extra search is used only when its best re-ranker score reaches this (= the weak-evidence line). */
    const val MIN_RERANK = 1.0f

    /**
     * The line's most specific word stem, skipping words it shares with the question's other lines
     * ([lines]); null when guide-wide weights are not loaded.
     */
    fun focus(line: String, lines: List<String> = emptyList()): String? =
        GenericKbKeyFacts.focusStem(line, lines.filter { it != line })

    /** A text covers a line when it contains the line's focus word (always true without weights). */
    fun covers(line: String, text: String, lines: List<String> = emptyList()): Boolean =
        focus(line, lines)?.let { GenericKbKeyFacts.hasStem(text, it) } ?: true

    /** Lines none of the windows the model reads covers. */
    fun uncovered(lines: List<String>, windows: List<String>): List<String> =
        lines.filter { l -> focus(l, lines) != null && windows.none { covers(l, it, lines) } }

    /** Up to [PER_CONCERN] of an extra search's first [PICK_DEPTH] hits that cover the line and are not already kept. */
    fun <T> pick(line: String, lines: List<String>, hits: List<T>, textOf: (T) -> String, keyOf: (T) -> String, exclude: Set<String>): List<T> =
        hits.take(PICK_DEPTH).filter { keyOf(it) !in exclude && covers(line, textOf(it), lines) }.take(PER_CONCERN)

    /**
     * Passages for an uncovered line already found by the main search but ranked below the passages the model
     * reads (no extra search needed): the first [PER_CONCERN] that name the line's focus word, in the main order.
     */
    fun <T> fromMain(line: String, lines: List<String>, rest: List<T>, textOf: (T) -> String): List<T> =
        rest.filter { covers(line, textOf(it), lines) }.take(PER_CONCERN)

    /**
     * Evidence order: the main search's first [headCount] passages exactly as they were (so a question
     * whose problems are already covered reads the same evidence as before), then the extra passages,
     * then the rest of the main search.
     */
    fun <T> merge(main: List<T>, extras: List<List<T>>, keyOf: (T) -> String, headCount: Int): List<T> {
        val head = main.take(headCount)
        val headKeys = head.map(keyOf).toSet()
        val extra = extras.flatten().filter { keyOf(it) !in headKeys }.distinctBy(keyOf)
        if (extra.isEmpty()) return main
        val extraKeys = extra.map(keyOf).toSet()
        return head + extra + main.drop(headCount).filter { keyOf(it) !in extraKeys }
    }

    /** Facts put first (so their passages get a window), the others after, repeats removed. */
    fun <F> prioritise(add: List<F>, facts: List<F>, keyOf: (F) -> String): List<F> {
        if (add.isEmpty()) return facts
        val seen = HashSet<String>()
        return (add + facts).filter { seen.add(keyOf(it)) }
    }

    /**
     * Facts of several lines in turns (first fact of each line, then the second of each…), repeats removed,
     * so the windows the model reads (chosen in fact order) include one passage per problem.
     */
    fun <T> interleave(lists: List<List<T>>, keyOf: (T) -> String): List<T> {
        val out = mutableListOf<T>()
        val seen = HashSet<String>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (l in lists) l.getOrNull(i)?.let { if (seen.add(keyOf(it))) out += it }
        return out
    }
}
