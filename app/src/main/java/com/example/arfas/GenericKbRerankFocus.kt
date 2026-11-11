package com.example.arfas

/**
 * 9 Nov 2026 (AE): the re-ranker reads only the first ~320 tokens of a (question, passage) pair, about
 * 1,000 characters of passage. 4,659 of the guide's 5,389 passages are longer, so a passage whose answer
 * sits in its second half was scored on its first half: the pregnancy danger-sign list ("swelling of face
 * and hands … headache, blurred vision → refer", characters 1,031+ of a 1,404-character passage) was
 * never seen and never ranked.
 *
 * For a passage longer than [WINDOW_CHARS], the re-ranker now reads the run of whole sentences (up to
 * [WINDOW_CHARS]) that best covers the question's words, weighted by how rare each word is in the
 * installed guide (no word list). The heading line stays in front. Passages that fit, and passages
 * whose best run is their beginning, are scored exactly as before. Only the score changes: the
 * passage itself, its evidence window and the answer's sources are untouched.
 */
object GenericKbRerankFocus {
    const val WINDOW_CHARS = 1_000
    private const val HEADING_MAX = 150
    /** The best run must cover at least this much more of the question (by word rarity) than the beginning. */
    private val MIN_GAIN = System.getProperty("focus.gain")?.toDoubleOrNull() ?: 1.5
    private val TOKEN = Regex("[\\p{L}\\p{N}]+")
    private val SENTENCE_END = Regex("(?<=[.!?।;:])\\s+|\\n+|\\s+(?=[•●▪◦])")

    private fun stems(text: String): Set<String> =
        TOKEN.findAll(text.lowercase()).map { it.value }.filter { it.length >= 3 }.map { GenericKbKeyFacts.stem(it) }.toSet()

    private fun weightOf(stem: String): Double = GenericKbKeyFacts.corpusWeight?.invoke(stem) ?: 1.0

    fun focus(question: String, passage: String): String {
        if (passage.length <= WINDOW_CHARS) return passage
        val nl = passage.indexOf('\n')
        val heading = if (nl in 1..HEADING_MAX) passage.substring(0, nl) else ""
        val body = if (heading.isEmpty()) passage else passage.substring(nl + 1)
        val room = WINDOW_CHARS - (if (heading.isEmpty()) 0 else heading.length + 1)
        if (body.length <= room) return passage
        val q = stems(question)
        if (q.isEmpty()) return passage
        // Sentence spans [start, end) over body.
        val spans = ArrayList<IntRange>()
        var start = 0
        SENTENCE_END.findAll(body).forEach { m ->
            if (m.range.first > start) spans += start until m.range.first
            start = m.range.last + 1
        }
        if (start < body.length) spans += start until body.length
        if (spans.size < 2) return passage
        val sentStems = spans.map { stems(body.substring(it.first, it.last + 1)) }
        fun score(from: Int, to: Int): Double {
            val seen = HashSet<String>()
            for (k in from..to) seen.addAll(sentStems[k])
            return q.filter { it in seen }.sumOf { weightOf(it) }
        }
        fun endFor(from: Int): Int {
            var to = from
            while (to + 1 < spans.size && spans[to + 1].last + 1 - spans[from].first <= room) to++
            return to
        }
        val headEnd = endFor(0)
        var best = 0
        val headScore = score(0, headEnd)
        var bestScore = headScore
        for (i in 1 until spans.size) {
            val s = score(i, endFor(i))
            if (s > bestScore + 1e-9) { best = i; bestScore = s }
        }
        // Only a clearly better run replaces the beginning (a small gain only shuffles similar passages).
        if (best == 0 || bestScore < headScore * MIN_GAIN + 1e-9) return passage
        val to = endFor(best)
        val text = body.substring(spans[best].first, minOf(body.length, spans[to].last + 1)).take(room)
        return if (heading.isEmpty()) text else heading + "\n" + text
    }
}
