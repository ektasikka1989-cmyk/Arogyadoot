package com.example.arfas

/**
 * Not-in-the-guide answers (8 Nov 2026). When the ASHA guides do not cover a question, ArogyaDoot
 * still shows an answer when one exists (the phone's general answer, or a web answer when online),
 * but under a clear label that it is NOT from the guides, with "Check online" recommended (the
 * answer card highlights that button) and "ask your ANM". Without an answer the ANM message stays.
 * Pure Kotlin, host-tested (tools/host-tests/NotFromGuideTest.kt).
 */
object ArogyaNotFromGuide {
    /** Labels the runtime puts before a general answer (AndroBaselineRuntime.kbGeneralAnswer). */
    private val GENERAL_MARKERS = listOf("General answer (not from its documents):", "सामान्य उत्तर (इसके दस्तावेज़ों से नहीं):")
    /** Texts that are only a "nothing found / unavailable" message, never an answer. */
    private val NO_ANSWER = listOf(
        "couldn't find enough relevant information", "could not be reached", "does not answer this",
        "not covered by the selected knowledge base", "try again in a moment"
    )

    /**
     * The answer part of a not-in-guide turn, or null when there is no real answer.
     * [web]: the turn is a live web answer (any substantial text counts); otherwise only a
     * general answer (after its label) counts.
     */
    fun answerBody(text: String, web: Boolean): String? {
        val t = text.trim()
        if (t.isBlank()) return null
        for (m in GENERAL_MARKERS) {
            val i = t.indexOf(m)
            if (i >= 0) return t.substring(i + m.length).trim().takeIf { it.length >= MIN_ANSWER_CHARS }
        }
        if (!web) return null
        if (NO_ANSWER.any { t.contains(it, ignoreCase = true) } && t.length < 300) return null
        return t.takeIf { it.length >= MIN_ANSWER_CHARS }
    }

    /** [body] under the "not from your ASHA guides" label, then the closest guide topics. */
    fun labelled(body: String, topics: List<String>, web: Boolean, hindi: Boolean): String = buildString {
        if (hindi) {
            append("⚠ यह आपकी ASHA गाइड से नहीं है। ")
            append(if (web) "यह वेब खोज से है" else "यह AI की सामान्य जानकारी है")
            append(", सरकारी दिशानिर्देशों से जाँची नहीं गई। पुष्टि के लिए ☁ ऑनलाइन जाँचें दबाएँ, या कुछ करने से पहले अपनी ANM से पूछें।")
        } else {
            append("⚠ Not from your ASHA guides. ")
            append(if (web) "This comes from a web search" else "This is general information from the AI")
            append(", not checked against official guidance. Tap ☁ Check online to verify, or ask your ANM before acting.")
        }
        append("\n\n").append(body.trim())
        if (topics.isNotEmpty()) {
            append(if (hindi) "\n\nगाइड में सबसे करीबी विषय: " else "\n\nClosest guide topics: ")
            append(topics.take(5).joinToString("; "))
        }
    }

    private const val MIN_ANSWER_CHARS = 20
}
