package com.example.arfas

/**
 * 12 Nov 2026 (AW): pick, then write. On the phone's answer-quality run the wrong sentences came from passages about
 * another problem that sat next to the right one (an anaemia-guideline page beside the newborn danger signs; sepsis
 * text beside jaundice; a pneumonia page beside TB) — the small model, told to combine every passage, blended them.
 * Choosing is easier for a small model than writing from a mix, so the phone model is first asked only which passages
 * describe this person's problem (a short call: numbers out), and the answer is then written from those passages.
 * Any knowledge base: the question is the only criterion. An unreadable reply, "0", or a choice of every passage
 * leaves the evidence as it was. Pure Kotlin, host-tested.
 */
object ArogyaPassagePick {
    /** Characters of each passage shown for choosing (its opening is where its topic is named). */
    const val SNIPPET_CHARS = 420
    const val MAX_OUTPUT_TOKENS = 16
    /** At most this many passages are kept (best first, as the model listed them). */
    const val MAX_KEPT = 3

    fun prompt(question: String, passages: List<String>): String = buildString {
        appendLine("Question: ${question.trim().replace(Regex("\\s+"), " ").take(400)}")
        appendLine()
        appendLine("Passages:")
        passages.forEachIndexed { i, p ->
            appendLine("[${i + 1}] ${snippet(p)}")
        }
        appendLine()
        appendLine("Which passages are about the same problem, person and situation as the question? " +
            "A passage that only shares a word (such as fever or cord) but is about something else does not count.")
        append("Write only their numbers, best first, like: 2,1. If none of them is, write: 0")
    }

    /** The passage's text without the "Source ID:" / "Source:" header lines, cut to [SNIPPET_CHARS] at a word. */
    fun snippet(passage: String): String {
        val body = passage.lines().filterNot { it.startsWith("Source ID:") || it.startsWith("Source:") || it.startsWith("Note:") }
            .joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (body.length <= SNIPPET_CHARS) return body
        val cut = body.lastIndexOf(' ', SNIPPET_CHARS).takeIf { it > SNIPPET_CHARS / 2 } ?: SNIPPET_CHARS
        return body.substring(0, cut) + " …"
    }

    /**
     * 12 Nov 2026 (AX, phone run 3): the model alone twice chose a confident wrong passage (an acute-fever emergency page
     * for fever with shivering every alternate day; an epilepsy page for fits in pregnancy) and the best-ranked passage
     * (malaria; high blood pressure in pregnancy) was dropped. The first passage — the search's best — is always kept,
     * then the model's choices; null (keep all) when that would be every passage.
     */
    fun withBest(picked: List<Int>?, count: Int): List<Int>? {
        if (picked == null || count <= 1) return null
        val keep = (listOf(0) + picked).distinct().take(MAX_KEPT)
        return if (keep.size >= count) null else keep
    }

    /**
     * Passage indexes (0-based, best first) from the model's reply, or null when the evidence should stay as it was:
     * no readable number, "0" (none fits), or every passage chosen.
     */
    fun parse(reply: String?, count: Int): List<Int>? {
        if (reply.isNullOrBlank() || count <= 1) return null
        val firstLine = reply.trim().lines().firstOrNull { it.any(Char::isDigit) } ?: return null
        val nums = Regex("\\d{1,2}").findAll(firstLine).map { it.value.toInt() }.toList()
        if (nums.isEmpty() || nums.first() == 0) return null
        val picked = nums.filter { it in 1..count }.map { it - 1 }.distinct().take(MAX_KEPT)
        if (picked.isEmpty() || picked.size >= count) return null
        return picked
    }
}
