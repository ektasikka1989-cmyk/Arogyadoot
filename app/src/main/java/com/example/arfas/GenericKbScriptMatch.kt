package com.example.arfas

/**
 * 11 Nov 2026 (AQ): the multilingual re-ranker gives Hindi passages high scores for English questions they do not
 * answer (phone 22:50, "child of 3 years has cough for more than 2 weeks and weight loss": a Hindi page on feeding
 * small foods scored 3.98, above every TB passage, 2.1–2.9). For a question written only in Latin letters (English or
 * Hinglish), a passage written mostly in Devanagari has its re-ranker score lowered by [PENALTY]; a Hindi passage that
 * really answers still competes, it just needs a clearly better score. Devanagari questions are never changed (their
 * best passages are often English). Measured on the 233-question sets: 225 → 226 strict, no question lost; the TB
 * question then gets three TB passages in its first four.
 * Pure Kotlin, host-tested.
 */
object GenericKbScriptMatch {
    const val PENALTY = 3.0f

    /** Share of letters that are Devanagari (0 when there are no letters). */
    fun devanagariShare(text: String): Double {
        var letters = 0
        var deva = 0
        for (c in text) if (c.isLetter()) { letters++; if (c in 'ऀ'..'ॿ') deva++ }
        return if (letters == 0) 0.0 else deva.toDouble() / letters
    }

    /** A question with any Devanagari is a Hindi question (its search question may add an English rewrite). */
    private fun hindiQuestion(question: String): Boolean = question.any { it in '\u0900'..'\u097F' }

    fun penalty(question: String, passage: String): Float =
        if (!hindiQuestion(question) && devanagariShare(passage) > 0.5) PENALTY else 0f

    /**
     * What the re-ranker reads as the question. For a Hindi question that also carries its English rewrite (the
     * search question is "rewrite + own words"), only the English part: the re-ranker scored Hindi pages on songs and
     * stories above the pregnancy danger signs for "पैरों में सूजन और सिरदर्द" when it read the Hindi words too.
     * Unchanged when the English part is short (no rewrite) or there is no Devanagari. Measured: 226/233 either way
     * (33 Hindi questions), the swelling + headache question loses its unrelated Hindi pages.
     */
    fun rerankQuestion(question: String): String {
        if (!hindiQuestion(question)) return question
        val english = question.split(Regex("\\s+")).filter { w -> w.none { it in '\u0900'..'\u097F' } }.joinToString(" ").trim()
        return if (english.count { it.isLetter() } >= MIN_ENGLISH_LETTERS) english else question
    }
    private const val MIN_ENGLISH_LETTERS = 20

    /** [scores] (one per passage) with the script penalty applied; the same array when nothing changes. */
    fun adjust(question: String, passages: List<String>, scores: FloatArray?): FloatArray? {
        if (scores == null || scores.size != passages.size || hindiQuestion(question)) return scores
        return FloatArray(scores.size) { i -> scores[i] - penalty(question, passages[i]) }
    }
}
