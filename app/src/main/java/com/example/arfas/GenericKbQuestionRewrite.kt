package com.example.arfas

import java.util.Locale

/**
 * The answer model turns the user's question into (1) one self-contained search question in
 * the documents' own terms and (2) an arithmetic expression when the question gives amounts.
 *
 * "i am going to procure 20 acs at price of 42000 each what should be the epbg" →
 *   SEARCH: How much performance security (ePBG) should be taken for a contract?
 *   CALC: 20*42000
 *
 * The literal question is still searched; the rewrite is one more view, and the re-ranker reads
 * "rewrite + literal". The model never does the arithmetic (KbCalculator does). The prompt's
 * examples are deliberately from no particular KB, and the KB's own abbreviation long forms
 * (GenericKbAliasIndex) are given as hints so a small model can name the right term.
 */
data class GenericKbQuestionRewrite(
    val search: String,
    val calc: String?,
    /** One search question per thing asked, when the question asks two different things. */
    val parts: List<String> = listOf(search),
    /** 9 Nov: the model's own judgement, asked only when an earlier question was given: true = continues it, false = new topic. */
    val followsEarlier: Boolean? = null,
    /**
     * 11 Nov 2026 (AS): the name the documents would use for what the question describes without naming it (the
     * illness the signs point to, the rule or scheme a situation falls under), searched as one more view. Null
     * when the question already names it. Any knowledge base: the model names it from the question, no word list.
     */
    val topic: String? = null
) {
    companion object {
        const val MAX_OUTPUT_TOKENS = 120
        private const val MAX_PARTS = 3
        /** Long questions often ask two things at once; the model splits them into one search each. */
        private const val LONG_QUESTION_WORDS = 12

        /** Structural signals that a rewrite can help: amounts to work out, or a long (often two-part) question. */
        fun worthRewriting(question: String): Boolean =
            mayNeedCalculation(question) || question.trim().split(Regex("\\s+")).size >= LONG_QUESTION_WORDS ||
                question.count { it == '?' } >= 2

        /**
         * Rewrite before searching: long or two-part questions, amounts, and questions with words the pack
         * never uses (Hindi words such as "kitna", "kab", "bukhar"; misspellings).
         */
        fun worthRewritingForSearch(question: String, unknownWords: Int): Boolean =
            // Short questions in the pack's own words ("what is gem", "bid security") are searched as
            // typed: rewriting them first cost one extra model call (2-3 s on the phone) for no measured
            // gain; when their own words find nothing convincing, the checked fallback rewrite still runs.
            worthRewriting(question) || unknownWords > 0

        /** Two or more numbers in the question: an amount may have to be worked out. */
        fun mayNeedCalculation(question: String): Boolean =
            Regex("\\d[\\d,]*(?:\\.\\d+)?").findAll(question).count() >= 2

        /**
         * [previous]: the earlier question of this conversation (blank for the first question). 9 Nov: every
         * question is rewritten first, so a follow-up ("aur kitne din dena hai?") must be rewritten as the
         * complete question it means, or its search line ("For how many days should it be given?") finds
         * any topic's "how many days" passage.
         */
        fun prompt(question: String, aliasHints: List<String>, previous: String = ""): String = buildString {
            appendLine("Rewrite the question for a document search. Answer with SEARCH and CALC lines only:")
            appendLine("The question may be very short, misspelled or mixed Hindi and English: understand what the user wants and write it in clear English.")
            appendLine("SEARCH: one short, complete question in formal document language (no numbers from the user's own case)")
            appendLine("If the question asks two different things, write two SEARCH lines, one for each thing.")
            appendLine("Keep the question's key words (for example limit, dose, value, age) and never add details the user did not ask about.")
            appendLine("Keep what the user asks for: 'what should I do' stays a question about what to do (not about what to give); 'how much' stays a question about an amount.")
            // 9 Nov 2026 (AE, phone log): "महिला को पेशाब में जलन और बुखार है" (burning urination) was rewritten as
            // burning "in the vulva". Hindi symptom and body words must keep their exact meaning.
            appendLine("Hindi or Hinglish words: translate each one by its exact meaning; never change the body part, the symptom or the person (woman, child, newborn).")
            appendLine("TOPIC: the name these documents would use for what the question describes but does not name (for example the illness its signs point to, or the rule or scheme the situation falls under), in two to five words; NONE when the question already names it")
            appendLine("CALC: the arithmetic for the amount the user describes, using only + - * / and numbers, or NONE")
            if (aliasHints.isNotEmpty()) appendLine("Terms used in these documents: " + aliasHints.joinToString("; "))
            // 11 Nov 2026 (AQ, phone 22:54): "पैरों में सूजन और सिरदर्द" was rewritten as "bleeding and nausea"; the known
            // meanings of its Hindi words now go with it ("सूजन = swelling oedema").
            (CoreHealthFacts.hinglishHints(question) + runCatching { ArogyaHindiTerms.glosses(question) }.getOrDefault(emptyList()))
                .distinct().takeIf { it.isNotEmpty() }?.let { appendLine("Hindi words in this question: " + it.joinToString("; ")) }
            appendLine()
            // 8 Nov 2026: ASHA examples that keep the intent. The old ones came from another knowledge
            // base (room rent, office leave), and "bachche ko bukhar me kya dena chahiye -> What should be
            // given to a child with fever?" was copied for every fever question, pulling dose passages.
            appendLine("Question: child weighs 8 kg, paracetamol 15 mg per kg, how much to give")
            appendLine("SEARCH: What is the paracetamol dose for a child by body weight?")
            appendLine("TOPIC: NONE")
            appendLine("CALC: 8*15")
            appendLine()
            appendLine("Question: bachche ko tez bukhar hai kya karein")
            appendLine("SEARCH: What should an ASHA do when a child has high fever?")
            appendLine("TOPIC: NONE")
            appendLine("CALC: NONE")
            appendLine()
            appendLine("Question: pregnent lady bleding wat to do")
            appendLine("SEARCH: What should an ASHA do when a pregnant woman is bleeding?")
            appendLine("TOPIC: bleeding in pregnancy")
            appendLine("CALC: NONE")
            appendLine()
            appendLine("Question: aadmi ko teen hafte se khansi hai aur vajan ghat raha hai")
            appendLine("SEARCH: What should an ASHA do when a man has cough for three weeks and is losing weight?")
            appendLine("TOPIC: tuberculosis")
            appendLine("CALC: NONE")
            appendLine()
            appendLine("Question: ors kaise banaye")
            appendLine("SEARCH: How is ORS prepared at home?")
            appendLine("TOPIC: NONE")
            appendLine("CALC: NONE")
            appendLine()
            appendLine("Question: ifa goli kab leni hai aur kitne din tak")
            appendLine("SEARCH: When should IFA tablets be taken in pregnancy?")
            appendLine("SEARCH: For how many days should IFA tablets be taken?")
            appendLine("TOPIC: NONE")
            appendLine("CALC: NONE")
            appendLine()
            if (previous.isNotBlank()) {
                appendLine("An earlier question is shown. First write FOLLOWS: YES if the last question is incomplete without the earlier one (for example 'and the dose?', 'aur kitne din?', 'what if she also has fever?'), or FOLLOWS: NO if it names its own problem or person (a new question).")
                appendLine("FOLLOWS: YES → write the complete question it means, naming what the earlier question was about. FOLLOWS: NO → rewrite it alone and never add anything from the earlier question.")
                appendLine("Earlier question: zinc ki dose 4 mahine ke bacche ke liye")
                appendLine("Question: aur kitne din tak dena hai")
                appendLine("FOLLOWS: YES")
                appendLine("SEARCH: For how many days should zinc be given to a young child?")
                appendLine("CALC: NONE")
                appendLine()
                appendLine("Earlier question: bacche ko dast aur bukhar hai")
                appendLine("Question: navjaat ko pehla snan kab dena chahiye")
                appendLine("FOLLOWS: NO")
                appendLine("SEARCH: When should a newborn be given the first bath?")
                appendLine("CALC: NONE")
                appendLine()
                append("Earlier question: ").appendLine(previous.trim().replace(Regex("\\s+"), " ").take(300))
            }
            append("Question: ").appendLine(question.trim().replace(Regex("\\s+"), " ").take(400))
            // Without this the 3B model often rewrote the examples again instead of the user's
            // question (every Hinglish question in the ASHA test came back with no rewrite).
            append(if (previous.isNotBlank()) "Write only the FOLLOWS, SEARCH, TOPIC and CALC lines for this last question." else "Write only the SEARCH, TOPIC and CALC lines for this last question.")
        }

        /** Parses the model output; null when no usable SEARCH line. */
        fun parse(output: String, question: String, previous: String = ""): GenericKbQuestionRewrite? {
            // 9 Nov 2026 (phone log): Gemma sometimes types "\n" as two characters instead of a line break, which glued
            // "CALC: NONE" onto the search question.
            val all = output.replace("\\n", "\n").lines().map { it.trim().trim('*', '`', ' ') }
            // When the model repeats the examples, its rewrite of the user's question follows the
            // line that repeats that question: read only what comes after it.
            val q = question.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            // 12 Nov 2026 (AV, phone run): only an echo line counts, never an answer line. "SEARCH: What should an ASHA do when
            // a pregnant woman looks pale and gets breathless while working?" contains the question itself, was taken for
            // the echo, and the rewrite (3 of 19 questions) was thrown away.
            val echo = all.indexOfLast { l -> !ANSWER_LINE.containsMatchIn(l) && l.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").let { it.contains(q.take(60)) && q.length >= 8 } }
            val lines = if (echo >= 0 && echo < all.size - 1) all.drop(echo + 1) else all
            val searches = lines.mapNotNull { l ->
                Regex("^search\\s*\\d*\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)?.trim()
            }.map { it.trim('"', '\'', ' ').take(240) }.filter { it.length >= 8 && it.any(Char::isLetter) }
                .distinctBy { it.lowercase(Locale.ROOT) }.take(MAX_PARTS)
            // A small model that cannot work out the question copies an example ("What advance payment
            // is required for a booking?" for a question about zinc): such lines are not rewrites.
            .filterNot { copiesExample(it, question, previous) }
            val search = searches.firstOrNull() ?: return null
            val calcRaw = lines.firstNotNullOfOrNull { l ->
                Regex("^calc\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)?.trim()
            }
            // The expression may use only numbers the user actually wrote (no invented quantities).
            val calc = calcRaw?.takeUnless { it.uppercase(Locale.ROOT).startsWith("NONE") }
                ?.takeIf { KbCalculator.evaluate(it) != null && numbersFromQuestion(it, question) }
            val follows = if (previous.isBlank()) null else lines.firstNotNullOfOrNull { l ->
                Regex("^follows\\s*[:\\-]\\s*(yes|no)\\b", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
            }?.let { it == "yes" }
            val topic = lines.firstNotNullOfOrNull { l ->
                Regex("^topic\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)?.trim()
            }?.let { usableTopic(it, question, search, previous) }
            return GenericKbQuestionRewrite(search, calc, searches, follows, topic)
        }

        private val ANSWER_LINE = Regex("^(?:search|topic|calc|follows)\\s*\\d*\\s*[:\\-]", RegexOption.IGNORE_CASE)

        /** Topics written in the prompt's examples: kept only when the question itself is about them. */
        private val EXAMPLE_TOPICS = listOf("bleeding in pregnancy", "tuberculosis")
        /** The signs that make each example's topic right (word starts), required in the user's search line or question. */
        // 12 Nov 2026 (AV): one sign is enough (phone: "tuberculosis" for cough with blood in sputum was dropped because
        // the question did not also say "weight").
        private val EXAMPLE_TOPIC_SIGNS = listOf(listOf("bleed"), listOf("cough"))

        /**
         * 11 Nov 2026 (AS): the model's TOPIC line, or null when it is NONE, too long to be a name, only repeats words
         * the question or search line already has, or is an example's topic the question shares no word with.
         */
        internal fun usableTopic(raw: String, question: String, search: String, previous: String = ""): String? {
            val t = raw.trim().trim('"', '\'', '.', ' ').replace(Regex("\\s+"), " ")
            if (t.isEmpty() || t.uppercase(Locale.ROOT).startsWith("NONE") || t.length > 60 || !t.any(Char::isLetter)) return null
            if (t.split(' ').size > 6) return null
            // Short names count too ("TB", "PPH").
            val words = contentWords(t).ifEmpty { t.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 2 }.toSet() }
            if (words.isEmpty()) return null
            // Adds nothing: every word is already in the question or its search line.
            val said = contentWords(question) + contentWords(search)
            if (words.all { w -> said.any { it == w || it.startsWith(w.take(5)) || w.startsWith(it.take(5)) } }) return null
            // A copied example ("tuberculosis" for a fever question): kept only when the example's own signs are in the
            // user's search line or question (cough and weight for the tuberculosis example).
            val example = EXAMPLE_TOPICS.indexOfFirst { it.equals(t, ignoreCase = true) }
            if (example >= 0) {
                val text = (search + " " + question).lowercase(Locale.ROOT)
                if (!EXAMPLE_TOPIC_SIGNS[example].all { it in text }) return null
            }
            return t
        }

        private val EXAMPLE_SEARCHES = listOf(
            "What advance payment is required for a booking?",
            "How much rent must be paid for a room?",
            "What should be given to a child with fever?",
            "When can leave be granted, and under what conditions?",
            "Who is the competent authority to sanction leave?",
            "What is the maximum period of leave that can be sanctioned at one time?",
            "For how many days should zinc be given to a young child?",
            "When should a newborn be given the first bath?"
        )

        private fun contentWords(t: String): Set<String> =
            t.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{M}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }.toSet()

        private val STOP = setOf(
            "what", "which", "who", "whom", "when", "where", "why", "how", "the", "and", "for", "are", "can", "does", "should",
            "must", "this", "that", "with", "from", "into", "about", "under", "kya", "hai", "hain", "kaise", "kitna", "kitne",
            "kab", "kaun", "kis", "kya", "karna", "chahiye", "dena", "milti", "milta", "hota", "hoti", "than", "there", "their"
        )

        /**
         * True when [search] is (nearly) one of the prompt's examples, or shares no content word with
         * a Latin-script question (a Devanagari question is checked against the examples only).
         */
        internal fun copiesExample(search: String, question: String, previous: String = ""): Boolean {
            val s = contentWords(search)
            // A follow-up's rewrite names the earlier question's subject ("how long?" → "... zinc ..."): its words count.
            // 12 Nov 2026 (AZ, laptop scorecard): 17 of 273 correct rewrites were thrown away as "copied examples" — all
            // Hindi/Hinglish questions about a child with fever, cough or diarrhoea ("bacche ko dast aur bukhar hai" →
            // "What should an ASHA do when a child has diarrhoea and fever?"), because the English words of the rewrite
            // could not be traced to the Hindi words of the question. The known meanings of the question's Hindi words
            // (the same dictionaries the search uses) now count as the user's own words.
            val translated = runCatching {
                (CoreHealthFacts.hinglishHints(question) + ArogyaHindiTerms.glosses(question) + ArogyaHindiTerms.views(question))
                    .flatMap { h -> contentWords(h.substringAfter(" = ", h)) }
            }.getOrDefault(emptyList())
            val q = contentWords(question) + contentWords(previous) + translated
            fun fromQuestion(word: String) = q.any { qw -> qw == word || word.startsWith(qw.take(5)) || qw.startsWith(word.take(5)) }
            // Close to an example and adding nothing the user said: a copy.
            val example = EXAMPLE_SEARCHES.any { e ->
                val w = contentWords(e)
                w.isNotEmpty() && (s intersect w).size * 3 >= w.size * 2 && (s - w).none(::fromQuestion) && w.none(::fromQuestion)
            }
            if (example) return true
            // A Hinglish question ("navjaat shishu ko pehla doodh kab pilana chahiye") shares no words
            // with its correct English rewrite, so the shared-word test is for English questions only.
            val latin = question.count { it in 'a'..'z' || it in 'A'..'Z' } > question.count { it.code in 0x0900..0x097F }
            return latin && !CoreHealthFacts.isHinglish(question) && q.isNotEmpty() && s.none(::fromQuestion)
        }

        private fun numbers(text: String): List<String> =
            Regex("\\d[\\d,]*(?:\\.\\d+)?").findAll(text).map { java.math.BigDecimal(it.value.replace(",", "")).stripTrailingZeros().toPlainString() }.toList()

        internal fun numbersFromQuestion(expression: String, question: String): Boolean {
            val allowed = numbers(question).toSet()
            val used = numbers(expression)
            return used.isNotEmpty() && used.all { it in allowed || it == "100" }
        }
    }
}
