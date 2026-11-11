package com.example.arfas

/**
 * 9 Nov 2026 (AH): the overlap guide repeats a neighbouring sentence at the start/end of each passage, so two
 * adjacent passages in one answer's evidence carried the same sentence twice ("ASHAs should motivate every
 * pregnant woman … labour complications may suddenly occur", pages 69 and 70). A repeated sentence in the prompt
 * pushed Gemma into a loop (phone 23:27: the sentence written again and again, then a two-line answer).
 * Each sentence is kept only the first time it appears across the evidence passages (best passage first).
 * Pure Kotlin, host-tested.
 */
object ArogyaEvidenceDedupe {
    /** Shorter sentences (headings, "Yes", list stubs) are never removed. */
    private const val MIN_KEY_CHARS = 30
    private val SENTENCE = Regex("[^.!?।\\n]+(?:[.!?।]+[\"')\\]]*|\\n|$)")

    private val HEADER_PREFIXES = listOf("Source ID:", "Source:", "Note:", "[")

    // Letters, digits and the vowel signs of Devanagari (combining marks are not "letters").
    private fun key(s: String): String = s.lowercase().filter {
        it.isLetterOrDigit() || Character.getType(it).let { t -> t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt() }
    }

    /** The same passages, in order, with sentences already given by an earlier passage removed. */
    fun dedupe(passages: List<String>): List<String> {
        val seen = HashSet<String>()
        return passages.map { p ->
            val out = StringBuilder()
            SENTENCE.findAll(p).forEach { m ->
                val s = m.value
                val k = key(s)
                // Passage headers ("Source: S2 :: … page 70") are structure, never removed.
                val header = s.trimStart().let { t -> HEADER_PREFIXES.any { t.startsWith(it) } }
                if (!header && k.length >= MIN_KEY_CHARS && !seen.add(k)) return@forEach
                out.append(s)
            }
            out.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
        }
    }
}
