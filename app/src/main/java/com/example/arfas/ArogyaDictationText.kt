package com.example.arfas

/**
 * Pure text helpers for inline dictation (host-tested, no Android or Compose types).
 * Dictated words are inserted at the cursor (or over the selection) with spacing so words never
 * glue together; existing text is always kept.
 */
object ArogyaDictationText {
    /** Result of an insert: the new text and the cursor position (collapsed selection). */
    data class Edit(val text: String, val selStart: Int, val selEnd: Int)

    // Whisper writes non-speech as bracketed tags: [BLANK_AUDIO], [Music], (coughing), *laughs* …
    private val nonSpeech = Regex("""\[[^\]]{0,40}]|\((?:[^)]{0,40})\)|\*[^*]{0,40}\*""")
    private val spaces = Regex("""\s+""")

    /** Removes Whisper non-speech tags and collapses whitespace. Blank when nothing was said. */
    fun clean(raw: String): String = raw.replace(nonSpeech, " ").replace(spaces, " ").trim()

    /**
     * Insert [dictated] into [text] at [selStart, selEnd). Blank (or tag-only) dictation leaves
     * the text untouched. The cursor lands right after the inserted words.
     */
    fun insertAtCursor(text: String, selStart: Int, selEnd: Int, dictated: String): Edit {
        val d = clean(dictated)
        val a = selStart.coerceIn(0, text.length)
        val b = selEnd.coerceIn(a, text.length)
        if (d.isEmpty()) return Edit(text, a, b)
        val before = text.substring(0, a)
        val after = text.substring(b)
        val spaceBefore = before.isNotEmpty() && !before.last().isWhitespace()
        val spaceAfter = after.isNotEmpty() && !after.first().isWhitespace()
        val ins = (if (spaceBefore) " " else "") + d + (if (spaceAfter) " " else "")
        val out = before + ins + after
        val cur = (before.length + ins.length).coerceIn(0, out.length)
        return Edit(out, cur, cur)
    }
}
