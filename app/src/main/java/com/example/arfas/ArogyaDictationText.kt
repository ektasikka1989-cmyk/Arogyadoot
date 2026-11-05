package com.example.arfas

/**
 * Pure cursor-insert helpers for inline dictation (host-tested). No Android or Compose types:
 * callers convert to/from TextFieldValue. Dictated text is inserted at the cursor (or over the
 * selection) with spacing so words never glue; existing content is always preserved.
 */
object ArogyaDictationText {
    /** Result of an insert: the new text and the cursor position (collapsed selection). */
    data class Edit(val text: String, val selStart: Int, val selEnd: Int)

    /**
     * Insert [dictated] into [text] at [selStart, selEnd). Blank dictation leaves the text
     * untouched. The cursor lands right after the inserted words.
     */
    fun insertAtCursor(text: String, selStart: Int, selEnd: Int, dictated: String): Edit {
        val d = dictated.trim()
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
