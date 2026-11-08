package com.example.arfas

/**
 * Auto language choice for dictation (pure, host-tested).
 *
 * ASHAs speak Hindi, English or Hinglish, whatever the app language is, so the mic always
 * auto-detects. Plain Whisper auto-detect picks from ~99 languages, and spoken Hindi and Urdu
 * sound almost the same, so short or noisy Hindi clips were often written in Urdu (Arabic)
 * script. Here the choice is narrowed to Hindi or English:
 *  - Hindi and Urdu probabilities count together as "Hindi" (we always write Devanagari).
 *  - English is chosen only when it clearly dominates: decoding Hindi or Hinglish speech as
 *    English can translate the words and change their meaning, while decoding English words
 *    as Hindi only spells them in Devanagari (meaning kept).
 *  - If the decoded text still contains Arabic script, it is decoded again as Hindi.
 */
object ArogyaSpeechLang {
    const val HINDI = "hi"
    const val ENGLISH = "en"

    /** English needs at least this share of (Hindi + Urdu + English). */
    const val ENGLISH_SHARE = 0.70f

    data class Probs(val hi: Float, val ur: Float, val en: Float)

    /** Parses the native detector line, e.g. "hi=0.412 ur=0.388 en=0.120 top=hi". Null when unusable. */
    fun parse(line: String?): Probs? {
        if (line.isNullOrBlank()) return null
        fun v(k: String) = Regex("""\b$k=([0-9.]+)""").find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val hi = v("hi") ?: return null
        val ur = v("ur") ?: return null
        val en = v("en") ?: return null
        if (hi + ur + en <= 0f) return null
        return Probs(hi, ur, en)
    }

    /** Hindi unless English clearly dominates. Unknown → Hindi (safe: never translates). */
    fun choose(p: Probs?): String {
        if (p == null) return HINDI
        val total = p.hi + p.ur + p.en
        if (total <= 0f) return HINDI
        return if (p.en / total >= ENGLISH_SHARE) ENGLISH else HINDI
    }

    /** True when [text] holds Arabic/Urdu script letters. */
    fun hasArabicScript(text: String): Boolean = text.any { c ->
        val x = c.code
        x in 0x0600..0x06FF || x in 0x0750..0x077F || x in 0x08A0..0x08FF ||
            x in 0xFB50..0xFDFF || x in 0xFE70..0xFEFF
    }
}
