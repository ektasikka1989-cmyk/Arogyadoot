package com.example.arfas

import android.util.Log
import java.util.Locale

/**
 * Domain-neutral selected knowledge-pack provider.
 *
 * Contract:
 * - the user explicitly selects the pack;
 * - retrieval is hybrid lexical + semantic inside that pack;
 * - the literal current user turn is always the primary retrieval query;
 * - at most two generic task-shape views may supplement the literal query;
 * - evidence is fused from multiple relevant documents into one bounded packet;
 * - only sources that actually fit inside that packet are exposed to the UI;
 * - answer adequacy is judged semantically, not by domain/query keyword lists.
 */


class GenericKnowledgeContextProvider(
    private val enabled: () -> Boolean,
    private val ensureReady: suspend () -> Boolean,
    private val repository: GenericKnowledgePackRepository,
    private val isContextDependent: suspend (String, List<AndroChatMessage>) -> Boolean
) : BaselineContextProvider, BaselineResponseValidator {

    companion object {
        /** Another pack is suggested only when its best passage clears this re-ranker score… */
        const val OTHER_PACK_MIN_RERANK = -1.0f
        /** …and beats the active pack's best by this margin. */
        const val OTHER_PACK_MARGIN = 2.5f
        /** A visit note cites the knowledge base only for a passage the re-ranker clearly matched. */
        const val QUICK_CITATION_MIN_RERANK = 1.0f
        private const val MAX_OTHER_PACKS = 4
        private const val OTHER_PACK_POOL = 8

        /**
         * Follow-up resolution thresholds, in re-ranker logits (a property of the re-ranker model).
         * Measured on the 75-question set: stand-alone questions scored >= 0.9 on their own; the
         * elliptical "Explain the second one." scored -2.89 alone and 4.67 with its previous question.
         * On the phone the stand-alone "what are different type of tenders" scored 0.88 and was
         * wrongly joined to the previous question at the old limit of 1.0, so the limit is 0.
         */
        const val FOLLOW_UP_WEAK_RERANK = 0.0f
        const val FOLLOW_UP_MARGIN = 2.0f
        /** A question of at most this many words may be elliptical ("and the dose?"). */
        const val FOLLOW_UP_SHORT_WORDS = 4
        const val FOLLOW_UP_ANSWER_CHARS = PocketRagPromptBuilder.FOLLOW_UP_ANSWER_CHARS
        private const val TAG = "GenericKnowledgeContext"
        /** Best re-ranker relevance at or above which the literal search is trusted without a rewrite. */
        const val REWRITE_BELOW_RERANK = 2.0f
        /** A rewrite search is not used when its best relevance falls this far below the literal one. */
        const val REWRITE_MAX_LOSS = 1.0f
        /** Best re-ranker relevance at or above which no second search pass runs (speedSkipSecondPass). */
        const val SKIP_SECOND_PASS_RERANK = 8.0f
        /** Passages whose sentences compete for key facts. */
        const val KEY_FACT_PASSAGES = 5
        /** "Check online": passages (and characters per passage) sent to the online model. */
        const val ONLINE_PASSAGES = 12
        const val ONLINE_WINDOW_CHARS = 1_600
        /** Part B3: Check-online turns read up to 16 passages; the Cloud-first wide set stays 12 x 1600. */
        const val CHECK_PASSAGES = 16
        /** Evidence budget (characters) from which the model reads whole passages instead of key sentences. */
        const val PASSAGE_MODE_MIN_CHARS = 3_000
        /**
         * Speed pass (UI2): offline phone answers read at most this much evidence. Measured on the
         * phone: ~6,000 chars made a 2,135-token prompt and 29 s before the first word.
         */
        /**
         * 9 Nov 2026 (AF): 4,500. Raised to 9,000 earlier today, which made the phone answers short and
         * section-less: the phone's real KV cache is 4,096 tokens (ors_log4), so ~7,800 chars of evidence
         * left Gemma little room and a long prompt; answers fell from ~2,500 chars (20:19 run, 4,485 chars
         * of evidence) to ~650. The overlap guide now completes sentences at passage edges (the ORS recipe
         * cut that led to the raise came from a passage edge).
         */
        const val PHONE_EVIDENCE_CAP_CHARS = 4_500
        /** Set per turn by the Ask path: true only while an offline (phone-model) turn runs. */
        @Volatile @JvmStatic var phoneEvidenceCap: Boolean = false
            set(v) { field = v; PocketRagEvidenceAssembler.phoneCapChars = if (v) PHONE_EVIDENCE_CAP_CHARS else 0 }
        /** Set per Ask turn in a visit: the visit's age group ("newborn, 7 days old"); "" otherwise. */
        @Volatile @JvmStatic var visitAgeHint: String = ""
    }

    data class LookupState(
        val attempted: Boolean = false,
        val ready: Boolean = true,
        val confident: Boolean = false,
        val topSimilarity: Float = 0f,
        val packId: String = "",
        val displayName: String = ""
    )

    @Volatile private var lookupState = LookupState()
    /** (user question, text it was resolved to) of the previous KB turn, for chained follow-ups. */
    @Volatile private var lastResolution: Pair<String, String>? = null
    // AnythingLLM-style source-window memory: retrieval always uses the CURRENT user
    // message. Recent accepted evidence is only a context backfill when the current
    // search yields a small window; it never becomes part of the search query.
    @Volatile private var acceptedEvidenceWindow: List<GenericKnowledgePackRepository.Hit> = emptyList()
    @Volatile private var acceptedEvidencePackId: String = ""
    @Volatile private var pendingEvidenceWindow: List<GenericKnowledgePackRepository.Hit> = emptyList()
    @Volatile private var pendingEvidencePackId: String = ""
    @Volatile private var pendingEvidenceQuery: String = ""
    @Volatile private var activeContextWindowTokens: Int = 4_096
    /** The passages of the last packet, in Source ID order (S1 = first). */
    @Volatile private var lastPacked: List<GenericKnowledgePackRepository.Hit> = emptyList()

    /**
     * The cited passages' surroundings: their documents' titles and the same and two previous pages
     * (section headings). "How should a person with diabetes cut their toenails?" is answered by a
     * foot-care passage under a diabetes heading that never repeats the word; the topic check reads
     * this too, so the warning does not fire for it.
     */
    suspend fun citedContextText(sourceIds: Set<String>): String {
        val hits = sourceIds.mapNotNull { id -> id.uppercase().removePrefix("S").toIntOrNull()?.let { lastPacked.getOrNull(it - 1) } }
            .distinctBy { it.docId to it.pageNumber }.take(3)
        val sb = StringBuilder()
        for (h in hits) sb.append((h.sourceTitle ?: h.sourceName).orEmpty()).append('\n')
            .append(runCatching { repository.nearbyText(h.docId, h.pageNumber) }.getOrDefault("")).append('\n')
        return sb.toString()
    }

    /** Set during [contextForOnline]: more and longer passages for the online model. */
    @Volatile private var widePassages = false
    /** Answer-model question rewrite (search question + arithmetic); null until the runtime wires it. */
    @Volatile private var questionRewriter: (suspend (String, List<String>, String) -> GenericKbQuestionRewrite?)? = null
    /** The last question and the model's English rewrite of it (follow-ups are compared with both). */
    @Volatile private var lastRewriteOf: Pair<String, String>? = null
    /** 9 Nov 2026: this turn's search results (all candidates, best first), for confirming a number. */
    @Volatile private var turnCandidates: List<GenericKnowledgePackRepository.Hit> = emptyList()
    /** Rewrite of the current turn (for the app-side calculation in the evidence packet). */
    @Volatile private var currentRewrite: GenericKbQuestionRewrite? = null

    /**
     * 11 Nov 2026 (AS): when the model named what the question describes ("tuberculosis" for three weeks of cough and
     * weight loss), that name finds candidates too; the re-ranker still reads only the question and its search lines.
     * Desktop: the right passage reached the evidence for 39 of 40 symptom-only questions with the name, 37 without;
     * 273 questions in all: 266 vs 263; with the names shuffled to wrong ones, see FIX_LIST AS.
     */
    private fun topicViews(rw: GenericKbQuestionRewrite): List<String> =
        listOfNotNull(rw.topic?.takeIf { ArogyaFlags.rewriteTopic })
    /** 9 Nov: multi-symptom evidence — facts put first so a missing symptom's passage gets a window, and how many symptom passages follow the normal ones. */
    @Volatile private var concernFacts: List<GenericKbKeyFacts.Fact> = emptyList()
    @Volatile private var concernExtraCount: Int = 0
    @Volatile private var keyFactsScoredByModel = false

    fun configureQuestionRewriter(rewriter: suspend (String, List<String>, String) -> GenericKbQuestionRewrite?) {
        questionRewriter = rewriter
    }


    fun isSelected(): Boolean = enabled()

    /**
     * One quick look-up in the active pack (a visit note's danger sign, for its page citation):
     * the best passage's source label and its most relevant sentence, or null when nothing in the
     * pack clearly matches.
     */
    suspend fun quickCitation(query: String, minRelevance: Float = QUICK_CITATION_MIN_RERANK): Pair<String, String>? {
        if (!enabled() || query.isBlank() || !(repository.isReady() || runCatching { ensureReady() }.getOrDefault(false))) return null
        val plan = GenericKbQueryPlanner.plan(query, query)
        val result = runCatching { repository.searchPhase14(query.trim(), plan.clauses, OTHER_PACK_POOL) }.getOrNull() ?: return null
        val best = result.bestRerank ?: return null
        if (best < minRelevance) return null
        val hit = result.hits.firstOrNull() ?: return null
        val sentence = KnowledgeSourcePresentation.bestSentence(hit.content.replace(Regex("\\s+"), " ").trim().take(1_200), query)
        return sourceLabel(hit) to sentence.take(220)
    }

    /**
     * Knowledge-base health check of the active pack ([KbHealthCheck]): [samples] passages spread
     * across documents are searched for with a question made from each (by [questionWriter], the
     * phone's model, when given; else the passage's key sentence) and found or not in the top 5;
     * plus the pack's data statistics. [onProgress] gets "3/20".
     */
    suspend fun healthCheck(
        samples: Int = 20,
        questionWriter: (suspend (String) -> String?)? = null,
        onProgress: ((String) -> Unit)? = null
    ): KbHealthCheck.Report? {
        if (!(repository.isReady() || runCatching { ensureReady() }.getOrDefault(false))) return null
        val picked = repository.healthSamples(samples)
        val probes = ArrayList<KbHealthCheck.Probe>()
        var byModel = 0
        for ((i, s) in picked.withIndex()) {
            onProgress?.invoke("${i + 1}/${picked.size}")
            val written = questionWriter?.let { w -> runCatching { w(s.content.take(1_500)) }.getOrNull()?.trim()?.lineSequence()?.firstOrNull()?.trim() }
                ?.takeIf { it.length in 12..240 }
            val question = written ?: KbHealthCheck.keySentence(s.content) ?: continue
            if (written != null) byModel++
            val started = System.currentTimeMillis()
            val plan = GenericKbQueryPlanner.plan(question, question)
            val result = runCatching { repository.searchPhase14(question, plan.clauses, 24) }.getOrNull()
            // The same text from another copy of the document (an amended version) counts as found.
            fun key(t: String) = t.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }.take(300)
            val sampleKey = key(s.content)
            val rank = result?.hits?.take(5)?.indexOfFirst { h ->
                h.chunkId == s.chunkId || h.originalChunkId == s.chunkId || (sampleKey.length >= 80 && key(h.content).contains(sampleKey.take(120))) ||
                    (h.docId == s.docId && s.page != null && (h.pageNumber == s.page || (h.pageNumber != null && h.pageEnd != null && s.page in h.pageNumber..h.pageEnd)))
            }?.takeIf { it >= 0 }?.plus(1)
            probes += KbHealthCheck.Probe(s.title.ifBlank { s.docId }, s.page, question, rank, result?.bestRerank, System.currentTimeMillis() - started)
        }
        val stats = repository.healthStats()
        val report = KbHealthCheck.report(repository.currentDisplayName(), probes, stats, questionsByModel = byModel * 2 > probes.size)
        Log.i(TAG, "KB_HEALTH pack=${repository.currentDisplayName()} score=${report.score} at5=${report.recallAt5} at1=${report.recallAt1} n=${probes.size}")
        return report
    }

    /** The re-ranker as a statement-vs-passages scorer for [AnswerProof]; null until it is ready. */
    fun proofScorer(): (suspend (String, List<String>) -> FloatArray?)? = repository.keyFactScorer()

    /**
     * 9 Nov 2026: (source label | url, passage text) of the passages the search found for THIS question but did
     * not give the answer model (it reads the best 6). A number the answer states is confirmed only against them:
     * the whole guide was too wide (a dose row for another age or another medicine could "confirm" a wrong number).
     */
    suspend fun guidePassagesFor(text: String, limit: Int = 30): List<Pair<String, String>> =
        turnCandidates.take(limit).map { hit ->
            val label = sourceLabel(hit).trim().ifBlank { hit.sourceName.orEmpty().ifBlank { hit.docId } }.replace('\n', ' ').take(500)
            (label + (hit.sourceUrl?.takeIf { it.startsWith("http") }?.let { " | $it" } ?: "")) to hit.content
        }

    /** UI2: the same re-ranker reading every statement's pairs in one batched run. */
    fun proofPairScorer(): (suspend (List<Pair<String, String>>) -> FloatArray?)? = repository.pairScorer()

    data class OtherPackMatch(val packId: String, val displayName: String, val relevance: Float, val source: String)

    /**
     * The active pack does not cover [query]: search the user's other installed packs (switching is
     * a file move, tens of ms) and return the one that clearly covers it. The active pack is always
     * restored. Null when no other pack has a clearly better passage.
     * Called by the runtime inside its turn lock, after the turn's own answer is settled.
     */
    suspend fun bestOtherPackFor(query: String, activeBestRelevance: Float?): OtherPackMatch? {
        if (!enabled() || query.isBlank()) return null
        val packs = runCatching { repository.installedPacks() }.getOrDefault(emptyList())
        val active = packs.firstOrNull { it.active } ?: return null
        val others = packs.filter { !it.active }.take(MAX_OTHER_PACKS)
        if (others.isEmpty()) return null
        val floor = maxOf(OTHER_PACK_MIN_RERANK, (activeBestRelevance ?: OTHER_PACK_MIN_RERANK) + OTHER_PACK_MARGIN)
        var best: OtherPackMatch? = null
        val started = System.currentTimeMillis()
        try {
            for (pack in others) {
                if (!runCatching { repository.activatePack(pack.packId) }.getOrDefault(false)) continue
                val plan = GenericKbQueryPlanner.plan(query, query)
                val result = runCatching { repository.searchPhase14(query.trim(), plan.clauses, OTHER_PACK_POOL) }.getOrNull() ?: continue
                val score = result.bestRerank ?: continue
                if (score >= floor && score > (best?.relevance ?: Float.NEGATIVE_INFINITY)) {
                    val hit = result.hits.firstOrNull()
                    val where = listOfNotNull(
                        (hit?.sourceTitle ?: hit?.sourceName)?.let { KnowledgeSourcePresentation.cleanTitle(it) }?.takeIf { it.isNotBlank() }?.take(90),
                        hit?.pageNumber?.let { "page $it" }
                    ).joinToString(", ")
                    best = OtherPackMatch(pack.packId, pack.displayName, score, where)
                }
            }
        } finally {
            runCatching { repository.activatePack(active.packId) }
                .onFailure { Log.w(TAG, "KB_OTHER_PACK_RESTORE_FAILED ${it.message}") }
        }
        Log.i(TAG, "KB_OTHER_PACK_PROBE packs=${others.size} activeBest=$activeBestRelevance best=${best?.displayName}:${best?.relevance} ms=${System.currentTimeMillis() - started}")
        return best
    }
    fun lastLookupState(): LookupState = lookupState
    fun selectedPackIdentity(): Pair<String, String> =
        repository.currentPackId() to repository.currentDisplayName()

    /** Set by the selected-KB runtime before retrieval; defaults safely until model probing completes. */
    fun setActiveContextWindowTokens(tokens: Int) {
        activeContextWindowTokens = tokens.coerceAtLeast(1_024)
    }

    /**
     * New isolated KB engine commits the evidence window after accepting a turn
     * without invoking this provider's legacy answer validator. Retrieval state
     * remains provider-owned; answer acceptance is owned by PocketRagEngine (the selected-KB answer path).
     */
    fun commitPendingEvidenceWindow(answerUsedEvidence: Boolean = true) {
        // Only an answer that actually used the evidence (it has sources) may become the accepted
        // evidence window; a refusal or a failed answer leaves the previous window in place.
        if (!answerUsedEvidence) {
            Log.i(TAG, "GENERIC_KB_EVIDENCE_WINDOW_NOT_COMMITTED reason=answer_without_sources")
            return
        }
        if (pendingEvidenceWindow.isEmpty()) return
        acceptedEvidenceWindow = pendingEvidenceWindow
        acceptedEvidencePackId = pendingEvidencePackId
        Log.i(
            TAG,
            "GENERIC_KB_ISOLATED_ACCEPT_SOURCE_WINDOW pack=${acceptedEvidencePackId.take(24)} " +
                "query=${pendingEvidenceQuery.take(80)} chunks=${acceptedEvidenceWindow.size}"
        )
    }

    override suspend fun shouldAttach(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): Boolean = enabled() && query.isNotBlank()

    /**
     * Mechanical cross-section compaction only. No query vocabulary participates.
     * Sampling the beginning/middle/end is safer for tables/forms than blindly taking
     * only the first characters, and it remains completely domain-independent.
     */
    private fun compactAcrossContent(content: String, maxChars: Int): String {
        val clean = content.trim()
        if (clean.length <= maxChars) return clean
        if (maxChars < 180) return clean.take(maxChars)

        val third = (maxChars - 12) / 3
        val middleStart = ((clean.length - third) / 2).coerceAtLeast(third)
        val tailStart = (clean.length - third).coerceAtLeast(middleStart + third)
        return buildString {
            append(clean.take(third).trimEnd())
            append("\n…\n")
            append(clean.substring(middleStart, (middleStart + third).coerceAtMost(clean.length)).trim())
            append("\n…\n")
            append(clean.substring(tailStart).take(third).trimStart())
        }.take(maxChars)
    }


    private fun sourceLabel(hit: GenericKnowledgePackRepository.Hit): String = buildString {
        val title = hit.sourceTitle?.trim().orEmpty()
        val file = hit.sourceName?.trim().orEmpty()
        append(KnowledgeSourcePresentation.cleanTitle(if (title.isNotBlank()) title else file).ifBlank { "Knowledge source" })
        val page = hit.pageNumber
        val end = hit.pageEnd
        when {
            page != null && end != null && end > page -> append(" — pages ").append(page).append('–').append(end)
            page != null -> append(" — page ").append(page)
        }
        hit.locator?.takeIf { it.isNotBlank() && !it.contains("page", ignoreCase = true) }?.let {
            append(" — ").append(it.take(120))
        }
        // A section is shown only when the pack stores a single heading. Several lines joined with
        // '|' are the page's first lines (extraction artefact), not a section title, and misled users.
        // A running header (the same heading over many pages) is not this passage's section.
        hit.headingContext?.trim()?.takeIf { !hit.runningHeading && it.isNotBlank() && '|' !in it && it.length <= 160 }?.let {
            append(" — section ").append(it)
        }
    }

    /**
     * "Check online": the same search with an online-sized budget (the online model reads far more
     * than the phone's), so Muse Spark checks the answer against more of the related passages.
     */
    suspend fun contextForOnline(query: String, acceptedHistory: List<AndroChatMessage>, contextTokens: Int): BaselineContextAttachment? {
        val before = activeContextWindowTokens
        activeContextWindowTokens = contextTokens.coerceAtLeast(before)
        widePassages = true
        return try { contextFor(query, acceptedHistory) } finally { activeContextWindowTokens = before; widePassages = false }
    }

    override suspend fun contextFor(
        query: String,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineContextAttachment? {
        if (!enabled() || query.isBlank()) return null
        lookupState = LookupState(attempted = false, ready = true)
        if (!ensureReady()) {
            // Not ready (still loading, missing files): the turn continues without KB evidence.
            lookupState = LookupState(attempted = false, ready = false)
            Log.w(TAG, "GENERIC_KB_CONTEXT_UNAVAILABLE ready=false")
            return null
        }
        // Diagnostic only: if the pack's vectors do not match the phone's embedder, dense retrieval
        // is switched off for this pack and full-text retrieval keeps answering with citations.
        // It never blocks the knowledge base.
        val space = runCatching { repository.vectorSpaceCheck() }.getOrNull()
        val denseEnabled = !(space != null && space.checked > 0 && !space.passed)
        if (!denseEnabled) {
            Log.w(TAG, "GENERIC_KB_DENSE_DISABLED reason=${space?.reason} min=${space?.minimumCosine} fallback=lexical_only")
        }

        // Multi-view E5 + FTS retrieval: one database scan evaluates the literal turn
        // together with a small set of domain-neutral task views. This preserves exact
        // wording while recovering definitions, lists, procedures and follow-ups whose
        // wording differs from the document. No topic-specific keyword table is used.
        val started = System.currentTimeMillis()
        Log.i(TAG, "KB_ENGINE_BUILD abbrev-2026-10-02")
        // The literal question is always searched first and never rewritten. A history-joined
        // second search happens only for weak literal evidence (see follow-up resolution below).
        val resolvedRetrievalQuery = query.trim()
        Log.i(TAG, "LITERAL_FIRST_RETRIEVAL historyVariant=evidence_gated")
        val queryPlan = GenericKbQueryPlanner.plan(query, query)
        val literalSlots = repository.meaningfulLiteralSlots(query)
        Log.i(
            TAG,
            "GENERIC_KB_RETRIEVAL_QUERY literal=${query.take(100)} resolved=${resolvedRetrievalQuery.take(180)} " +
                "clauses=${queryPlan.clauses.size} literalSlots=${literalSlots.size} history=${acceptedHistory.size}"
        )
        val modelEvidenceBudget = GenericKbTokenBudget.evidenceCharBudget(
            contextTokens = activeContextWindowTokens,
            query = resolvedRetrievalQuery,
            clauses = queryPlan.clauses.size
        )
        // Offline phone turns only; "Check online" (widePassages) and cloud turns keep the full budget.
        val evidenceCharBudget = if (phoneEvidenceCap && !widePassages)
            minOf(modelEvidenceBudget, PHONE_EVIDENCE_CAP_CHARS) else modelEvidenceBudget
        if (evidenceCharBudget != modelEvidenceBudget)
            Log.i(TAG, "AROGYA_PHONE_EVIDENCE_CAP budget=$evidenceCharBudget model=$modelEvidenceBudget")
        val candidateWork = GenericKbTokenBudget.retrievalCandidatePool(
            evidenceCharBudget = evidenceCharBudget,
            clauses = queryPlan.clauses.size
        )
        // Phase 1.4 path; falls back to the previous clause search when the pack profile or
        // calibration is not available yet (or on any error), so behaviour never degrades to nothing.
        // Question rewrite by the answer model, decided by evidence and structure, not by topic:
        // a question with two or more numbers may need a calculation (rewrite first, one search);
        // otherwise the literal search runs first and the rewrite is tried only when the re-ranker
        // finds no convincing passage for the user's own words.
        currentRewrite = null
        concernFacts = emptyList()
        turnCandidates = emptyList()
        concernExtraCount = 0
        val rewriter = questionRewriter
        // The conversation so far (needed before the rewrite: a follow-up is rewritten with the earlier question).
        val previousUser = acceptedHistory.lastOrNull { it.role == "user" }?.content?.trim().orEmpty()
        val previousAnswer = acceptedHistory.lastOrNull { it.role == "assistant" }?.content?.trim().orEmpty()
        val basis = lastResolution?.takeIf { it.first == previousUser }?.second ?: previousUser
        val hasPrevious = previousUser.isNotBlank() && !previousUser.equals(query.trim(), ignoreCase = true)
        val continuation = hasPrevious && KbReplyText.isContinuationReply(query, previousAnswer)
        val previousRewrite = lastRewriteOf?.takeIf { it.first == previousUser }?.second.orEmpty()
        // 9 Nov (phone test: an unrelated second question was answered about the first one): the earlier question is
        // shown to the rewriting model only when the question may depend on it — very short ("and for children?"),
        // or the app's existing context classifier says so (embedding + structure, no word list) — and the model then
        // says itself whether it follows (FOLLOWS: YES/NO).
        val shortElliptical = query.trim().split(Regex("\\s+")).size <= FOLLOW_UP_SHORT_WORDS
        val dependent = hasPrevious && !continuation && runCatching { isContextDependent(query, acceptedHistory) }.getOrDefault(false)
        val mayFollow = hasPrevious && !continuation && (shortElliptical || dependent)
        suspend fun rewrite(withEarlier: Boolean = mayFollow): GenericKbQuestionRewrite? = rewriter?.let { r ->
            runCatching { r(resolvedRetrievalQuery, repository.aliasExpansions(resolvedRetrievalQuery), if (withEarlier) basis else "") }.getOrNull()
        }
        var phase14: GenericKnowledgePackRepository.Phase14Result? = null
        var rewriteTried = false
        // Up front only when amounts must be worked out (each rewrite is one extra model call, a few
        // seconds on the phone); other questions are rewritten only when their own words find no
        // convincing passage (below).
        // Long or two-part questions too ("explain ePBG and how it should be calculated for ..."): their
        // own words matched only the many guide pages that mention ePBG, never the rule that gives the
        // amount; one search per part finds both.
        val unknownWords = runCatching { repository.unknownWords(resolvedRetrievalQuery) }.getOrDefault(emptyList())
        if (unknownWords.isNotEmpty()) Log.i(TAG, "KB_UNKNOWN_WORDS ${unknownWords.take(6).joinToString(",")}")
        // Hindi/Hinglish questions also search English dictionary terms (the question itself
        // is untouched, so the answer stays in Hindi).
        // 9 Nov audit: English lay words the guides never use ("loose motion" 0×, "diarrhoea" 215×) get one
        // view with the guide's word, so an English question reaches the same passages as its guide wording.
        val languageViews = if (ArogyaFlags.hindiRewrite && ArogyaHindiTerms.needsViews(resolvedRetrievalQuery))
            runCatching { ArogyaHindiTerms.views(resolvedRetrievalQuery) }.getOrDefault(emptyList())
        else if (ArogyaFlags.englishLayTerms) runCatching { ArogyaHindiTerms.englishViews(resolvedRetrievalQuery) }.getOrDefault(emptyList())
        else emptyList()
        if (languageViews.isNotEmpty()) Log.i(TAG, "KB_HINDI_VIEWS ${languageViews.joinToString(" | ").take(160)}")
        // Age group of a baby/child question searched too, so adult passages do not answer it.
        val ageViews = if (ArogyaFlags.babyAgeView) runCatching { ArogyaClinicalText.ageViewsWithVisit(resolvedRetrievalQuery, visitAgeHint) }.getOrDefault(emptyList()) else emptyList()
        if (ageViews.isNotEmpty()) Log.i(TAG, "KB_AGE_VIEW ${ageViews.joinToString(" | ").take(160)}")
        // Typos fixed ("a baby is havong h9gh fever" → "a baby is having high fever"): one more search view.
        val cleaned = runCatching { ArogyaQuestionNormalizer.normalize(resolvedRetrievalQuery) }.getOrNull()
        val spellingViews = if (cleaned != null && cleaned.changed) listOf(cleaned.text) else emptyList()
        if (spellingViews.isNotEmpty()) Log.i(TAG, "KB_SPELLING_VIEW fixes=${cleaned?.fixes?.size ?: 0}")
        val hindiViews = (languageViews + ageViews + spellingViews).distinct()
        // 9 Nov: the on-device model reads EVERY question first (spelling, Hinglish, everyday words, two or more
        // problems → one search line each) instead of word lists; the literal question is still searched too.
        // Costs one short local model call per question. Switch: ArogyaFlags.rewriteEveryQuestion.
        if (rewriter != null && (ArogyaFlags.rewriteEveryQuestion || GenericKbQuestionRewrite.worthRewritingForSearch(resolvedRetrievalQuery, unknownWords.size))) {
            var rw = rewrite()
            // The model said "new question" yet carried words over from the earlier one: rewrite it alone.
            if (rw != null && mayFollow && rw.followsEarlier == false && GenericKbKeyFacts.carriesOver(rw.parts, query, "$basis $previousRewrite")) {
                Log.i(TAG, "KB_FOLLOW_UP model=new_topic carried=true → rewrite alone")
                rw = rewrite(withEarlier = false) ?: rw
            }
            currentRewrite = rw
            rewriteTried = true
            if (rw != null) {
                // The model's own lines replace the word-list views (Hindi dictionary terms, lay words, spelling
                // fixes, age words): those are used only when the model gives no rewrite (below).
                Log.i(TAG, "KB_QUESTION_REWRITE parts=${rw.parts.size} topic=${rw.topic ?: "-"} ${rw.parts.joinToString(" | ").take(200)}")
                phase14 = runCatching { repository.searchPhase14(resolvedRetrievalQuery, queryPlan.clauses, candidateWork, denseEnabled, rw.parts.distinct(), topicViews(rw)) }
                    .onFailure { Log.w(TAG, "KB_PHASE14_REWRITE_FAILED ${it.javaClass.simpleName}:${it.message}") }
                    .getOrNull()
            }
        }
        if (phase14 == null) {
            phase14 = runCatching { repository.searchPhase14(resolvedRetrievalQuery, queryPlan.clauses, candidateWork, denseEnabled, hindiViews) }
                .onFailure { Log.w(TAG, "KB_PHASE14_FAILED ${it.javaClass.simpleName}:${it.message}") }
                .getOrNull()
            val literalBestScore = phase14?.bestRerank
            if (rewriter != null && !rewriteTried && currentRewrite == null && phase14 != null && literalBestScore != null && literalBestScore < REWRITE_BELOW_RERANK) {
                val rw = rewrite()
                if (rw != null && !rw.search.equals(resolvedRetrievalQuery, ignoreCase = true)) {
                    val viaRewrite = runCatching { repository.searchPhase14(resolvedRetrievalQuery, queryPlan.clauses, candidateWork, denseEnabled, rw.parts.distinct(), topicViews(rw)) }.getOrNull()
                    val rewriteBest = viaRewrite?.bestRerank
                    val use = viaRewrite != null && viaRewrite.hits.isNotEmpty() && rewriteBest != null && rewriteBest >= literalBestScore - REWRITE_MAX_LOSS
                    Log.i(TAG, "KB_REWRITE_SEARCH literalBest=${"%.2f".format(Locale.US, literalBestScore)} rewriteBest=${rewriteBest?.let { "%.2f".format(Locale.US, it) } ?: "-"} used=$use search=${rw.search.take(120)}")
                    if (use) { phase14 = viaRewrite; currentRewrite = rw }
                }
            }
        }
        // Follow-up resolution, decided by evidence, not by words: the literal question is always
        // searched first. Only when the re-ranker finds nothing convincing for it AND there is a
        // previous question in this KB conversation, the question is searched again together with
        // the previous question (and the start of the previous answer). The contextual result is
        // used only if the re-ranker judges it clearly more relevant. Independent questions keep
        // their own strong literal evidence, so topics cannot leak between turns.
        var followUpOf: String? = null
        val literalBest = phase14?.bestRerank
        // The app's own continuation protocol: the previous answer was cut at the output limit and
        // told the user to type "continue" (KbReplyText.MORE_EN / MORE_HI). That reply continues the
        // previous answer from the same evidence; it is never searched on its own words (it matched
        // a slide saying "Click on 'Continue' to proceed").
        // 9 Nov: the model rewrote a follow-up as the complete question (its lines name a specific word of the
        // earlier question or of its rewrite that this question lacks: "aur kitne din?" → "... zinc ..."). The search
        // already used those lines, so the contextual second search is not needed; the answer is told it follows up.
        val rewroteFollowUp = mayFollow && currentRewrite?.let { rw ->
            rw.followsEarlier != false && GenericKbKeyFacts.carriesOver(rw.parts, query, "$basis $previousRewrite")
        } == true
        // The model judged it a new question: no contextual search with the earlier question either.
        val modelSaysNewTopic = mayFollow && currentRewrite?.followsEarlier == false
        if (rewroteFollowUp) {
            followUpOf = previousUser
            Log.i(TAG, "KB_FOLLOW_UP rewritten=true previous=${previousUser.take(80)} lines=${currentRewrite?.parts?.joinToString(" | ")?.take(160)}")
        }
        // Confident first pass: skip the embedding classifier and the contextual second search.
        val skipSecondPass = ArogyaFlags.speedSkipSecondPass && !continuation &&
            phase14 != null && (literalBest ?: 0f) >= SKIP_SECOND_PASS_RERANK
        if (skipSecondPass) Log.i(TAG, "KB_FOLLOW_UP skipped=confident literalBest=${"%.2f".format(Locale.US, literalBest ?: 0f)}")
        if (continuation) {
            val again = runCatching { repository.searchPhase14(basis, queryPlan.clauses, candidateWork, denseEnabled) }.getOrNull()
            if (again != null && again.hits.isNotEmpty()) {
                phase14 = again
                followUpOf = previousUser
            }
            Log.i(TAG, "KB_FOLLOW_UP continuation=true used=${followUpOf != null} previous=${previousUser.take(80)}")
        } else if (phase14 != null && literalBest != null && hasPrevious && !skipSecondPass && !rewroteFollowUp && !modelSaysNewTopic) {
            // Follow-up resolution, decided by evidence: (a) the literal question finds nothing
            // convincing, or (b) the question reads as dependent on the conversation (embedding
            // classifier; a complete question that names its own subject never is). The question is
            // then searched again together with the previous one; the contextual result is used when
            // the re-ranker judges it more relevant (by a margin for (a); at least as relevant for (b)).
            // A weak literal match alone is not a follow-up. Searching it together with the previous
            // question and answer scores the previous topic's passages against a question that
            // contains the previous answer's own words, so they always "won": after a few turns a
            // new question the pack covers only weakly was answered about the earlier topic. The
            // contextual search runs only for a question that reads as dependent on the conversation
            // (embedding classifier) or is too short to stand alone ("and for children?").
            val weak = literalBest < FOLLOW_UP_WEAK_RERANK
            if (dependent || (weak && shortElliptical)) {
                // Ask SOP conversation: chained follow-ups ("and for a newborn?", "what dose?") are
                // rewritten from the last 2 exchanges, not just the last question (behind the flag).
                val prevPrev = if (ArogyaFlags.sopFollowUpContext)
                    acceptedHistory.filter { it.role == "user" }.map { it.content.trim() }
                        .filter { it.isNotBlank() && !it.equals(query.trim(), ignoreCase = true) }
                        .takeLast(2).firstOrNull().orEmpty() else ""
                // 8 Nov 2026: the follow-up's cleaned reading and English terms too ("aur bukhaar ho to?" →
                // "bukhar" → fever), not only its raw words; the decision rules above are unchanged.
                val followUpClean = listOfNotNull(cleaned?.takeIf { it.changed }?.text) + languageViews
                val contextual = (listOf(query.trim()) + followUpClean + listOf(basis, prevPrev, previousAnswer.take(FOLLOW_UP_ANSWER_CHARS)))
                    .filter { it.isNotBlank() }.distinct().joinToString(" ")
                val second = runCatching { repository.searchPhase14(contextual, queryPlan.clauses, candidateWork, denseEnabled) }.getOrNull()
                val secondBest = second?.bestRerank
                val margin = if (weak) FOLLOW_UP_MARGIN else 0f
                val use = second != null && second.hits.isNotEmpty() && secondBest != null &&
                    secondBest >= literalBest + margin
                Log.i(
                    TAG,
                    "KB_FOLLOW_UP weak=$weak short=$shortElliptical dependent=$dependent literalBest=${"%.2f".format(Locale.US, literalBest)} contextualBest=" +
                        "${secondBest?.let { "%.2f".format(Locale.US, it) } ?: "-"} used=$use previous=${previousUser.take(80)}"
                )
                if (use) {
                    phase14 = second
                    followUpOf = previousUser
                }
            }
        }
        lastRewriteOf = currentRewrite?.let { query.trim() to it.parts.joinToString(" ") }
        lastResolution = query.trim() to (followUpOf?.let { "${lastResolution?.takeIf { r -> r.first == it }?.second ?: it} ${query.trim()}".take(400) } ?: query.trim())
        if (phase14 != null) {
            val tEv = System.currentTimeMillis()
            val main14: GenericKnowledgePackRepository.Phase14Result = phase14
            val found = if (ArogyaFlags.perConcernSearch && followUpOf == null && evidenceCharBudget >= PASSAGE_MODE_MIN_CHARS)
                runCatching { withSymptomEvidence(query, queryPlan, main14, evidenceCharBudget, denseEnabled) }
                    .onFailure { Log.w(TAG, "KB_SYMPTOM_SEARCH_FAILED ${it.javaClass.simpleName}:${it.message}") }.getOrDefault(main14)
            else main14
            return phase14Attachment(query, queryPlan, found, evidenceCharBudget, started, followUpOf).also {
                Log.i(TAG, "POCKET_RAG_EVIDENCE_MS total=${System.currentTimeMillis() - tEv} hits=${found.hits.size}")
            }
        }

        // The Phase 1.4 search failed with an error. Retry once in its full-text-only mode (same
        // generic engine, no semantic lane). There is no older pattern-based fallback path: if this
        // also fails, the turn has no KB evidence and the answer says so.
        val lexicalOnly = runCatching { repository.searchPhase14(resolvedRetrievalQuery, queryPlan.clauses, candidateWork, false) }
            .onFailure { Log.w(TAG, "KB_PHASE14_LEXICAL_RETRY_FAILED ${it.javaClass.simpleName}:${it.message}") }
            .getOrNull()
        if (lexicalOnly != null) {
            Log.w(TAG, "KB_PHASE14_LEXICAL_RETRY used=true")
            return phase14Attachment(query, queryPlan, lexicalOnly, evidenceCharBudget, started, null)
        }
        lookupState = LookupState(attempted = true, ready = true, confident = false)
        Log.w(TAG, "KB_PHASE14_NO_RESULT query=${query.take(100)}")
        return null
    }

    /**
     * Phase 1.4 evidence packet: coverage-reserved order from the repository (one block per
     * covered question part first), then remaining evidence by final rank within the budget.
     * Reserved blocks are shortened rather than dropped when the budget is tight.
     */
    private suspend fun phase14Attachment(
        query: String,
        queryPlan: GenericKbQueryPlan,
        result: GenericKnowledgePackRepository.Phase14Result,
        evidenceCharBudget: Int,
        started: Long,
        followUpOf: String? = null
    ): BaselineContextAttachment? {
        turnCandidates = result.hits
        lookupState = LookupState(
            attempted = true,
            ready = true,
            confident = result.hits.isNotEmpty(),
            topSimilarity = result.topSimilarity,
            packId = result.packId,
            displayName = result.displayName
        )
        if (result.hits.isEmpty()) {
            Log.i(TAG, "KB_PHASE14_NO_EVIDENCE query=${query.take(100)}")
            return null
        }
        var valueConflict = ""
        // R10-lite companion: extra 1600-char windows (passages past the narrow set) for the
        // cloud packet, cut from the same search (no second search). Empty unless passage mode.
        var wideExtraText = ""
        var wideExtraRefs = ""
        val packed = mutableListOf<GenericKnowledgePackRepository.Hit>()
        val sourceRefs = mutableListOf<String>()
        val blocks = mutableListOf<String>()
        var used = 0
        // Part C2: gold pinning. A strong/medium verified-answer match pins its guide
        // passages at the top of the packet; a strong match also adds reference notes
        // (kb_gold_reference). Trailing normal blocks are dropped so the prompt stays
        // within today's size. The answer is still generated live from guide passages.
        val goldScored = if (ArogyaVerifiedAnswers.count() == 0) null else runCatching {
            val vec = repository.embedForGold(ArogyaVerifiedAnswers.QUERY_PREFIX + query)
            ArogyaVerifiedAnswers.match(query, queryVec = vec)
        }.getOrNull()
        // 11 Nov 2026 (AR): strong matches only. Medium matches (score 0.65–0.80) pinned another topic's passages at
        // the top of the evidence on 11 of 33 desktop questions: "प्रसव के बाद माँ को बहुत खून बह रहा है" pinned "continue
        // IFA after delivery", "newborn not crying after birth" pinned "newborn feels cold", "cough, fast breathing and
        // fever" pinned the fever answer. The answer then described the pinned topic (the TB question's pneumonia
        // answer is this kind of failure). Without a pin the passages are the ones search and re-ranking chose.
        val goldPin = if (goldScored != null && goldScored.strong)
            runCatching { repository.goldPassages(goldScored.item.sources) }.getOrDefault(emptyList())
        else emptyList()
        val goldRef = if (goldScored != null && goldScored.strong && goldPin.isNotEmpty())
            ArogyaGoldPin.referenceBlock(goldScored.item.aEn) else ""
        val pinnedChunkIds = goldPin.map { it.chunkId }.toSet()
        goldPin.forEach { g ->
            val sourceId = "S${packed.size + 1}"
            val ref = ArogyaGoldPin.sourceRef(sourceId, g.title, g.locator, g.url)
            val body = "\u2022 ${g.text}"
            packed += GenericKnowledgePackRepository.Hit(
                chunkId = g.chunkId, originalChunkId = null, docId = g.docId,
                sourceName = null, pageNumber = null, locator = g.locator,
                headingContext = null, unitKind = null, content = body,
                similarity = 1f, rankScore = 1f, smallFragment = false, sourceTitle = g.title,
                sourceUrl = g.url.ifBlank { null }
            )
            sourceRefs += ref
            val block = "Source ID: $sourceId\nSource: $ref\n$body"
            blocks += block
            used += block.length + 2
        }
        // Normal-block chars before gold trimming (key-facts branch sets the exact figure).
        var goldNormalBefore = 0
        // Key facts: the sentences of the top passages that answer the question (cross-encoder
        // scored). A small answer model reads ~1,300 characters of exactly relevant text instead of
        // several full pages. Falls back to whole passages when no fact could be selected.
        val keyFacts = selectKeyFacts(query, queryPlan, result, evidenceCharBudget)
        // Relevance of the turn: the best sentence's re-ranker score when sentences were scored by the
        // re-ranker (more precise than the best whole passage: a passage about "passport numbers of
        // the buyer's representatives" scored 1.8 for "how do I apply for a passport", its best
        // sentence 0.7); otherwise the best passage.
        val relevance = if (keyFactsScoredByModel && keyFacts.isNotEmpty())
            minOf(keyFacts.maxOf { it.score }, result.bestRerank ?: Float.MAX_VALUE) else result.bestRerank
        val calculation = currentRewrite?.calc?.let { calc ->
            KbCalculator.calculate(calc, keyFacts.map { it.text })
        }
        if (keyFacts.isNotEmpty()) {
            val grouped = GenericKbKeyFacts.byPassage(keyFacts)
            // Evidence form follows the model's reading room, not a setting: with room for ~3K characters
            // (Gemma 3n / Gemma 4 E2B on a 4K context) the model reads the best passages whole, PocketSage
            // style (each cut to ~900 characters around its best sentence, plus any best sentence outside
            // them); with less room it reads only the key sentences.
            val windows = if (evidenceCharBudget >= PASSAGE_MODE_MIN_CHARS)
                (if (widePassages) GenericKbKeyFacts.passageWindows(result.hits.take(keyFactPassageCount(queryPlan) + concernExtraCount).map { it.content }, keyFacts,
                    count = CHECK_PASSAGES, maxChars = ONLINE_WINDOW_CHARS)
                else GenericKbKeyFacts.passageWindows(result.hits.take(keyFactPassageCount(queryPlan) + concernExtraCount).map { it.content }, keyFacts,
                    factPassagesOnly = ArogyaFlags.factPassagesOnly))
            else null
            // 9 Nov 2026: which passages the model reads (page, size, end), so a phone answer can be replayed.
            windows?.let { ws -> Log.i(TAG, "KB_EVIDENCE_WINDOWS " + ws.joinToString(" | ") { (p, t) ->
                "${sourceLabel(result.hits[p]).take(60).replace('\n', ' ')} chars=${t.length} end=\"${t.takeLast(50).replace('\n', ' ')}\"" }) }
            // 12 Nov 2026 (AV): during a question-file run, the exact passage text the model reads (for review; off otherwise).
            if (ArogyaFlags.logEvidenceText) windows?.forEachIndexed { i, (p, t) ->
                t.chunked(3000).forEachIndexed { c, part -> Log.i(TAG, "KB_EVIDENCE_TEXT w=${i + 1} part=${c + 1} src=${sourceLabel(result.hits[p]).take(60).replace('\n', ' ')} :: ${part.replace('\n', ' ')}") }
            }
            // Passages in the order of their best fact; the calculation goes with the first fact that states a percentage.
            val passageOrder = windows?.map { it.first } ?: keyFacts.map { it.passage }.distinct()
            val windowText = windows?.toMap().orEmpty()
            val calcPassage = keyFacts.firstOrNull { KbCalculator.percentages(it.text).isNotEmpty() }?.passage ?: passageOrder.first()
            // Part C2: normal blocks pack into a temp list first; pinned duplicates are
            // skipped (they already sit at the top) and trailing blocks drop to fit.
            val normalBlocks = ArrayList<String>()
            val normalRefs = ArrayList<String>()
            val normalHits = ArrayList<GenericKnowledgePackRepository.Hit>()
            val passageByNormal = ArrayList<Int>()
            val sidByPassage = HashMap<Int, String>()
            passageOrder.forEach { p ->
                val hit = result.hits[p]
                if (hit.chunkId in pinnedChunkIds) return@forEach
                val sourceId = "S${packed.size + normalBlocks.size + 1}"
                sidByPassage[p] = sourceId
                passageByNormal += p
                val label = sourceLabel(hit).trim().ifBlank { hit.sourceName.orEmpty().ifBlank { hit.docId } }
                val sourceRef = buildString {
                    append(sourceId).append(" :: ").append(label.replace('\n', ' ').take(500))
                    hit.sourceUrl?.takeIf { it.startsWith("http") }?.let { append(" | ").append(it) }
                }
                val body = buildString {
                    val whole = windowText[p]
                    if (whole != null) append("\u2022 ").appendLine(whole)
                    else grouped[p].orEmpty().forEach { append("\u2022 ").appendLine(it.text) }
                    if (calculation != null && p == calcPassage) appendLine(calculation.describe())
                }.trim()
                val block = "Source ID: $sourceId\nSource: $sourceRef\n$body"
                normalHits += hit.copy(content = body)
                normalRefs += sourceRef
                normalBlocks += block
            }
            goldNormalBefore = normalBlocks.sumOf { it.length + 2 }
            val keptNormals = ArogyaGoldPin.trimNormal(used, normalBlocks, goldNormalBefore)
            repeat(keptNormals.size) { i ->
                packed += normalHits[i]
                sourceRefs += normalRefs[i]
                blocks += normalBlocks[i]
                used += normalBlocks[i].length + 2
            }
            // Documents that state different values of the same kind: the answer must give each with
            // its source instead of copying the first one. S-ids follow the pinned layout
            // (a skipped duplicate already sits at the top under its pinned id); statements
            // from trimmed passages are dropped so every cited S-id has its block.
            val keptPassages = passageByNormal.take(keptNormals.size).toSet()
            val sidForConflict: (Int) -> String = { p ->
                sidByPassage[p] ?: run {
                    val gi = goldPin.indexOfFirst { it.chunkId == result.hits[p].chunkId }
                    if (gi >= 0) "S${gi + 1}" else "S?"
                }
            }
            valueConflict = GenericKbKeyFacts.conflict(keyFacts, { p -> result.hits[p].sourceName ?: result.hits[p].docId })
                ?.let { c -> c.statements
                    .filter { (p, _) -> p in keptPassages || result.hits[p].chunkId in pinnedChunkIds }
                    .joinToString("; ") { (p, v) -> "${sidForConflict(p)}: $v" } }.orEmpty()
            if (ArogyaFlags.r10WideCloud && windows != null) {
                runCatching {
                    val narrowSet = windows.map { it.first }.toSet()
                    val wideWindows = GenericKbKeyFacts.passageWindows(
                        result.hits.take((ONLINE_PASSAGES + 2).coerceAtMost(result.hits.size)).map { it.content },
                        keyFacts, count = ONLINE_PASSAGES, maxChars = ONLINE_WINDOW_CHARS
                    )
                    val extra = wideWindows.filter { it.first !in narrowSet && result.hits[it.first].chunkId !in pinnedChunkIds }
                    if (extra.isNotEmpty()) {
                        val refs = mutableListOf<String>()
                        val blks = extra.mapIndexed { i, (p, text) ->
                            val hit = result.hits[p]
                            val sid = "S${packed.size + i + 1}"
                            val label = sourceLabel(hit).trim().ifBlank { hit.sourceName.orEmpty().ifBlank { hit.docId } }
                            val ref = buildString {
                                append(sid).append(" :: ").append(label.replace('\n', ' ').take(500))
                                hit.sourceUrl?.takeIf { it.startsWith("http") }?.let { append(" | ").append(it) }
                            }
                            refs += ref
                            "Source ID: $sid\nSource: $ref\n\u2022 $text"
                        }
                        wideExtraText = blks.joinToString("\n\n")
                        wideExtraRefs = refs.joinToString("\n")
                        Log.i(TAG, "KB_WIDE_EXTRA blocks=${blks.size} chars=${wideExtraText.length}")
                    }
                }.onFailure { Log.w(TAG, "KB_WIDE_EXTRA_FAILED ${it.message}") }
            }
            Log.i(TAG, "KB_KEY_FACTS mode=${if (windows != null) "passages" else "sentences"} facts=${keyFacts.size} passages=${passageOrder.size} " +
                "chars=${blocks.sumOf { it.length }} " +
                "calc=${calculation?.describe()?.take(160) ?: "-"} best=${keyFacts.firstOrNull()?.score?.let { "%.2f".format(Locale.US, it) } ?: "-"}")
        } else result.hits.forEachIndexed { index, hit ->
            if (hit.chunkId in pinnedChunkIds) return@forEachIndexed
            val sourceId = "S${packed.size + 1}"
            val label = sourceLabel(hit).trim().ifBlank { hit.sourceName.orEmpty().ifBlank { hit.docId } }
            val sourceRef = buildString {
                append(sourceId).append(" :: ").append(label.replace('\n', ' ').take(500))
                hit.sourceUrl?.takeIf { it.startsWith("http") }?.let { append(" | ").append(it) }
            }
            val note = result.notesByChunk[hit.chunkId]
            val header = buildString {
                appendLine("Source ID: $sourceId")
                appendLine("Source: $sourceRef")
                if (!note.isNullOrBlank()) appendLine("Note: $note")
            }
            val remaining = evidenceCharBudget - used - header.length - 2
            val content = hit.content.trim()
            val body = when {
                content.length <= remaining -> content
                index < result.reservedCount && remaining >= 240 -> compactAcrossContent(content, remaining)
                else -> return@forEachIndexed
            }
            val block = (header + body).trim()
            packed += hit.copy(content = body)
            sourceRefs += sourceRef
            blocks += block
            used += block.length + 2
        }
        if (packed.isEmpty()) {
            Log.i(TAG, "KB_PHASE14_NO_PACKED_EVIDENCE query=${query.take(100)} budget=$evidenceCharBudget")
            return null
        }
        lastPacked = packed.toList()
        val packetText = blocks.joinToString("\n\n")
        val clauseCoverage = result.clauseStates.map { state ->
            val sourceIds = packed.mapIndexedNotNull { i, hit ->
                if (state.clauseId in result.clauseIdsByChunk[hit.chunkId].orEmpty()) "S${i + 1}" else null
            }.toSet()
            val finalState = if (sourceIds.isEmpty()) GenericKbCoverageState.UNSUPPORTED else state.state
            GenericKbClauseCoverage(state.clauseId, finalState, sourceIds)
        }
        val coverageText = clauseCoverage.joinToString("\n") { c ->
            "${c.clauseId}|${c.state.name}|${c.sourceIds.sorted().joinToString(",")}"
        }
        pendingEvidenceWindow = packed
        pendingEvidencePackId = result.packId
        pendingEvidenceQuery = query.trim().lowercase(Locale.ROOT)
        Log.i(
            TAG,
            "KB_PHASE14_CONTEXT pack=${result.packId} packed=${packed.size}/${result.hits.size} reserved=${result.reservedCount} " +
                "chars=${packetText.length} budget=$evidenceCharBudget coverage=${clauseCoverage.joinToString(",") { "${it.clauseId}:${it.state}" }} " +
                "sources=${packed.joinToString(",") { it.chunkId.take(10) }} elapsed=${System.currentTimeMillis() - started}ms"
        )
        if (goldPin.isNotEmpty()) {
            val normalChars = blocks.drop(goldPin.size).sumOf { it.length + 2 }
            val before = if (goldNormalBefore > 0) goldNormalBefore else normalChars
            Log.i(TAG, "AROGYA_GOLD pin id=${goldScored?.item?.id} strong=${goldScored?.strong} " +
                "pinned=${goldPin.size} tokens_before=${ArogyaGoldPin.estimateTokens(before)} " +
                "tokens_after=${ArogyaGoldPin.estimateTokens(packetText.length + goldRef.length)}")
        }
        return BaselineContextAttachment(
            sourceId = "knowledge_pack:${result.packId}",
            text = packetText,
            priority = 95,
            attributes = mapOf(
                "grounding_required" to "true",
                "knowledge_pack" to "generic",
                "knowledge_pack_id" to result.packId,
                "knowledge_pack_name" to result.displayName,
                "kb_top_similarity" to String.format(Locale.US, "%.4f", result.topSimilarity),
                "kb_retrieval_mode" to "PHASE14_CENTRED_CALIBRATED_MULTILANE",
                "kb_retrieval_primary" to "literal_first",
                "kb_retrieval_view_count" to queryPlan.clauses.size.toString(),
                "kb_query_intent" to "CLAUSE_AWARE",
                "kb_retrieval_views" to queryPlan.retrievalQueries.joinToString(" || ").take(1_500),
                "kb_evidence_char_budget" to evidenceCharBudget.toString(),
                "kb_resolved_user_query" to query.trim().take(1_800),
                "kb_source_refs" to sourceRefs.joinToString("\n"),
                "kb_wide_extra" to wideExtraText,
                "kb_wide_extra_refs" to wideExtraRefs,
                "kb_gold_reference" to goldRef,
                "kb_primary_source_ref" to sourceRefs.firstOrNull().orEmpty().take(700),
                "kb_record_ids" to packed.joinToString(",") { it.chunkId }.take(1_500),
                "kb_current_record_ids" to packed.joinToString(",") { it.chunkId }.take(1_500),
                "kb_backfill_count" to "0",
                "kb_evidence_packet_chars" to packetText.length.toString(),
                "kb_evidence_complete_packet" to "true",
                "kb_coverage_requested" to queryPlan.clauses.size.toString(),
                "kb_coverage_matched" to clauseCoverage.count { it.state == GenericKbCoverageState.SUPPORTED }.toString(),
                "kb_coverage_complete" to clauseCoverage.all { it.state == GenericKbCoverageState.SUPPORTED }.toString(),
                "kb_clause_coverage" to coverageText,
                "kb_phase14_trace" to result.trace.take(1_500),
                "kb_follow_up_of" to followUpOf.orEmpty().take(600),
                "kb_best_relevance" to (relevance?.let { String.format(Locale.US, "%.3f", it) } ?: ""),
                "kb_key_facts" to keyFacts.size.toString(),
                "kb_value_conflict" to valueConflict,
                // The pack's own long forms of abbreviations in the question ("AON = acceptance of necessity").
                "kb_terms" to runCatching { repository.abbreviationTerms(query) }.getOrDefault(emptyList())
                    .joinToString("; ") { (abbr, long) -> "$abbr = $long" },
                "kb_search_rewrite" to currentRewrite?.search.orEmpty().take(300)
            )
        )
    }

    /** A question asking several things gets two more passages per extra part to choose from. */
    private fun keyFactPassageCount(queryPlan: GenericKbQueryPlan): Int {
        val partCount = maxOf(currentRewrite?.parts?.size ?: 1, queryPlan.clauses.size).coerceIn(1, 3)
        if (widePassages) return CHECK_PASSAGES + 2 * (partCount - 1)
        // R1-lite: six passages compete for key facts when the switch is on, else five.
        val base = if (ArogyaFlags.r1WideEvidence) KEY_FACT_PASSAGES + 1 else KEY_FACT_PASSAGES
        return base + 2 * (partCount - 1)
    }

    /**
     * Key facts for the question; a question with several parts gets its share of the budget per
     * part so no part is crowded out. Empty when the passages could not be split or scored.
     */
    private suspend fun selectKeyFacts(
        query: String,
        queryPlan: GenericKbQueryPlan,
        result: GenericKnowledgePackRepository.Phase14Result,
        evidenceCharBudget: Int
    ): List<GenericKbKeyFacts.Fact> = runCatching {
        keyFactsScoredByModel = false
        val passageCount = keyFactPassageCount(queryPlan)
        val passages = result.hits.take(passageCount).map { it.content }
        val passageScores = result.hits.take(passageCount).map { it.rankScore }
        if (passages.isEmpty()) return@runCatching emptyList()
        // Whole-passage mode: sentences only anchor the passage windows, so they are scored by word
        // overlap (free) instead of the cross-encoder. Phone log: the cross-encoder takes ~0.4 s per
        // passage pair; scoring 50+ sentences as well added several seconds per answer. Desktop:
        // 180 vs 183 of 198 questions with the answer in the evidence.
        val scorer = if (evidenceCharBudget >= PASSAGE_MODE_MIN_CHARS) null else repository.keyFactScorer()
        keyFactsScoredByModel = scorer != null
        val budget = minOf(GenericKbKeyFacts.DEFAULT_BUDGET, evidenceCharBudget)
        val question = result.rerankQuestion.ifBlank { query }
        // Parts: the model's one-search-per-thing split when it made one, else the planner's clauses.
        val parts = currentRewrite?.parts?.takeIf { it.size > 1 }
            ?: queryPlan.clauses.map { it.literal.trim() }.filter { it.isNotBlank() }
        val modelParts = currentRewrite?.parts?.takeIf { it.size > 1 }
        if (parts.size <= 1) {
            GenericKbKeyFacts.select(question, passages, scorer, budget, passageScores = passageScores)
        } else if (modelParts != null && scorer == null) {
            // 9 Nov: one list of facts per problem the model named, taken in turns (first fact of each problem,
            // then the second…), so the windows the model reads cover every problem. A part's facts must contain
            // its own most specific word (guide-wide rarity, ignoring wording shared with the other parts).
            val share = budget / modelParts.size
            ArogyaConcerns.interleave(modelParts.map { part ->
                GenericKbKeyFacts.select(part, passages, null, share, GenericKbKeyFacts.MAX_FACTS / 2 + 1, passageScores,
                    requireFocus = true, siblings = modelParts.filter { it != part })
            }) { f -> "${f.passage}:${f.index}" }
        } else {
            val share = budget / parts.size
            val out = mutableListOf<GenericKbKeyFacts.Fact>()
            parts.forEach { part ->
                val partQuestion = (listOf(part) + repository.aliasExpansions(part)).joinToString(" ")
                GenericKbKeyFacts.select(partQuestion, passages, scorer, share, GenericKbKeyFacts.MAX_FACTS / 2 + 1, passageScores)
                    .forEach { f -> if (out.none { it.passage == f.passage && it.index == f.index }) out += f }
            }
            out
        }
    }.map { facts -> ArogyaConcerns.prioritise(concernFacts, facts) { f -> "${f.passage}:${f.index}" } }
        .onFailure { Log.w(TAG, "KB_KEY_FACTS_FAILED ${it.javaClass.simpleName}:${it.message}") }.getOrDefault(emptyList())

    /**
     * 9 Nov: questions with several problems, without word lists. The on-device model wrote one search line
     * per problem. The normal evidence is built first (same facts and windows as always); only a line whose
     * own problem word (guide-wide rarity, see [ArogyaConcerns.focus]) is in none of the windows the model
     * will read gets more evidence: first from the main search's lower-ranked passages, else from one short
     * search of that line (with all lines as one extra view, so a problem described only inside another
     * one's passages is found: "if the child vomits, wait 10 minutes" sits in the diarrhoea guidance). Its
     * passages follow the normal ones and its best fact is put first so its passage gets a window. Nothing
     * changes when every line is already covered or the model gave one line.
     */
    private suspend fun withSymptomEvidence(
        query: String,
        queryPlan: GenericKbQueryPlan,
        found: GenericKnowledgePackRepository.Phase14Result,
        evidenceCharBudget: Int,
        denseEnabled: Boolean
    ): GenericKnowledgePackRepository.Phase14Result {
        val lines = currentRewrite?.parts?.takeIf { it.size > 1 } ?: return found
        val baseCount = keyFactPassageCount(queryPlan)
        val baseFacts = selectKeyFacts(query, queryPlan, found, evidenceCharBudget)
        val windows = GenericKbKeyFacts.passageWindows(found.hits.take(baseCount).map { it.content }, baseFacts).map { it.second }
        val missing = ArogyaConcerns.uncovered(lines, windows).take(ArogyaConcerns.MAX_EXTRA_SEARCHES)
        if (missing.isEmpty()) { Log.i(TAG, "KB_PARTS covered=${lines.size}"); return found }
        val head = found.hits.take(baseCount).map { it.chunkId }.toSet()
        val context = listOf(lines.joinToString(" "))
        val byLine = missing.map { line ->
            val fromMain = ArogyaConcerns.fromMain(line, lines, found.hits.drop(baseCount)) { it.content }
            if (fromMain.isNotEmpty()) {
                Log.i(TAG, "KB_PART_EVIDENCE focus=${ArogyaConcerns.focus(line, lines)} from=main line=${line.take(80)}")
                return@map line to fromMain
            }
            val r = runCatching { repository.searchPhase14(line, GenericKbQueryPlanner.plan(line, line).clauses, ArogyaConcerns.SEARCH_POOL, denseEnabled, context) }.getOrNull()
            val ok = (r?.bestRerank ?: Float.NEGATIVE_INFINITY) >= ArogyaConcerns.MIN_RERANK
            Log.i(TAG, "KB_PART_SEARCH focus=${ArogyaConcerns.focus(line, lines)} best=${r?.bestRerank?.let { "%.2f".format(Locale.US, it) } ?: "-"} used=$ok line=${line.take(80)}")
            line to (if (ok) ArogyaConcerns.pick(line, lines, r?.hits.orEmpty(), { it.content }, { it.chunkId }, head) else emptyList())
        }
        val merged = ArogyaConcerns.merge(found.hits, byLine.map { it.second }, { it.chunkId }, baseCount)
        val extraCount = byLine.flatMap { it.second }.map { it.chunkId }.distinct().count { it !in head }.coerceAtMost(4)
        if (extraCount == 0) return found
        val passages = merged.take(baseCount + extraCount).map { it.content }
        val scores = merged.take(baseCount + extraCount).map { it.rankScore }
        val budget = minOf(GenericKbKeyFacts.DEFAULT_BUDGET, evidenceCharBudget)
        val add = byLine.mapNotNull { (line, picked) ->
            val ids = picked.map { it.chunkId }.toSet()
            GenericKbKeyFacts.select(line, passages, null, budget / 2, 3, scores, requireFocus = true, siblings = lines.filter { it != line })
                .firstOrNull { f -> merged[f.passage].chunkId in ids }
        }
        Log.i(TAG, "KB_PARTS missing=${missing.size} extraPassages=$extraCount forcedFacts=${add.size}")
        if (add.isEmpty()) return found
        concernFacts = add
        concernExtraCount = extraCount
        return found.copy(hits = merged)
    }

    override suspend fun validate(
        query: String,
        answer: String,
        provider: AndroBaselineRuntime.Provider,
        attachments: List<BaselineContextAttachment>,
        acceptedHistory: List<AndroChatMessage>
    ): BaselineValidationResult {
        if (provider != AndroBaselineRuntime.Provider.LOCAL &&
            provider != AndroBaselineRuntime.Provider.CLOUD) return BaselineValidationResult.ACCEPT
        val sources = attachments.filter {
            it.attributes["grounding_required"] == "true" &&
                it.attributes["knowledge_pack"] == "generic"
        }
        if (sources.isEmpty()) return BaselineValidationResult.ACCEPT
        val evidence = sources.joinToString("\n\n") { it.text }.trim()
        if (evidence.isBlank()) return BaselineValidationResult.reject("generic_kb_source_missing")

        val clean = answer.trim()
        if (clean.isBlank()) return BaselineValidationResult.reject("generic_kb_blank_answer")

        // Source IDs are app-owned labels attached to the exact evidence packet. A model
        // may cite only IDs that were actually supplied for this turn. This prevents a
        // fluent Local/Cloud answer from fabricating an S-number while keeping the
        // existing semantic grounding validator as the authority for claim support.
        val allowedSourceIds = sources
            .flatMap { it.attributes["kb_source_refs"].orEmpty().lineSequence().toList() }
            .mapNotNull { Regex("""^\s*(S\d+)\s*::""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.uppercase() }
            .toSet()
        val citedSourceIds = Regex("""\[(S\d+)]""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .map { it.groupValues[1].uppercase() }
            .toSet()
        val unknownSourceIds = citedSourceIds - allowedSourceIds
        if (unknownSourceIds.isNotEmpty()) {
            return BaselineValidationResult.reject("generic_kb_unknown_source_id:${unknownSourceIds.sorted().joinToString(",")}")
        }

        // Protocol/privacy format guards only. These do not classify the user's topic.
        val privateLeak = Regex(
            "(?i)<source\\b|\\[SELECTED KNOWLEDGE PACK|knowledge_pack_id|kb_record_ids|grounding_required|\\bEvidence\\s*\\d+\\b|\\bretrieved chunk\\b|\\bcontext chunk\\b|\\[Evidence source"
        ).containsMatchIn(clean)
        if (privateLeak) return BaselineValidationResult.reject("generic_kb_private_context_leak")

        // Reject obvious unfilled form/template output regardless of domain.
        if (Regex("_{4,}|\\.{5,}|\\[\\s*blank\\s*]", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
            return BaselineValidationResult.reject("generic_kb_unfilled_template_fragment")
        }

        val intent = sources.firstNotNullOfOrNull { source ->
            source.attributes["kb_query_intent"]?.let { runCatching { SemanticQueryIntent.valueOf(it) }.getOrNull() }
        } ?: SemanticQueryIntent.GENERAL

        val coverageIncomplete = sources.any { source ->
            source.attributes["kb_coverage_complete"].equals("false", ignoreCase = true) ||
                source.attributes["kb_evidence_complete_packet"].equals("false", ignoreCase = true)
        }
        if (coverageIncomplete) {
            Log.i(TAG, "GENERIC_KB_COVERAGE_ADVISORY intent=$intent action=continue_local")
        }

        // Keep post-generation validation objective. Procedure quality/completeness
        // is owned by retrieval + Gemma, not by heuristic surface-shape scoring.

        // Grounded exact-value claims must be visible in the evidence packet. This
        // extractor is intentionally domain-neutral: it covers identifiers, dates,
        // currency, percentages, ranges, and numeric values. Leading list
        // ordinals and app-owned [S#] citations are removed before extraction.
        val answerForClaims = clean.lineSequence().joinToString("\n") { line ->
            line.replaceFirst(Regex("""^\s*\d{1,2}[.)]\s+"""), "")
        }.replace(Regex("""\[(?:S\d+)]""", RegexOption.IGNORE_CASE), "")

        fun normalizedExactClaims(text: String): Set<String> {
            val patterns = listOf(
                Regex("""(?i)\b[A-Z]{2,}[A-Z0-9./_-]*\d[A-Z0-9./_-]*\b"""),
                Regex("""\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b|\b\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4}\b"""),
                Regex("""(?i)(?:₹|\$|€|£)\s?\d[\d,]*(?:\.\d+)?"""),
                Regex("""\b\d+(?:[.,]\d+)?%\b"""),
                Regex("""\b\d+(?:[.,]\d+)?(?:\s*[-–]\s*\d+(?:[.,]\d+)?)?\b"""),
                Regex("""\b\d+(?:\.\d+){1,5}\b"""),
                Regex("""\b(?:19|20)\d{2}\b""")
            )
            return patterns.flatMap { regex -> regex.findAll(text).map { it.value }.toList() }
                .map { it.lowercase(Locale.US).replace(Regex("""\s+"""), "") }
                .filter { it.isNotBlank() }
                .toSet()
        }
        val exactClaims = normalizedExactClaims(answerForClaims)
        val evidenceNormalized = evidence.lowercase(Locale.US).replace(Regex("""\s+"""), "")
        val queryNormalized = query.lowercase(Locale.US).replace(Regex("""\s+"""), "")
        val unsupportedExact = exactClaims.filter { claim ->
            claim.isNotBlank() && !evidenceNormalized.contains(claim) && !queryNormalized.contains(claim)
        }
        // A calculation may introduce a derived result that is absent from the source and
        // the user's input, but only the RIGHT-HAND result of an explicit equation gets
        // that exception. Every exact numeric/identifier operand on the left-hand side
        // must already be grounded in KB evidence or supplied by the user. This prevents
        // an invented procurement rate from being smuggled through merely by adding '='.
        val derivedClaims = answerForClaims.lineSequence().flatMap { line ->
            val eq = line.indexOf('=')
            if (eq <= 0 || eq >= line.lastIndex) return@flatMap emptySequence<String>()
            val lhs = line.substring(0, eq)
            val rhs = line.substring(eq + 1)
            val lhsUnsupported = normalizedExactClaims(lhs).filter { claim ->
                !evidenceNormalized.contains(claim) && !queryNormalized.contains(claim)
            }
            if (lhsUnsupported.isNotEmpty()) emptySequence()
            else normalizedExactClaims(rhs).asSequence()
        }.toSet()
        val unsupportedNonDerived = unsupportedExact.filterNot { it in derivedClaims }
        if (unsupportedNonDerived.isNotEmpty()) {
            return BaselineValidationResult.reject("generic_kb_unsupported_exact_claim:${unsupportedNonDerived.first()}")
        }

        // V14 simple-local-RAG policy: retrieval owns relevance; the local model owns
        // synthesis. Post-generation validation is limited to objective integrity checks
        // above. Embedding similarity is telemetry, never a binary answer-quality gate.
        if (pendingEvidenceWindow.isNotEmpty()) {
            acceptedEvidenceWindow = pendingEvidenceWindow
            acceptedEvidencePackId = pendingEvidencePackId
            Log.i(TAG, "GENERIC_KB_ACCEPTED_SOURCE_WINDOW pack=${acceptedEvidencePackId.take(24)} query=${pendingEvidenceQuery.take(80)} chunks=${acceptedEvidenceWindow.size}")
        }
        Log.i(TAG, "GENERIC_KB_SIMPLE_ACCEPT provider=$provider chars=${clean.length}")
        return BaselineValidationResult.ACCEPT
    }
}
