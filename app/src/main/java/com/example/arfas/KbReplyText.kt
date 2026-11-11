package com.example.arfas

/**
 * Deterministic, language-list-free helpers for selected-KB answers:
 * - the reply script is taken from the characters of the question (Unicode script), not guessed
 *   by the model: typos in an English question must not produce a Hindi (Devanagari) answer;
 * - a degenerate repetition at the end of a generation ("है है है …") is cut off.
 */
object KbReplyText {
    enum class Script { LATIN, DEVANAGARI, OTHER, NONE }

    /**
     * Sentence boundaries (and line breaks) for checking answers: never after "Rs.", "No.", "Tab.",
     * "e.g." and similar, and never before a number, so "Rs. 1,75,000" is not cut into "… Rs." and
     * "1,75,000 …" (a salvage once left "A PHC level AB-HWC gets Rs.").
     */
    val SENTENCE_SPLIT = Regex(
        "(?<!\\b(?:Rs|No|Nos|Dr|Tab|Cap|Inj|Syp|Mr|Mrs|Ms|Smt|Sh|Shri|St|Govt|Dept|viz|vs|approx|Para|para|Art|Sec|Fig|Vol|Ch|Sl|S\\.No|e\\.g|i\\.e)\\.)" +
            "(?<=[.!?\u0964])\\s+(?!\\d)|\\n+"
    )

    /** Share of letters in each script; the majority script of the letters decides. */
    fun dominantScript(text: String): Script {
        var latin = 0; var deva = 0; var other = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp)) when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.LATIN -> latin++
                Character.UnicodeScript.DEVANAGARI -> deva++
                else -> other++
            }
            i += Character.charCount(cp)
        }
        val total = latin + deva + other
        // Words decide a mixed question: "SHC स्तर के AB-HWC को कितना untied fund मिलता है?" is Hindi
        // with English terms (6 Hindi words, 4 English) though English letters are the majority.
        val devaWords = Regex("[\\u0900-\\u097F]+").findAll(text).count { m -> m.value.any { Character.isLetter(it) } }
        val latinWords = Regex("[A-Za-z]+").findAll(text).count()
        return when {
            total == 0 -> Script.NONE
            devaWords >= 3 && devaWords >= latinWords -> Script.DEVANAGARI
            deva * 2 >= total -> Script.DEVANAGARI
            latin * 2 >= total -> Script.LATIN
            else -> Script.OTHER
        }
    }

    /** Share (0..1) of an answer's letters that are Devanagari. */
    fun devanagariShare(text: String): Double {
        var letters = 0; var deva = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp)) {
                letters++
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.DEVANAGARI) deva++
            }
            i += Character.charCount(cp)
        }
        return if (letters == 0) 0.0 else deva.toDouble() / letters
    }

    /** Reply-script instruction for the answer model, from the question's own characters. */
    fun scriptInstruction(query: String): String = when (dominantScript(query)) {
        // Never say "Latin" here: Llama 3.2 3B read "write in Latin letters" as "write in Latin" and
        // answered in the Latin language.
        // Hinglish questions are understood but answered in simple English: Llama 3.2 3B wrote broken
        // Hinglish ("20 mg tak tablet ka") and once reversed a limit ("se adhik" for "up to").
        Script.LATIN -> "Reply language: the question is typed in the English alphabet (A-Z). It may be Hinglish " +
            "(Hindi typed in A-Z): understand it, and always answer in clear, simple English. Do not use Devanagari script. " +
            "Keep every limit exactly as the evidence states it (up to, not exceeding, more than, at least)."
        Script.DEVANAGARI -> "Reply script: the question is written in Devanagari, so write the answer in Hindi in " +
            "Devanagari script (technical terms from the evidence may stay in English)."
        else -> "Reply script: write the answer in the same language and script as the question."
    }

    /**
     * True when the answer is in the wrong script for the question: a Latin-letter question answered
     * mostly in Devanagari, or a Devanagari question answered with almost no Devanagari.
     */
    private val ENGLISH_FUNCTION_WORDS = setOf(
        "the", "is", "are", "was", "and", "of", "to", "for", "a", "an", "with", "should", "be", "it", "this",
        "what", "how", "when", "who", "which", "do", "does", "can", "on", "by", "or", "if", "her", "she", "they"
    )

    /**
     * An English question answered in another Latin-alphabet language (Llama 3.2 3B once answered
     * in Latin): the answer has 20+ words but almost none of English's most common words.
     */
    fun wrongLanguageForEnglish(query: String, answer: String): Boolean {
        fun words(t: String) = Regex("[A-Za-z]+").findAll(t.lowercase()).map { it.value }.toList()
        val q = words(query)
        if (dominantScript(query) != Script.LATIN || q.count { it in ENGLISH_FUNCTION_WORDS } < 1) return false
        val a = words(answer.replace(Regex("\\[[^\\]]*]"), " "))
        if (a.size < 20) return false
        return a.count { it in ENGLISH_FUNCTION_WORDS }.toDouble() / a.size < 0.04
    }

    /**
     * Citation forms a 3B model writes instead of [S1]: "(S1)", "(S1, S2)", "(Source: S1)",
     * "[Source S1]", "Source S1", "S1:" at a line end. All become [S1] so the app can show the
     * passages. Bare "S1" inside a sentence is left alone (it may be real text).
     */
    fun normalizeCitations(text: String): String {
        if (!Regex("S\\d").containsMatchIn(text)) return text
        var out = text
        val list = "S\\d{1,2}(?:\\s*(?:,|;|and|&)\\s*S\\d{1,2})*"
        out = Regex("[(\\[【]\\s*(?:sources?|src)?\\s*:?\\s*($list)\\s*[)\\]】]", RegexOption.IGNORE_CASE)
            .replace(out) { m -> "[" + Regex("S\\d{1,2}").findAll(m.groupValues[1]).joinToString(", ") { it.value } + "]" }
        out = Regex("\\b(?:sources?|src)\\s*:?\\s*($list)\\b", RegexOption.IGNORE_CASE)
            .replace(out) { m -> "[" + Regex("S\\d{1,2}").findAll(m.groupValues[1]).joinToString(", ") { it.value } + "]" }
        return out
    }

    fun scriptMismatch(query: String, answer: String): Boolean = when (dominantScript(query)) {
        Script.LATIN -> devanagariShare(answer) > 0.3
        Script.DEVANAGARI -> devanagariShare(answer) < 0.2
        else -> false
    }

    /**
     * Cuts a degenerate repetition at the end of [text]: the same unit of 1..[MAX_UNIT_WORDS] words
     * repeated at least [MIN_REPEATS] times in a row is reduced to its first occurrence, and the
     * text ends there. Text without such a tail is returned unchanged.
     */
    fun trimRepeatedTail(text: String): String {
        val words = Regex("\\S+").findAll(text).toList()
        if (words.size < MIN_REPEATS) return text
        var cutAt: Int? = null
        for (n in 1..MAX_UNIT_WORDS) {
            if (words.size < n * MIN_REPEATS) break
            fun unit(end: Int) = (end - n until end).joinToString(" ") { words[it].value }
            val last = unit(words.size)
            var copies = 1
            var end = words.size - n
            while (end - n >= 0 && unit(end) == last) { copies++; end -= n }
            if (copies >= MIN_REPEATS) {
                // Keep the first copy of the unit; drop the repeats after it.
                val keepEndWord = end + n - 1
                val candidate = words[keepEndWord].range.last + 1
                cutAt = minOf(cutAt ?: candidate, candidate)
            }
        }
        return cutAt?.let { text.substring(0, it).trimEnd() } ?: text
    }

    /**
     * Removes degenerate repetition that is not an exact tail loop: a sentence whose words are
     * almost all the same as an earlier sentence's (word-set overlap >= [NEAR_DUPLICATE]) is
     * dropped, and a word repeated back to back ("breakup breakup") is written once. Only when one sentence occurs
     * [MIN_LOOP_OCCURRENCES]+ times (a loop); sentences of fewer than [MIN_DEDUPE_WORDS] words are
     * always kept (list labels, short steps). Line structure
     * (numbered steps, bullets) is preserved.
     */
    /** Word-set overlap (of the longer sentence) at which a second sentence is the same sentence. */
    private const val SAME_SENTENCE = 0.9

    fun removeRepeatedSentences(text: String): String {
        val stutter = Regex("(?i)(?<![\\p{L}\\p{M}])(\\p{L}[\\p{L}\\p{M}]*)(?:\\s+\\1)+(?![\\p{L}\\p{M}])")
        val splitter = TRIM_SPLIT
        fun words(piece: String) = Regex("[\\p{L}\\p{N}]+").findAll(piece.lowercase()).map { it.value }.toSet()
        fun near(a: Set<String>, b: Set<String>) = a.intersect(b).size.toDouble() / minOf(a.size, b.size) >= NEAR_DUPLICATE
        val lines = text.split('\n').map { line -> splitter.split(stutter.replace(line) { it.groupValues[1] }) }
        // Only a degenerate loop repeats one sentence MIN_LOOP_OCCURRENCES or more times; similar
        // but legitimate sentences (two parallel steps) are left alone.
        val sets = lines.flatten().map(::words).filter { it.size >= MIN_DEDUPE_WORDS }
        // A loop = one sentence plus MIN_LOOP_OCCURRENCES more near-copies. A normal structured answer
        // restates its key action two or three times (answer, "What to do", summary); counting the
        // sentence itself made that a "loop" and cut a third of a good answer (8 Nov phone log).
        val looping = sets.any { a -> sets.count { b -> b !== a && near(a, b) } >= MIN_LOOP_OCCURRENCES }
        val kept = ArrayList<Set<String>>()
        val out = StringBuilder()
        for ((index, pieces) in lines.withIndex()) {
            // Without a loop, only a sentence that is (almost) word for word an earlier one is dropped:
            // the same rule copied from two editions of a document ("AoN would lapse …" twice).
            val keptPieces = pieces.filter { piece ->
                val w = words(piece)
                if (w.size < MIN_DEDUPE_WORDS) return@filter true
                // 8 Nov 2026: a numbered step restating the answer's key action ("2. Give the first dose …
                // and refer") is part of "What to do", not a duplicate (outside a real loop).
                if (!looping && STEP_START.containsMatchIn(piece)) return@filter true
                val duplicate = kept.any { k ->
                    if (looping) near(w, k)
                    else w.intersect(k).size.toDouble() / maxOf(w.size, k.size) >= SAME_SENTENCE
                }
                if (!duplicate) kept += w
                !duplicate
            }
            // A step number whose text was dropped ("What to do: 1.") is removed too.
            val joined = keptPieces.filterNot { ORPHAN_STEP.matches(it.trim()) }.joinToString(" ").trimEnd()
            if (joined.isNotBlank() || text.split('\n')[index].isBlank()) out.append(joined).append('\n')
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    /**
     * An answer that stopped at the output limit mid-sentence is cut back to its last complete
     * sentence or line, and a short note (in the answer's script) says there is more and how to get
     * it. [likelyAtLimit] comes from the caller (answer length close to the output budget); an answer
     * that ended normally, or that already ends with complete punctuation, is returned unchanged.
     */
    fun completeAtLimit(text: String, likelyAtLimit: Boolean): String {
        val t = text.trimEnd()
        if (!likelyAtLimit || t.isEmpty() || t.last() in TERMINAL) return text
        val lineStart = t.lastIndexOf('\n') + 1
        val lastLine = t.substring(lineStart)
        // Last real sentence end inside the unfinished line (not a step number such as "3.").
        val sentenceEnd = Regex("[.!?।](?=\\s)").findAll(lastLine)
            .map { it.range.first }
            .filter { pos -> !Regex("^\\s*[\\p{N}\\p{L}]{1,3}$").matches(lastLine.substring(0, pos)) }
            .lastOrNull()
        val cutAt = when {
            sentenceEnd != null -> lineStart + sentenceEnd + 1
            lineStart > 0 -> lineStart - 1
            else -> return text
        }
        // Keep at least half the answer; otherwise show it as it is rather than lose most of it.
        if (cutAt < t.length / 2) return text
        val kept = t.substring(0, cutAt).trimEnd()
        return kept + "\n\n" + if (devanagariShare(kept) > 0.3) MORE_HI else MORE_EN
    }

    private const val TERMINAL = ".!?।:)]\"'”"

    /**
     * Sentence boundary: after . ! ? । and whitespace, but not after a lone letter ("e. g.",
     * "i. e.", initials) — the guide writes "fever e. g. malaria", which was cut to "fever e.".
     */
    private val TRIM_SPLIT = Regex("(?<=[.!?।])(?<![\\s(]\\p{L}\\.)(?<!^\\p{L}\\.)(?<!(?:^|[\\s:])\\d\\.)\\s+")

    /**
     * A draft the decoder's loop guard stopped: the answer before the loop is usually good (it is
     * what the ASHA watched being written). Keeps whole sentences up to the loop; null when too
     * little is left to be an answer (then the caller regenerates as before).
     */
    fun keepBeforeLoop(text: String): String? {
        // Cut right after the first copy of the repeating unit (found anywhere; the decode may stop
        // in the middle of a copy, which an exact-tail match misses).
        val base = loopUnit(text)?.let { unit ->
            val words = unit.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val m = Regex(words.joinToString("\\s+") { Regex.escape(it) }).find(text)
            if (m != null) text.substring(0, m.range.last + 1) else null
        } ?: text
        var t = removeRepeatedSentences(trimRepeatedTail(base)).trim()
        if (t.isEmpty()) return null
        if (t.last() !in TERMINAL) {
            val end = Regex("[.!?।](?=\\s|$)").findAll(t).map { it.range.first }.lastOrNull() ?: return null
            t = t.substring(0, end + 1).trim()
        }
        // A citation left on its own after the cut ("… often [S2]. [S4].") is dropped.
        t = t.replace(Regex("(?<=[.!?।])\\s*\\[S?\\d+(?:\\s*,\\s*S?\\d+)*]\\.?$"), "").trim()
        val sentences = t.split(TRIM_SPLIT).count { Regex("[\\p{L}]{2,}").containsMatchIn(it) }
        if (t.length < MIN_KEPT_CHARS || sentences < MIN_KEPT_SENTENCES) return null
        if (degenerateRepetition(t) != null) return null
        return t
    }
    private const val MIN_KEPT_CHARS = 160
    /** A piece that is only a step number ("1."): its step text was dropped. Never a number ending a sentence. */
    private val ORPHAN_STEP = Regex("^\\d{1,2}[.)]$")

    /**
     * Safety: removes answer sentences that state, without any negation, an action the evidence
     * forbids ("Do not cover with a blanket" → answer "… and cover with a blanket"). A forbidden
     * action that the evidence also states positively somewhere (e.g. "cover the baby" for warmth)
     * is not used. Returns the text and the number of sentences removed. English evidence only.
     */
    fun dropFlippedProhibitions(answer: String, evidence: String): Pair<String, Int> {
        fun content(t: String) = Regex("[a-z]{3,}").findAll(t.lowercase()).map { it.value }.filter { it !in FLIP_STOP }.toSet()
        val evClauses = evidence.split(Regex("[.;!?\\n]|,\\s|\\s(?:and|or|but)\\s"))
        val forbidden = PROHIBIT.findAll(evidence).mapNotNull { m ->
            val action = m.groupValues[1].split(PROHIBIT_END).first()
            content(action).takeIf { it.size >= 2 }
        }.distinct().filter { words ->
            evClauses.none { c -> !NEGATION.containsMatchIn(c) && content(c).containsAll(words) }
        }.toList()
        if (forbidden.isEmpty()) return answer to 0
        var removed = 0
        // Lines under "Common mistakes to avoid" name the forbidden action on purpose: never removed.
        var inMistakes = false
        val out = answer.split('\n').joinToString("\n") { line ->
            val head = line.trim().lowercase().removePrefix("**")
            if (MISTAKES_LABEL.containsMatchIn(head)) inMistakes = true
            else if (OTHER_LABEL.containsMatchIn(head)) inMistakes = false
            if (inMistakes) return@joinToString line
            line.split(TRIM_SPLIT).mapNotNull { sentence ->
                fun isFlipped(clause: String) = !NEGATION.containsMatchIn(clause) && forbidden.any { content(clause).containsAll(it) }
                val parts = sentence.split(CLAUSE_SPLIT)
                if (parts.none { isFlipped(it) }) return@mapNotNull sentence
                removed++
                // 8 Nov 2026: drop only the flipped clause ("… with tepid water and cover with a blanket"
                // → "… with tepid water."); a section label at the start ("What to do: 1. …") is kept.
                // Each piece starts with its separator (", and cover …"); flipped pieces are dropped.
                val keptText = sentence.split(CLAUSE_SEP_KEEP).filter { it.isNotBlank() && !isFlipped(it) }
                    .joinToString("").trim().replace(Regex("^[,;\\s]*(?:(?:and|or|then)\\s+)?"), "")
                    .trim().trimEnd(',', ';').trim()
                val citeTail = Regex("\\s*(\\[[^\\]]*])\\s*[.!?।]?\\s*$").find(sentence)?.groupValues?.get(1)
                val words = Regex("\\p{L}{2,}").findAll(keptText.substringAfter(':', keptText)).count()
                if (words >= 3) {
                    var r = keptText.replace(Regex("\\s*\\[[^\\]]*]\\s*$"), "").trimEnd('.', ' ')
                    if (citeTail != null && !r.contains(citeTail)) r += " $citeTail"
                    "$r."
                } else LEADING_LABEL.find(sentence)?.value?.trim()
            }.joinToString(" ")
        }
        return (if (removed == 0) answer else out.replace(Regex("\n{3,}"), "\n\n").trim()) to removed
    }
    private val PROHIBIT = Regex("(?i)\\b(?:do not|don't|never|should not|must not|avoid)\\s+([^.;!?\\n]{3,80})")
    private val PROHIBIT_END = Regex("(?i)\\s(?:as|because|since|which|so that|till|until|unless|if|when|or else|even)\\s")
    private val NEGATION = Regex("(?i)\\b(?:no|not|never|don't|avoid|without|nahi|nahin|mat)\\b|नहीं|मत ")
    private val FLIP_STOP = setOf("the", "with", "and", "for", "any", "them", "this", "that", "from", "into", "your", "her", "his",
        "their", "its", "are", "was", "were", "has", "have", "had", "very", "more", "less", "too", "also", "only", "all", "should", "must",
        "can", "will", "child", "baby", "person", "patient", "mother", "woman")
    private const val MIN_KEPT_SENTENCES = 2
    private val STEP_START = Regex("^\\s*\\d{1,2}[.)]\\s")
    private val CLAUSE_SPLIT = Regex(",|;|\\s(?:and|or|then)\\s")
    /** Splits into clause, separator, clause, … (separators kept so the sentence can be rebuilt). */
    private val CLAUSE_SEP_KEEP = Regex("(?=[,;])|(?=\\s(?:and|or|then)\\s)")
    private val LEADING_LABEL = Regex("^\\s*(?:\\*\\*)?(?:answer|why it matters|what to do|when to tell (?:the )?anm|common mistakes(?: to avoid)?)\\s*:\\s*(?:\\d{1,2}[.)])?", RegexOption.IGNORE_CASE)
    private val MISTAKES_LABEL = Regex("^(?:common mistakes|mistakes to avoid|आम गलतियाँ|आम गलतिया)")
    private val OTHER_LABEL = Regex("^(?:answer|why it matters|what to do|when to tell|उत्तर|क्यों ज़रूरी|क्यों जरूरी|क्या करें|anm को कब)\\s*:")

    /**
     * A question part that only asks for action or advice ("what should I do", "क्या करें") and
     * names no topic. It is never "not covered" by the guide on its own.
     */
    fun isGenericAsk(part: String): Boolean {
        val t = part.lowercase().replace(Regex("[^\\p{L}\\p{M}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return true
        return GENERIC_ASK.matches(t)
    }
    private val GENERIC_ASK = Regex(
        "(?:(?:so |and |then |now )?(?:what|how)(?: should| can| do| must| shall| to)?(?: i| we| you| the asha| asha)?(?: do| treat| manage| handle| proceed| help)?(?: now| next| first| then)?(?: about it| about this| for it| for this)?)" +
        "|(?:(?:ab |to )?(?:kya|kaise) (?:karein|karen|kare|karna|karna chahiye|karu|karoon|karun)(?: chahiye)?)" +
        "|(?:(?:अब |तो )?(?:क्या|कैसे) (?:करें|करे|करना|करूँ|करूं|करना चाहिए|करें\\?)(?: चाहिए)?)"
    )
    /**
     * The user's reply to the app's own "Type "continue" to see the rest" note: [previousAnswer]
     * carries that note and the reply is the word it asked for (punctuation and case ignored).
     */
    fun isContinuationReply(reply: String, previousAnswer: String): Boolean {
        if (!previousAnswer.contains(MORE_EN) && !previousAnswer.contains(MORE_HI)) return false
        val word = reply.trim().trim('.', '!', '?', '"', '\'', ' ').lowercase()
        return word == CONTINUE_WORD
    }

    const val CONTINUE_WORD = "continue"

    const val MORE_EN = "(The source has more on this. Type \"continue\" to see the rest.)"
    const val MORE_HI = "(स्रोत में इस पर और जानकारी है। बाकी देखने के लिए \"continue\" लिखें।)"

    private const val MIN_LOOP_OCCURRENCES = 3
    /**
     * Degenerate repetition anywhere in a draft, in any script, with or without spaces:
     * "heinheinhein…", "है है है है …", or one phrase written again and again. Returns a short
     * description, or null. A unit of [MIN_LOOP_UNIT_CHARS]..[MAX_LOOP_UNIT_CHARS] characters that
     * contains a letter, repeated [MIN_LOOP_REPEATS]+ times back to back over [MIN_LOOP_SPAN_CHARS]+
     * characters, is a decoder loop. Table rows are exempt ('|' or tab in the unit): identical cells
     * are legitimate there. Also flags a long draft whose words are almost all the same few words.
     */
    /** The repeating unit of a degenerate loop (whitespace-normalised), or null. */
    fun loopUnit(text: String): String? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        val n = t.length
        if (n < MIN_LOOP_SPAN_CHARS) return null
        for (u in MIN_LOOP_UNIT_CHARS..minOf(MAX_LOOP_UNIT_CHARS, n / MIN_LOOP_REPEATS)) {
            var i = 0
            while (i + u * MIN_LOOP_REPEATS <= n) {
                var repeats = 1
                while (i + (repeats + 1) * u <= n && t.regionMatches(i, t, i + repeats * u, u)) repeats++
                if (repeats >= MIN_LOOP_REPEATS && repeats * u >= MIN_LOOP_SPAN_CHARS) {
                    val unit = t.substring(i, i + u)
                    if (unit.any { it.isLetter() } && '|' !in unit && '\t' !in unit && unit.trim().length >= 8) return unit
                }
                i += if (repeats > 1) (repeats - 1) * u else 1
            }
        }
        return null
    }

    fun degenerateRepetition(text: String): String? {
        val t = text.replace(Regex("\\s+"), " ").trim()
        val n = t.length
        if (n >= MIN_LOOP_SPAN_CHARS) {
            for (u in MIN_LOOP_UNIT_CHARS..minOf(MAX_LOOP_UNIT_CHARS, n / MIN_LOOP_REPEATS)) {
                var i = 0
                while (i + u * MIN_LOOP_REPEATS <= n) {
                    var repeats = 1
                    while (i + (repeats + 1) * u <= n && t.regionMatches(i, t, i + repeats * u, u)) repeats++
                    if (repeats >= MIN_LOOP_REPEATS && repeats * u >= MIN_LOOP_SPAN_CHARS) {
                        val unit = t.substring(i, i + u)
                        if (unit.any { it.isLetter() } && '|' !in unit && '\t' !in unit) {
                            return "repeat:${unit.trim().take(24)}x$repeats"
                        }
                    }
                    i += if (repeats > 1) (repeats - 1) * u else 1
                }
            }
        }
        val words = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*").findAll(t.lowercase()).map { it.value }.toList()
        if (words.size >= LOW_DIVERSITY_MIN_WORDS && words.toSet().size.toDouble() / words.size < LOW_DIVERSITY_RATIO) {
            return "low_diversity:${words.toSet().size}/${words.size}"
        }
        return null
    }

    private const val MIN_LOOP_UNIT_CHARS = 2
    private const val MAX_LOOP_UNIT_CHARS = 80
    private const val MIN_LOOP_REPEATS = 4
    private const val MIN_LOOP_SPAN_CHARS = 24
    private const val LOW_DIVERSITY_MIN_WORDS = 40
    private const val LOW_DIVERSITY_RATIO = 0.25

    private const val NEAR_DUPLICATE = 0.8
    private const val MIN_DEDUPE_WORDS = 6
    private const val MAX_UNIT_WORDS = 12
    private const val MIN_REPEATS = 4
}
