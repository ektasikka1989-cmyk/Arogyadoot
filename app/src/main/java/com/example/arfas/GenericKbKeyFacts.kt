package com.example.arfas

import java.util.Locale

/**
 * Key-fact extraction: the few sentences of the retrieved passages that answer the question.
 *
 * Small answer models (Gemma 3n E2B, and 0.5–1 B models even more) lose the answer when it is
 * buried in 4–6 full passages: they copy the wrong number, swap terms, or summarise the page
 * instead of the rule. The same cross-encoder that ranks passages scores every sentence-sized
 * unit of the top passages against the question; the best units (about [DEFAULT_BUDGET]
 * characters) become the evidence. Generic for any KB: no topic lists, only sentence boundaries.
 *
 * Without the re-ranker the units are scored by question-word overlap (weighted by rarity in
 * the retrieved passages), so the path still works on phones where the cross-encoder is off.
 */
object GenericKbKeyFacts {
    const val DEFAULT_BUDGET = 1_300
    const val MAX_FACTS = 7
    private const val MIN_UNIT = 60
    /** A unit read together with the previous one may borrow its context, with this penalty. */
    private const val CONTEXT_PENALTY = 1.5f
    /** Units kept per passage at most, so one long page cannot crowd out a second source. */
    private const val PER_PASSAGE = 4
    private const val CONTEXT_UNIT_CHARS = 140
    /** Sentences longer than this are split at "; " / ": " into clauses. */
    private const val LONG_UNIT = 350
    private const val MAX_MERGED = 450
    /** The best facts kept even when they exceed the budget (up to twice the budget). */
    private const val ALWAYS_KEPT = 3
    /** A fact sharing this share of its words with an already chosen fact is a near-repeat. */
    private const val REDUNDANT_OVERLAP = 0.8
    private val LIST_INTRO = Regex("(:|following|as under|as follows)[\\W&&[^.]]{0,6}(\\(?([ivx]{1,4}|[a-h]|\\d{1,2})[.)])?[\\s;—–-]*$", RegexOption.IGNORE_CASE)
    /** Units that end with a short capitalised abbreviation ("Tab.", "No.", "Rs.") continue in the next. */
    private val ABBREVIATION_END = Regex("(^|\\s)[A-Z][a-z]{0,2}\\.$")
    private const val MAX_LIST = 600
    private const val MAX_TABLE = 700
    private const val TABLE_DIGIT_SHARE = 0.3
    /** Share of the passage's re-ranker score added to a sentence's own score when ordering facts. */
    internal var PASSAGE_WEIGHT = 0.0f
    /** The best NEIGHBOUR_FACTS facts are given with the next sentence of their passage (0 weight = off). */
    internal var NEIGHBOUR_FACTS = 0
    internal var NEIGHBOUR_WEIGHT = 1f
    private const val MAX_NEIGHBOURED = 700
    /** Re-ranker logits: facts this far below the best are not compared for conflicting values. */
    private const val CONFLICT_WINDOW = 3.0f

    data class Unit(val passage: Int, val index: Int, val text: String)
    data class Fact(val passage: Int, val index: Int, val text: String, val score: Float)

    // Sentence ends (also Devanagari danda), clause markers "(a) ", "(ii) " and numbered items "2. Text".
    private val SPLIT = Regex(
        "(?<=[.;?!।])\\s+(?=[A-Z0-9(\"'‘“ऀ-ॿ])|\\s+(?=\\(?[a-z]{1,4}\\)\\s)|\\s+(?=\\d{1,2}\\.\\s+[A-Z])"
    )
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    /** Sentence-sized units of [text]; very short pieces are joined to the next one. */
    fun units(text: String): List<String> {
        val raw = SPLIT.split(text.replace(Regex("\\s+"), " ").trim()).map { it.trim() }.filter { it.isNotEmpty() }
        // "Tab. Chloroquine", "No. of days", "Rs. 500": a short abbreviation is not a sentence end.
        val parts = mutableListOf<String>()
        for (p in raw) {
            if (parts.isNotEmpty() && ABBREVIATION_END.containsMatchIn(parts.last())) parts[parts.lastIndex] = parts.last() + " " + p
            else parts += p
        }
        val out = mutableListOf<String>()
        var buffer = ""
        for (p in parts.flatMap { splitLong(it) }) {
            buffer = if (buffer.isEmpty()) p else "$buffer $p"
            if (buffer.length >= MIN_UNIT) { out += buffer; buffer = "" }
        }
        if (buffer.isNotEmpty()) { if (out.isEmpty()) out += buffer else out[out.lastIndex] = out.last() + " " + buffer }
        val merged = mutableListOf<String>()
        var i = 0
        while (i < out.size) {
            var u = out[i]
            i++
            if (u.trimEnd().endsWith("?") && i < out.size) {
                // An FAQ question is not an answer: it is kept together with the answer that follows.
                val room = MAX_LIST - u.length - 1
                u = if (out[i].length <= room) "$u ${out[i]}" else "$u ${cut(out[i], room)}"
                i++
            } else if (LIST_INTRO.containsMatchIn(u)) {
                // A list introduction ("… exempted from furnishing EMD: i.", "carry the following—")
                // belongs with its items: following units are added up to MAX_LIST, the last one cut
                // at a word boundary.
                while (i < out.size && u.length < MAX_LIST) {
                    val room = MAX_LIST - u.length - 1
                    u = if (out[i].length <= room) "$u ${out[i]}" else "$u ${cut(out[i], room)}"
                    i++
                }
            } else if (isTabular(u)) {
                // Table rows ("5-8 2 2 1 9-14 3 3 1½") stay together: a row is meaningless alone.
                while (i < out.size && isTabular(out[i]) && u.length + out[i].length < MAX_TABLE) { u = "$u ${out[i]}"; i++ }
            }
            merged += u
        }
        return merged
    }

    /** Mostly numbers (table rows, dose charts): at least [TABLE_DIGIT_SHARE] of 6+ tokens hold a digit or fraction. */
    internal fun isTabular(text: String): Boolean {
        val tokens = text.split(' ').filter { it.isNotBlank() }
        if (tokens.size < 6) return false
        val numeric = tokens.count { t -> t.any { it.isDigit() || it in "½¼¾" } }
        return numeric.toDouble() / tokens.size >= TABLE_DIGIT_SHARE
    }

    private fun cut(text: String, room: Int): String {
        if (room <= 20) return "…"
        val end = text.lastIndexOf(' ', room).takeIf { it > room / 2 } ?: room
        return text.take(end).trimEnd() + " …"
    }

    /**
     * Legal sentences can run to 600+ characters; one such sentence would use half the budget.
     * Longer than [LONG_UNIT]: split after "; " or ": " so each clause is scored on its own.
     */
    private fun splitLong(sentence: String): List<String> {
        if (sentence.length <= LONG_UNIT) return listOf(sentence)
        val pieces = Regex("(?<=[;:])\\s+").split(sentence).filter { it.isNotBlank() }
        if (pieces.size <= 1) return listOf(sentence)
        val out = mutableListOf<String>()
        var buffer = ""
        for (p in pieces) {
            buffer = if (buffer.isEmpty()) p else "$buffer $p"
            if (buffer.length >= MIN_UNIT) { out += buffer; buffer = "" }
        }
        if (buffer.isNotEmpty()) { if (out.isEmpty()) out += buffer else out[out.lastIndex] = out.last() + " " + buffer }
        return out
    }

    fun candidates(passages: List<String>): List<Unit> =
        passages.flatMapIndexed { p, text -> units(text).mapIndexed { i, u -> Unit(p, i, u) } }
            // 12 Nov 2026 (BA): form and checklist rows are never key facts. On the laptop scorecard the re-ranker gave
            // "Pus filled pustules Yes/No Yes/No Yes/No …" (a supervision form) 3.1, above the guide's own danger-sign
            // sentence; the form then led the evidence and the model copied it. Generic: any document's forms.
            .mapNotNull { u -> if (!ArogyaFlags.formRowsOff) u else stripFormCells(u.text)?.let { t -> if (t == u.text) u else u.copy(text = t) } }

    /** A supervision/record form row: repeated Yes/No or Y/N cells, "Day 1 Day 3 …" headers, "Ask/Examine" grids. */
    internal fun isFormRow(text: String): Boolean {
        val yn = FORM_YN.findAll(text).count()
        if (yn >= 2) return true
        val words = text.split(' ').count { it.isNotBlank() }
        return (words >= 6 && FORM_DAYS.containsMatchIn(text)) || FORM_GRID.containsMatchIn(text)
    }

    /** A window with its form rows removed sentence by sentence (sentences that are only form cells are dropped). */
    internal fun stripFormText(text: String): String {
        if (!FORM_YN.containsMatchIn(text) && !FORM_DAYS.containsMatchIn(text) && !FORM_GRID.containsMatchIn(text)) return text
        val parts = Regex("(?<=[.!?।])\\s+|\\n").split(text)
        return parts.mapNotNull { s -> if (s.isBlank()) null else stripFormCells(s) }.joinToString(" ").replace(Regex(" {2,}"), " ").trim()
    }

    /** The unit without its form cells (Yes/No runs, "Day 1 Day 3 …"); null when too little prose is left. */
    internal fun stripFormCells(text: String): String? {
        if (!isFormRow(text)) return text
        val t = text.replace(FORM_YN, " ").replace(FORM_DAYS, " ").replace(FORM_GRID, " ").replace(Regex("\\s{2,}"), " ").trim()
        val words = t.split(' ').count { w -> w.any(Char::isLetter) }
        return if (words >= 5) t else null
    }
    private val FORM_YN = Regex("(?i)\\b(?:yes\\s*/\\s*no|y\\s*/\\s*n)\\b")
    private val FORM_DAYS = Regex("(?i)(?:\\bday\\s*\\d+\\s*){3,}")
    private val FORM_GRID = Regex("(?i)^\\s*(?:ask\\s*/\\s*examine|supervisor['’]?s note|annexure)\\b")

    /**
     * Best units for [question] from [passages] (in rank order). [score] is the cross-encoder
     * (null result or null function = lexical scoring). Returns facts in selection order.
     */
    suspend fun select(
        question: String,
        passages: List<String>,
        score: (suspend (String, List<String>) -> FloatArray?)?,
        budget: Int = DEFAULT_BUDGET,
        maxFacts: Int = MAX_FACTS,
        /** Re-ranker score of each whole passage (same order as [passages]); null when not available. */
        passageScores: List<Float>? = null,
        /**
         * 9 Nov: for one part of a split question ("What should be done when a child is vomiting?"): only
         * sentences containing the part's most specific word by guide-wide weight ("vomit") count, so a
         * sentence sharing only filler words ("what has to be done") is never picked. Needs [corpusWeight].
         */
        requireFocus: Boolean = false,
        /** The question's other parts: words they share with this part are not its focus (see [focusStem]). */
        siblings: List<String> = emptyList()
    ): List<Fact> {
        val units = candidates(passages)
        if (units.isEmpty()) return emptyList()
        // Short units ("(b) 5 per cent of the value.") are also read with the unit before them, which
        // usually names what they are about; long units carry their own context.
        // Table rows are read with the unit before them too: that is usually the table's header.
        val needContext = units.indices.filter { i ->
            (units[i].text.length < CONTEXT_UNIT_CHARS || isTabular(units[i].text)) && i > 0 && units[i - 1].passage == units[i].passage
        }
        val withPrevious = BooleanArray(units.size)
        val model = score?.let { s ->
            val alone = runCatching { s(question, units.map { it.text }) }.getOrNull()
            if (alone == null || alone.size != units.size) return@let null
            val ctx = if (needContext.isEmpty()) null else runCatching {
                s(question, needContext.map { units[it - 1].text + " " + units[it].text })
            }.getOrNull()?.takeIf { it.size == needContext.size }
            val out = alone.copyOf()
            ctx?.forEachIndexed { k, c ->
                val i = needContext[k]
                // The pair scored better than the unit alone: the fact is the pair, as it was scored.
                if (c - CONTEXT_PENALTY > out[i]) { out[i] = c - CONTEXT_PENALTY; withPrevious[i] = true }
            }
            out
        }
        val scores = model ?: lexicalScores(question, units.map { it.text }, passages.size)
        if (requireFocus && model == null) focusStem(question, siblings)?.let { f ->
            units.forEachIndexed { i, u -> if (f !in stems(u.text)) scores[i] = 0f }
            // No sentence names it: this part adds no fact (rather than one that shares only filler words).
            if (scores.none { it > 0f }) return emptyList()
            // Only sentences naming it (lexical mode has no paired units, so the sub-list is safe).
            val keep = units.indices.filter { scores[it] > 0f }
            return pick(keep.map { units[it] }, FloatArray(keep.size) { scores[keep[it]] }, budget, maxFacts)
        }
        // No re-ranker and no question word in any sentence (e.g. a Hindi question on an English
        // pack): nothing to choose by, so the caller keeps the whole passages instead.
        if (model == null && scores.none { it > 0f }) return emptyList()
        // Order: the sentence's own score plus a share of its passage's score. A sentence that answers
        // the question usually sits in a passage the re-ranker also rates highly; a sentence that only
        // shares the question's words ("Payment of incentive to ASHA: … as per following rates") often
        // sits in a weaker one. The fact keeps its own sentence score (turn relevance, conflicts).
        val order = if (model != null && passageScores != null && passageScores.size == passages.size)
            FloatArray(units.size) { i -> scores[i] + PASSAGE_WEIGHT * passageScores[units[i].passage] } else null
        return pick(units, scores, budget, maxFacts, withPrevious, order)
    }

    internal fun pick(
        units: List<Unit>,
        scores: FloatArray,
        budget: Int,
        maxFacts: Int,
        withPrevious: BooleanArray = BooleanArray(units.size),
        orderBy: FloatArray? = null
    ): List<Fact> {
        val rank = orderBy ?: scores
        val order = units.indices.sortedByDescending { rank[it] }
        val picked = mutableListOf<Fact>()
        val pickedTerms = mutableListOf<Set<String>>()
        val used = HashSet<Int>()
        val perPassage = HashMap<Int, Int>()
        var size = 0
        for (i in order) {
            if (i in used) continue
            val u = units[i]
            val pair = withPrevious[i] && (i - 1) !in used
            val text = if (pair) units[i - 1].text + " " + u.text else u.text
            // The best few facts are always kept (up to twice the budget); after that, only what fits.
            val guaranteed = picked.size < ALWAYS_KEPT && size + text.length <= budget * 2
            if (!guaranteed && picked.isNotEmpty() && size + text.length > budget) continue
            if ((perPassage[u.passage] ?: 0) >= PER_PASSAGE) continue
            // Near-repeats (the same rule in three guides) add nothing; the slot goes to new content.
            val terms = terms(text)
            if (pickedTerms.any { overlap(it, terms) >= REDUNDANT_OVERLAP }) continue
            perPassage[u.passage] = (perPassage[u.passage] ?: 0) + 1
            var factText = text
            used += i; if (pair) used += i - 1
            // The best facts carry the sentence that follows them in the same passage: lists, steps and
            // conditions usually continue there ("Persistent sadness … Loss of interest …" → "Excessive
            // worrying …"). Only when it is not already chosen and the fact stays short enough.
            if (picked.size < NEIGHBOUR_FACTS && NEIGHBOUR_WEIGHT > 0f) {
                val next = i + 1
                if (next < units.size && units[next].passage == u.passage && next !in used &&
                    factText.length + units[next].text.length <= MAX_NEIGHBOURED
                ) { factText = factText + " " + units[next].text; used += next }
            }
            picked += Fact(u.passage, if (pair) u.index - 1 else u.index, factText, scores[i])
            pickedTerms += terms
            size += factText.length
            if (picked.size >= maxFacts || size >= budget) break
        }
        return picked
    }

    /** Share of the smaller word set found in the other. */
    private fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / minOf(a.size, b.size)
    }

    /** Question words in the unit, weighted by how rare they are among the units; earlier passages win ties. */
    /**
     * 9 Nov: guide-wide word weights, set from the installed pack's own statistics (no word list):
     * weight of a word stem = ln(1 + passages / passages containing it). Words found all over the guide
     * ("what", "done", "should", "ASHA") weigh almost nothing; specific ones ("vomit", "jaundice") weigh
     * a lot. Null (not loaded yet) = the previous local weighting. Switch: ArogyaFlags.guideWordWeights.
     */
    @Volatile var corpusWeight: ((String) -> Double)? = null

    /** Stem used with [corpusWeight]: the first 5 letters of a longer word ("vomiting"/"vomits" → "vomit"). */
    fun stem(word: String): String = if (word.length > 5 && word.all(Char::isLetter)) word.take(5) else word

    private fun stems(text: String): Set<String> =
        WORD.findAll(text.lowercase(Locale.ROOT)).map { it.value }
            .filter { it.length >= 3 || it.any(Char::isDigit) }.map(::stem).toSet()

    /** A word in at most 1 of 20 guide passages is specific enough to name a topic (weight ln(1 + 20)). */
    private val CARRY_MIN_WEIGHT = kotlin.math.ln(21.0)

    /**
     * 9 Nov: true when the model's rewrite of a follow-up names a specific word of the earlier question
     * ([previous]: its words and its rewrite) that the new [question] itself does not contain:
     * "aur kitne din dena hai?" → "For how many days should zinc be given …" (zinc). "Specific" is judged by
     * rarity in the installed guide, so common words ("child", "should") never count. False without weights.
     */
    internal fun carriesOver(rewrite: List<String>, question: String, previous: String): Boolean {
        if (previous.isBlank()) return false
        val cw = corpusWeight?.takeIf { ArogyaFlags.guideWordWeights } ?: return false
        val own = stems(question)
        val earlier = stems(previous)
        return rewrite.flatMap { stems(it) }.any { it.length >= 3 && it !in own && it in earlier && cw(it) >= CARRY_MIN_WEIGHT }
    }

    /** True when [text] contains the word stem [stem] (as [focusStem] returns it). */
    internal fun hasStem(text: String, stem: String): Boolean = stem in stems(text)

    /**
     * The question's most specific stem (highest guide-wide weight); null without weights. Stems shared with
     * [siblings] (the other parts of the same question: "What should be done when a child has fever?" /
     * "... is vomiting?") are skipped, so the part's own problem word wins over the common wording.
     */
    internal fun focusStem(question: String, siblings: List<String> = emptyList()): String? {
        val cw = corpusWeight?.takeIf { ArogyaFlags.guideWordWeights } ?: return null
        val own = stems(question).filter { it.length >= 4 && it.any(Char::isLetter) }
        val shared = siblings.flatMap { stems(it) }.toSet()
        return (own.filter { it !in shared }.ifEmpty { own }).maxByOrNull { cw(it) }
    }

    internal fun lexicalScores(question: String, units: List<String>, passages: Int): FloatArray {
        val cw = corpusWeight?.takeIf { ArogyaFlags.guideWordWeights }
        if (cw != null) {
            val q = stems(question)
            if (q.isEmpty()) return FloatArray(units.size)
            val weights = q.associateWith { cw(it) }
            return FloatArray(units.size) { i -> val t = stems(units[i]); q.sumOf { w -> if (w in t) weights[w] ?: 0.0 else 0.0 }.toFloat() }
        }
        val q = terms(question)
        if (q.isEmpty()) return FloatArray(units.size)
        val unitTerms = units.map { terms(it) }
        val df = HashMap<String, Int>()
        unitTerms.forEach { t -> t.forEach { df[it] = (df[it] ?: 0) + 1 } }
        return FloatArray(units.size) { i ->
            val t = unitTerms[i]
            q.sumOf { w -> if (w in t) kotlin.math.ln(1.0 + units.size.toDouble() / (df[w] ?: 1)) else 0.0 }.toFloat()
        }
    }

    private fun terms(text: String): Set<String> =
        WORD.findAll(text.lowercase(Locale.ROOT)).map { it.value }
            .filter { it.length >= 3 || it.any(Char::isDigit) }
            .map { if (it.length > 6 && it.all(Char::isLetter)) it.take(6) else it }
            .toSet()

    /**
     * Facts grouped per passage (for the evidence packet): the passage's best fact first (a small
     * model leans on the first line it reads), then the others in the passage's reading order.
     */
    fun byPassage(facts: List<Fact>): Map<Int, List<Fact>> =
        facts.groupBy { it.passage }.mapValues { (_, v) -> listOf(v.first()) + v.drop(1).sortedBy { it.index } }

    /** The same kind of value stated differently by different documents among the chosen facts. */
    data class ValueConflict(val kind: String, val statements: List<Pair<Int, String>>)

    private val NUMBER_WORDS = Regex(
        "\\b(?:one|two|three|four|five|six|seven|eight|nine|ten|twelve|fifteen|twenty|thirty|fifty)\\s*\\((\\d+(?:\\.\\d+)?)\\)",
        RegexOption.IGNORE_CASE
    )
    private val VALUE_KINDS = listOf(
        "percent" to Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|per\\s*cent\\b|percent\\b|प्रतिशत)", RegexOption.IGNORE_CASE),
        "days" to Regex("(\\d+)\\s*(?:working\\s+)?(?:days?|दिन)\\b", RegexOption.IGNORE_CASE),
        "weeks" to Regex("(\\d+)\\s*(?:weeks?|सप्ताह)\\b", RegexOption.IGNORE_CASE),
        "months" to Regex("(\\d+)\\s*(?:months?|माह|महीने)\\b", RegexOption.IGNORE_CASE),
        "years" to Regex("(\\d+)\\s*(?:years?|वर्ष|साल)\\b", RegexOption.IGNORE_CASE),
        "hours" to Regex("(\\d+)\\s*(?:hours?|hrs?|घंटे)\\b", RegexOption.IGNORE_CASE),
        "amount" to Regex("(?:Rs\\.?|₹|INR)\\s*(\\d[\\d,]*(?:\\.\\d+)?)\\s*(lakh|lac|crore|cr)?", RegexOption.IGNORE_CASE)
    )

    /**
     * Different documents giving different values of the same kind (per cent, days, amounts …) in
     * the best [top] facts: e.g. liquidated damages "1.5 % per week" in one manual and "0.5 % per
     * week" on GeM. A small model otherwise copies whichever comes first. Null when the documents
     * agree or only one document states a value.
     */
    fun conflict(facts: List<Fact>, documentOf: (Int) -> String, top: Int = 5): ValueConflict? {
        // Only facts close to the best one: a value in a weakly related sentence is not a disagreement.
        val bestScore = facts.maxOfOrNull { it.score } ?: return null
        val best = facts.take(top).filter { it.score >= bestScore - CONFLICT_WINDOW }
        for ((kind, rx) in VALUE_KINDS) {
            val byDocument = LinkedHashMap<String, MutableSet<String>>()
            val firstStatement = LinkedHashMap<String, Pair<Int, String>>()
            for (f in best) {
                val text = NUMBER_WORDS.replace(f.text) { it.groupValues[1] }
                val values = rx.findAll(text).map { m ->
                    (m.groupValues[1].replace(",", "") + (m.groupValues.getOrNull(2)?.lowercase(Locale.ROOT)?.let { " $it" } ?: "")).trim()
                }.toSet()
                if (values.isEmpty()) continue
                val doc = documentOf(f.passage)
                byDocument.getOrPut(doc) { LinkedHashSet() } += values
                firstStatement.putIfAbsent(doc, f.passage to rx.find(text)!!.value)
            }
            if (byDocument.size < 2) continue
            val sets = byDocument.values.toList()
            val disagree = sets.indices.any { a -> sets.indices.any { b -> a < b && sets[a].intersect(sets[b]).isEmpty() } }
            if (disagree) return ValueConflict(kind, firstStatement.values.toList())
        }
        return null
    }

    /**
     * PocketSage-style evidence for models that can read ~3–4K characters (Gemma 3n / Gemma 4 E2B):
     * the top [count] passages as they are, each cut to at most [maxChars] around its best sentence
     * (whole passages keep lists, steps and the conditions around a rule, which sentence picking
     * loses). Returns (passage index, text) in passage rank order.
     */
    fun passageWindows(passages: List<String>, facts: List<Fact>, count: Int = WINDOW_PASSAGES, maxChars: Int = WINDOW_CHARS,
        /**
         * 12 Nov 2026 (AU, phone run): passages that hold none of the question's key sentences only filled the
         * remaining slots ("avoid heat exposure" for fever with rash, an anaemia-guideline page for a newborn with
         * pus spots) and the small model blended them into the answer. When true (phone), only the best passage and
         * passages with a key sentence are read. 273 desktop questions: 266 → 266, 13% fewer passages.
         */
        factPassagesOnly: Boolean = false): List<Pair<Int, String>> =
        // Passages in the order of their best sentence (so each part of a question gets its passage),
        // then the next best-ranked passages up to [count].
        // The re-ranker's best passage (index 0) always comes first: a small answer model leans on
        // the first passage, and word overlap alone put "VHSND: ASHA, ANM and AWW attend" ahead of
        // "An ASHA is a woman selected by her community…".
        (listOf(0).filter { passages.isNotEmpty() } + facts.map { it.passage }.distinct() +
            (if (factPassagesOnly && facts.isNotEmpty()) emptyList() else passages.indices.toList())).distinct().take(count).map { p ->
            val raw = passages[p]
            // R1-lite: paragraph- and step-aware cuts (newlines kept, cuts prefer them).
            if (ArogyaFlags.r1WideEvidence) return@map p to windowR1(raw, facts, p, maxChars)
            val text = raw.replace(Regex("\\s+"), " ").trim()
            if (text.length <= maxChars) return@map p to text
            val best = facts.firstOrNull { it.passage == p }
            val anchor = best?.let { f -> text.indexOf(f.text.take(40)).takeIf { it >= 0 } } ?: 0
            // Start a little before the best sentence (the heading or condition it belongs to), at a sentence start.
            var start = (anchor - maxChars / 4).coerceAtLeast(0)
            if (start > 0) {
                val boundary = Regex("[.;:?!।]\\s+").findAll(text.substring(start, anchor.coerceAtLeast(start))).lastOrNull()
                start = if (boundary != null) start + boundary.range.last + 1 else start
            }
            var end = (start + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                val cut = text.lastIndexOf(". ", end).takeIf { it > start + maxChars / 2 }
                end = if (cut != null) cut + 1 else text.lastIndexOf(' ', end).coerceAtLeast(start + maxChars / 2)
            }
            p to ((if (start > 0) "… " else "") + text.substring(start, end).trim() + (if (end < text.length) " …" else ""))
        }.map { (p, w) -> p to completeSentences(w) }
            // 12 Nov 2026 (BA): form cells never reach the model's evidence either (same rule as key facts).
            .map { (p, w) -> p to (if (ArogyaFlags.formRowsOff) stripFormText(w) else w) }
            .filter { it.second.isNotBlank() }.let { windows ->
            // The best sentences always reach the model: one that fell outside every window (a fifth
            // passage, or beyond the cut) is added after its passage's window, or as its own item.
            val out = windows.map { it.first to it.second }.toMutableList()
            facts.take(EXTRA_BEST_FACTS).forEach { f ->
                val key = f.text.replace(Regex("\\s+"), " ").take(60)
                if (out.none { it.second.contains(key) }) {
                    val at = out.indexOfFirst { it.first == f.passage }
                    if (at >= 0) out[at] = out[at].first to (out[at].second + " … " + f.text) else out += f.passage to f.text
                }
            }
            out
        }

    /**
     * R1-lite window: newlines are kept (steps, bullets and paragraphs stay readable) and cuts
     * prefer paragraph breaks, then line breaks, then sentence ends. The anchor is the best
     * fact's first words, matched across whitespace (the fact text itself is one line).
     */
    internal fun windowR1(raw: String, facts: List<Fact>, p: Int, maxChars: Int): String {
        val text = raw.replace("\r\n", "\n")
            .replace(Regex("[ \\t\\x0B\\f]+"), " ")
            .replace(Regex("\n[ \\t]*\n([ \\t]*\n)+"), "\n\n")
            .trim()
        if (text.length <= maxChars) return text
        val keyWords = facts.firstOrNull { it.passage == p }?.text
            ?.replace(Regex("\\s+"), " ")?.split(' ').orEmpty()
            .filter { it.length > 2 }.take(5).map { Regex.escape(it) }
        val anchor = if (keyWords.size < 2) 0
        else runCatching { Regex(keyWords.joinToString("\\s+")).find(text)?.range?.first ?: 0 }.getOrDefault(0)
        var start = (anchor - maxChars / 4).coerceAtLeast(0)
        if (start > 0) {
            val look = text.substring(start, anchor.coerceAtLeast(start))
            val para = look.lastIndexOf("\n\n").takeIf { it >= 0 }?.let { start + it + 2 }
            val line = look.lastIndexOf('\n').takeIf { it >= 0 }?.let { start + it + 1 }
            val sent = Regex("[.;:?!।]\\s+").findAll(look).lastOrNull()?.let { start + it.range.last + 1 }
            start = para ?: line ?: sent ?: start
        }
        var end = (start + maxChars).coerceAtMost(text.length)
        if (end < text.length) {
            val minEnd = start + maxChars / 2
            val seg = text.substring(minEnd, end)
            val nl = seg.lastIndexOf("\n\n").takeIf { it >= 0 }?.let { minEnd + it }
                ?: seg.lastIndexOf('\n').takeIf { it >= 0 }?.let { minEnd + it }
            val sentEnd = listOf(". ", "। ", "? ", "! ", ".\n", "।\n")
                .map { text.lastIndexOf(it, end - 1) }.maxOrNull()
                ?.takeIf { it > minEnd }?.let { it + 1 }
            end = nl ?: sentEnd ?: text.lastIndexOf(' ', end).coerceAtLeast(minEnd)
        }
        return ((if (start > 0) "… " else "") + text.substring(start, end).trim() + (if (end < text.length) " …" else ""))
    }

    /**
     * Drops a half sentence at either end of a passage window ("… the AoN would"): the source chunk
     * itself often starts or ends mid-sentence, and a small model copies the broken sentence into its
     * answer. Only trimmed when at least [MIN_KEPT_SHARE] of the window remains.
     */
    fun completeSentences(window: String): String {
        var text = window.trim()
        val minKept = (text.length * MIN_KEPT_SHARE).toInt()
        val core = text.removePrefix("… ").removeSuffix(" …").trim()
        // 9 Nov 2026: a cut window ending in a digit ("For one glass (200") is a half sentence, not a number at a
        // sentence end; only an uncut window may end on a digit.
        val cut = text.endsWith("…")
        if (core.isNotEmpty() && !core.last().let { it in ".!?।:)\"”'" || (it.isDigit() && !cut) }) {
            val lastEnd = Regex("[.!?।](?=[\\s\"”'])").findAll(text).lastOrNull()?.range?.last
            if (lastEnd != null && lastEnd + 1 >= minKept) text = text.substring(0, lastEnd + 1) + " …"
        }
        val head = text.removePrefix("… ")
        if (head.isNotEmpty() && head.first().isLowerCase()) {
            val firstEnd = Regex("[.!?।]\\s+(?=[A-Z0-9(•\"“])").find(head)?.range?.last
            if (firstEnd != null && head.length - (firstEnd + 1) >= minKept) text = "… " + head.substring(firstEnd + 1).trim()
        }
        return text
    }

    private const val MIN_KEPT_SHARE = 0.6
    /**
     * Four passages of ~900 characters around their best sentences. Measured on 77 ASHA questions:
     * the answer is in the evidence for 65 (4 x 900, ~3,800 characters), 60 (3 x 700) and 59
     * (3 x 600): less to read loses answers, so the size stays.
     */
    @Volatile var WINDOW_PASSAGES = 4
    @Volatile var WINDOW_CHARS = 900
    private const val EXTRA_BEST_FACTS = 3
}
