package com.example.arfas

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown by a local answerer whose generation degenerated (decoder loop): never an answer. */
class PocketRagDegenerateOutput(val reason: String) : RuntimeException(reason)

interface PocketRagLocalAnswerer {
    suspend fun generate(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        maxOutputTokens: Int,
        recoveryMode: Boolean = false
    ): String?
}

interface PocketRagCloudAnswerer {
    suspend fun generate(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        localDraft: String?,
        reason: String,
        maxOutputTokens: Int
    ): String?
}

class PocketRagEngine(
    private val local: PocketRagLocalAnswerer,
    private val cloud: PocketRagCloudAnswerer,
    private val qualityGate: PocketRagQualityGate,
    private val sessions: PocketRagSessionStore,
    private val canUseCloud: () -> Boolean,
    private val contextWindowTokens: () -> Int = { 4_096 },
    private val localTimeoutMs: Long = 20_000L,
    private val localRecoveryTimeoutMs: Long = 12_000L,
    /**
     * False: no caller-side wall-clock deadline. The local engine's own watchdog still aborts a
     * generation that stalls (no first token within its prompt-scaled budget, or no new token for
     * its idle budget), but a healthy answer that is still being written is never discarded.
     */
    private val callerTimeoutEnabled: Boolean = true,
    /**
     * Called when a draft that was shown while being written is not accepted as it is (rejected,
     * salvaged or regenerated): the on-screen preview is cleared before the next attempt.
     */
    private val onDraftRejected: (() -> Unit)? = null,
    /** User setting "Online backup" (off by default). */
    private val onlineBackupEnabled: () -> Boolean = { false },
    /**
     * 9 Nov 2026: guide passages (source label, text) holding a sentence's words, from the whole guide (keyword
     * index). A number not in the passages the model read is kept when the guide states it elsewhere.
     */
    private val guideLookup: (suspend (String) -> List<Pair<String, String>>)? = null
) {
    companion object {
        /** Text kept from before a decoder loop must be at least this long to count as an answer. */
        const val LOOP_KEEP_MIN_CHARS = 500
        private const val TAG = "PocketRagEngine"
        /** 9 Nov 2026: the copied-guide-lines fallback says plainly what it is (was "Here is what the selected knowledge base says:"). */
        const val EXTRACTIVE_HEADER_EN = "The AI could not write a checked answer, so here are the exact lines from the guide:"
        const val EXTRACTIVE_HEADER_HI = "AI जाँचा हुआ उत्तर नहीं लिख सका, इसलिए गाइड की सटीक पंक्तियाँ:"
        /** Cloud's reply when the KB evidence does not answer a "Check online" question. */
        const val NOT_IN_EVIDENCE = "[[NOT_IN_KB]]"
        /** escalationReason: "Check online" found no answer in the KB evidence (a web search follows). */
        const val CHECK_ONLINE_NOT_IN_KB = "check_online_not_in_kb"
        private const val MIN_OUTPUT_TOKENS = 256
        /** 9 Nov 2026: prompt instructions + system text on the phone (log: reservedPromptTokens=1663). */
        private const val PROMPT_RESERVE_TOKENS = 1_700
        /** Share of a sentence's words a guide passage must hold to confirm its number. */
        private const val GUIDE_CONFIRM_OVERLAP = 0.5
        /** Kept free so the answer never meets the end of the model's window. */
        private const val OUTPUT_MARGIN_TOKENS = 256
        /**
         * Hard ceiling per question part (~230 words). The model does not reliably follow the length
         * tiers (a definition came back at 3,297 characters in 37 s on the phone); at the measured
         * ~90 characters a second a ceiling-length answer now takes ~15 s.
         */
        const val MAX_ANSWER_TOKENS_PER_PART = 400
        /**
         * Review: a cloud answer gets 20 s to first words and 60 s total, then falls
         * back to the phone (D7's 300 s stays the outer net). The log names the limit.
         */
        const val CLOUD_FIRST_TOKEN_MS = 20_000L
        /** ArogyaDoot: why the last Cloud-first turn did or did not use the cloud answer ("ok", "unavailable", "timeout:…", "rejected:…"), for the answer label. */
        @Volatile var lastCloudOutcome: String = ""
        /** Part B8: 120 s hard cap for a cloud answer (was 60 s). */
        const val CLOUD_TOTAL_MS = 120_000L
        /** Part B8: a cloud stream silent this long falls back to the phone. */
        const val CLOUD_IDLE_MS = 15_000L
        /** Length of an extractive (verbatim key facts) answer. */
        const val EXTRACTIVE_CHARS = 1400

        /**
         * Enforced partial-coverage disclosure: every question part that retrieval marked
         * UNSUPPORTED is named in an app-owned line, whatever the model wrote. This is
         * deterministic and language-independent (it does not try to detect the model's own
         * wording). Skipped only when the answer already carries the app's marker sentence,
         * which replaced a model [[NOT_IN_EVIDENCE]] marker.
         */
        fun discloseUnsupportedClauses(packet: PocketRagPacket, raw: String): String {
            // 12 Nov 2026 (AU): the generic "This part is not covered…" sentence and empty part labels are removed
            // first (they padded complete answers); the app's own line below then names any part really not covered.
            val answer = if (ArogyaFlags.dropFiller) runCatching { ArogyaAnswerFormat.dropNotCoveredFiller(raw) }.getOrDefault(raw) else raw
            val unsupported = packet.clauseCoverage.filter { it.state == GenericKbCoverageState.UNSUPPORTED }
            if (unsupported.isEmpty()) return answer
            if (!ArogyaFlags.dropFiller && answer.contains(PocketRagQualityGate.UNSUPPORTED_PART_SENTENCE)) return answer
            val clauses = GenericKbQueryPlanner.plan(packet.query, packet.query).clauses.associateBy { it.id }
            // "what should I do" names no topic: it is answered by the rest of the question, never "not covered".
            val lines = unsupported.mapNotNull { c -> clauses[c.clauseId]?.literal?.trim()?.takeIf { it.isNotBlank() && !KbReplyText.isGenericAsk(it) } }
            if (lines.isEmpty()) return answer
            return answer.trimEnd() + "\n\n" + lines.joinToString("\n") { "Not covered in the selected knowledge base: \u201c$it\u201d." }
        }
    }

    /** How Cloud takes part in a selected-KB turn (always user-chosen; never automatic). */
    enum class CloudMode {
        /** Local only (Local First). */
        NONE,
        /** Cloud First setting: Cloud answers from the same KB evidence; Local only if Cloud is unavailable or fails. */
        CLOUD_FIRST,
        /** "Check online" on a local answer: Cloud checks that answer against the KB evidence and corrects it. */
        VERIFY
    }

    /** The quality check with its own timing line (the per-question breakdown reads these). */
    private fun evalGate(query: String, text: String?, packet: PocketRagPacket, stage: String): PocketRagQualityDecision {
        val t = System.currentTimeMillis()
        val d = qualityGate.evaluate(query, text, packet)
        Log.i(TAG, "POCKET_RAG_GATE_MS stage=$stage ms=${System.currentTimeMillis() - t} accept=${d.acceptLocal} reason=${d.reason}")
        logAnswerLen(stage, text.orEmpty(), text)
        return d
    }

    /** Item 4: sentence counts as drafts pass the gate and the salvages (before/after each step). */
    /**
     * 9 Nov 2026 (phone log, "How do I make ORS at home?"): the number clean-up removed the sentences with an
     * unsupported number, then the term check failed and nothing removed that sentence, so a good answer was
     * thrown away for copied guide sentences. The two clean-ups now run one after the other (each removes only
     * the offending sentences, never adds text), up to three rounds, and the full gate must pass at the end.
     */
    /**
     * 9 Nov 2026 (phone 20:19: Gemma's "one litre of water with 50 gm of sugar and 5 gm of salt" is in the guide,
     * Induction Module page 94, which the search found for the question but did not give Gemma, so it was removed):
     * a sentence with a number the passages do not support is checked against the OTHER passages the search found
     * for this same question ([guideLookup]; never the whole guide). One that holds all of the sentence's numbers
     * with their units and at least half of its words confirms it: the passage joins the evidence as a new source,
     * the sentence cites it, and the full check runs again. Medicine doses (mg) are never confirmed this way: a dose
     * must be in the passages the model read (another row of a dose table could hold the same number for another
     * age). Nothing is generated or changed. Null when nothing was confirmed.
     */
    internal suspend fun confirmFromGuide(query: String, draft: String, packet: PocketRagPacket): Pair<String, PocketRagPacket>? {
        val lookup = guideLookup ?: return null
        if (qualityGate.unsupportedNumericClaims(query, draft, packet.evidenceText).isEmpty()) return null
        val blocks = packet.evidenceBlocks.toMutableList()
        val sources = packet.sources.toMutableList()
        val marker = Regex("\\s*\\[\\s*S\\d+(?:\\s*[,;]\\s*S\\d+)*\\s*]")
        fun words(t: String) = Regex("[\\p{L}]{3,}").findAll(t.lowercase()).map { it.value.take(5) }.toSet()
        var added = 0
        val outLines = ArrayList<String>()
        for (line in draft.lines()) {
            val outPieces = ArrayList<String>()
            for (raw in KbReplyText.SENTENCE_SPLIT.split(line)) {
                val piece = raw.trim()
                val evidenceNow = blocks.joinToString("\n\n") { it.text }
                if (piece.isBlank() || qualityGate.unsupportedNumericClaims(query, piece, evidenceNow).isEmpty()) { outPieces += piece; continue }
                val bare = piece.replace(marker, "").replace(Regex("^\\s*\\d{1,2}[.)]\\s+"), "").trim()
                val pw = words(bare)
                if (pw.size < 3 || qualityGate.unitQuantities(bare).any { it.endsWith(":mg") }) { outPieces += piece; continue }
                val candidates = runCatching { lookup(bare) }.onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
                val hit = candidates.firstOrNull { (_, text) ->
                    qualityGate.unsupportedNumericClaims(query, bare, text).isEmpty() &&
                        (words(text) intersect pw).size.toDouble() / pw.size >= GUIDE_CONFIRM_OVERLAP
                }
                if (hit == null) { outPieces += piece; continue }
                val existing = blocks.firstOrNull { it.text == hit.second }
                val sid = existing?.sourceId ?: "S${sources.size + 1}".also { id ->
                    blocks += PocketRagEvidenceBlock("G${++added}", id, hit.second)
                    sources += PocketRagSource(id, "$id :: ${hit.first}")
                }
                val lead = Regex("^\\s*\\d{1,2}[.)]\\s+").find(piece)?.value.orEmpty()
                val body = piece.removePrefix(lead).replace(marker, "").trimEnd()
                val end = body.lastOrNull()?.takeIf { it in ".!?।" }?.toString().orEmpty()
                outPieces += "$lead${body.removeSuffix(end).trimEnd()} [$sid]$end"
            }
            outLines += outPieces.filter { it.isNotEmpty() }.joinToString(" ")
        }
        val lines = outLines
        if (added == 0) return null
        Log.i(TAG, "POCKET_RAG_GUIDE_CONFIRMED passages=$added sources=${sources.drop(packet.sources.size).joinToString(" | ") { it.display.take(80) }}")
        return lines.joinToString("\n") to packet.copy(evidenceBlocks = blocks, sources = sources)
    }

    /** Confirmed against the whole guide, then checked by the full gate (and the clean-ups); null when not accepted. */
    private suspend fun guideConfirmedAnswer(key: String, query: String, draft: String, packet: PocketRagPacket, stage: String): PocketRagResult? {
        val (confirmed, wider) = runCatching { confirmFromGuide(query, draft, packet) }.getOrNull() ?: return null
        val text = if (qualityGate.evaluate(query, confirmed, wider).acceptLocal) confirmed
            else runCatching { chainedSalvage(query, confirmed, wider) }.getOrNull() ?: return null
        val extra = wider.evidenceBlocks.drop(packet.evidenceBlocks.size)
        Log.i(TAG, "POCKET_RAG_LOCAL_GUIDE_CONFIRMED_ACCEPT stage=$stage extra=${extra.size}")
        return acceptLocal(key, wider, text, "local_recovery:guide_confirmed", cloudAttempted = false)
            .copy(extraEvidence = extra, extraSources = wider.sources.drop(packet.sources.size))
    }

    internal fun chainedSalvage(query: String, draft: String, packet: PocketRagPacket): String? {
        var text = draft
        repeat(3) {
            val d = qualityGate.evaluate(query, text, packet)
            if (d.acceptLocal) return text.takeIf { it != draft }
            val next = when {
                d.reason.startsWith("unsupported_numeric_claim") -> qualityGate.salvageUnsupportedNumericSentences(query, text, packet)
                d.reason.startsWith("term_swap") -> qualityGate.salvageSwappedTerms(text, packet)
                else -> null
            }
            if (next.isNullOrBlank() || next == text) return null
            text = next
        }
        return text.takeIf { it != draft && qualityGate.evaluate(query, it, packet).acceptLocal }
    }

    private fun logAnswerLen(stage: String, before: String, after: String?) {
        val b = ArogyaAnswerFormat.sentenceCount(before)
        val a = after?.let { ArogyaAnswerFormat.sentenceCount(it) } ?: 0
        Log.i(TAG, "AROGYA_ANSWER_LEN stage=$stage before=$b after=$a salvaged=${(b - a).coerceAtLeast(0)}")
    }

    suspend fun answer(
        packet: PocketRagPacket,
        cloudMode: CloudMode = CloudMode.NONE,
        localDraftToVerify: String? = null,
        /** R10-lite: the wide (12 x 1600) packet the cloud reads; null = the cloud reads [packet]. */
        cloudPacket: PocketRagPacket? = null
    ): PocketRagResult {
        val key = packet.selection.sessionKey
        val memory = sessions.context(key)
        val maxOutput = outputBudget(packet, cloud = false)
        // The cloud reads the wide packet when there is one (Cloud First); the phone always
        // reads the narrow one. "Check online" keeps its own already-wide packet.
        val cpacket = if (cloudMode == CloudMode.CLOUD_FIRST) (cloudPacket ?: packet) else packet
        val maxCloudOutput = outputBudget(cpacket, cloud = cloudMode != CloudMode.NONE)

        if (cloudMode != CloudMode.NONE) lastCloudOutcome = ""
        if (cloudMode != CloudMode.NONE) {
            if (canUseCloud()) {
                val reason = if (cloudMode == CloudMode.VERIFY) "check_online" else "cloud_first"
                val tCloud = System.currentTimeMillis()
                // The resolver is fresh per turn, so didStream starts false: it marks first words.
                val streamFlag = cloud as? PocketRagCloudResolver
                var limitFired: String? = null
                val cloudAnswer: String? = try {
                    withTimeout(CLOUD_TOTAL_MS) {
                        coroutineScope {
                            val job = async {
                                try {
                                    cloud.generate(cpacket, memory, localDraftToVerify, reason, maxCloudOutput)?.trim()
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    Log.w(TAG, "POCKET_RAG_CLOUD_EXCEPTION mode=$cloudMode ${t.javaClass.simpleName}:${t.message}")
                                    null
                                }
                            }
                            // First-token deadline: streamed words or a finished call within 20 s,
                            // else cancel and fall back to the phone. (With streaming off, didStream
                            // stays false and a finished call counts as first tokens.)
                            val firstOk = withTimeoutOrNull(CLOUD_FIRST_TOKEN_MS) {
                                while (streamFlag != null && !streamFlag.didStream && job.isActive) delay(50)
                                true
                            } != null
                            if (!firstOk) {
                                limitFired = "first_token"
                                withTimeoutOrNull(5_000) { job.cancelAndJoin() }
                                null
                            } else {
                                // Part B8 idle limit: a stream silent for 15 s falls back to the
                                // phone (streaming turns only; a non-streaming call waits out the total).
                                val idleWatch = launch {
                                    while (job.isActive) {
                                        delay(1_000)
                                        val res = streamFlag
                                        val last = res?.lastDeltaMs ?: 0L
                                        if (job.isActive && res?.didStream == true && last > 0 &&
                                            System.currentTimeMillis() - last > CLOUD_IDLE_MS) {
                                            limitFired = "idle"
                                            Log.w(TAG, "POCKET_RAG_CLOUD_IDLE_LIMIT silentMs=${System.currentTimeMillis() - last}")
                                            runCatching { job.cancelAndJoin() }
                                        }
                                    }
                                }
                                try { job.await() } finally { idleWatch.cancel() }
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    // Our own deadline falls back to the phone; an outer deadline or a real
                    // cancellation propagates (a real cancel never falls back).
                    if (ce is TimeoutCancellationException && System.currentTimeMillis() - tCloud >= CLOUD_TOTAL_MS) {
                        limitFired = "total"
                        if (streamFlag?.didStream == true) runCatching { onDraftRejected?.invoke() }
                        null
                    } else throw ce
                }
                if (limitFired != null) {
                    val limitMs = when (limitFired) {
                        "first_token" -> CLOUD_FIRST_TOKEN_MS
                        "idle" -> CLOUD_IDLE_MS
                        else -> CLOUD_TOTAL_MS
                    }
                    Log.w(TAG, "POCKET_RAG_CLOUD_TIMEOUT mode=$cloudMode limit=$limitFired ms=$limitMs fallback=local")
                    lastCloudOutcome = "timeout:$limitFired"
                    if ((limitFired == "first_token" || limitFired == "idle") && streamFlag?.didStream == true) runCatching { onDraftRejected?.invoke() }
                }
                if (cloudMode == CloudMode.VERIFY && cloudAnswer?.contains(NOT_IN_EVIDENCE) == true) {
                    // The KB evidence does not answer it: the caller runs a live web search (labelled).
                    Log.i(TAG, "POCKET_RAG_CHECK_ONLINE_NOT_IN_KB")
                    if ((cloud as? PocketRagCloudResolver)?.didStream == true) runCatching { onDraftRejected?.invoke() }
                    return PocketRagResult(
                        "", AndroBaselineRuntime.Provider.LOCAL, emptyList(),
                        localAttempted = false, cloudAttempted = true, escalationReason = CHECK_ONLINE_NOT_IN_KB, accepted = false
                    )
                }
                if (!cloudAnswer.isNullOrBlank()) {
                    // 8 Nov 2026: online answers get the phone's safety guard too (a flipped "do not").
                    val rawOnline: String = cloudAnswer.orEmpty()
                    val onlineAnswer: String = if (ArogyaFlags.negationFlipGuard) {
                        val g = runCatching { KbReplyText.dropFlippedProhibitions(rawOnline, cpacket.evidenceText) }.getOrDefault(rawOnline to 0)
                        if (g.second > 0) Log.w(TAG, "POCKET_RAG_FLIPPED_PROHIBITION removed=${g.second} stage=cloud mode=$cloudMode")
                        g.first.ifBlank { rawOnline }
                    } else rawOnline
                    // Cloud First is grounded like a local answer (same checks). "Check online" now runs the same
                    // checks too (8 Nov): a wrong number or swapped term is repaired like a phone draft; any other
                    // objection is logged and the online answer is still shown (it is the user's explicit request).
                    val checkOnlineGate = cloudMode != CloudMode.CLOUD_FIRST && ArogyaFlags.checkOnlineChecks
                    val decision = if (cloudMode == CloudMode.CLOUD_FIRST || checkOnlineGate) evalGate(packet.query, onlineAnswer, cpacket, if (checkOnlineGate) "check_online" else "cloud_first")
                    else PocketRagQualityDecision(true, "cloud_verified")
                    if (decision.acceptLocal) { lastCloudOutcome = "ok"; return acceptCloud(key, cpacket, onlineAnswer, "$reason:${decision.reason}") }
                    // ArogyaDoot: the same repairs a phone draft gets (drop only the sentence with a number or a swapped
                    // term not in the guide) before giving up on the cloud answer.
                    if (checkOnlineGate) {
                        val repaired = when {
                            decision.reason.startsWith("unsupported_numeric_claim") -> qualityGate.salvageUnsupportedNumericSentences(packet.query, onlineAnswer, cpacket)
                            decision.reason.startsWith("term_swap") -> qualityGate.salvageSwappedTerms(onlineAnswer, cpacket)
                            else -> null
                        }
                        val use = repaired?.takeIf { it.isNotBlank() && it != onlineAnswer } ?: onlineAnswer
                        Log.i(TAG, "POCKET_RAG_CHECK_ONLINE_GATE reason=${decision.reason} repaired=${use != onlineAnswer}")
                        lastCloudOutcome = "ok"
                        return acceptCloud(key, cpacket, use, "$reason:check_online:${decision.reason.substringBefore(':')}")
                    }
                    if (cloudMode == CloudMode.CLOUD_FIRST) {
                        val repaired = when {
                            decision.reason.startsWith("unsupported_numeric_claim") -> qualityGate.salvageUnsupportedNumericSentences(packet.query, onlineAnswer, cpacket)
                            decision.reason.startsWith("term_swap") -> qualityGate.salvageSwappedTerms(onlineAnswer, cpacket)
                            else -> null
                        }
                        if (!repaired.isNullOrBlank() && repaired != onlineAnswer && evalGate(packet.query, repaired, cpacket, "cloud_salvage").acceptLocal) {
                            Log.i(TAG, "POCKET_RAG_CLOUD_SALVAGE_ACCEPT reason=${decision.reason}")
                            lastCloudOutcome = "ok"
                            return acceptCloud(key, cpacket, repaired, "$reason:cloud_salvage")
                        }
                    }
                    lastCloudOutcome = "rejected:${decision.reason.substringBefore(':')}"
                    Log.w(TAG, "POCKET_RAG_CLOUD_REJECT mode=$cloudMode reason=${decision.reason}")
                    if ((cloud as? PocketRagCloudResolver)?.didStream == true) runCatching { onDraftRejected?.invoke() }
                }
            } else {
                Log.w(TAG, "POCKET_RAG_CLOUD_UNAVAILABLE mode=$cloudMode fallback=local")
                lastCloudOutcome = "unavailable"
            }
            if (cloudMode == CloudMode.VERIFY) {
                return PocketRagResult(
                    "I couldn't reach the online AI to check this answer. Please check the internet connection and that a Groq API key is set in Settings → Online AI, then try again.",
                    AndroBaselineRuntime.Provider.LOCAL, emptyList(),
                    localAttempted = false, cloudAttempted = true, escalationReason = "check_online_unavailable", accepted = false
                )
            }
        }

        val first = runLocal(packet, memory, maxOutput, recoveryMode = false, timeoutMs = localTimeoutMs)
        val initialMarker = qualityGate.sanitizeUnsupportedMarkers(first.text)
        val localDraft = initialMarker.text
        var decision = if (first.degenerate != null) {
            PocketRagQualityDecision(false, "degenerate_output:${first.degenerate}")
        } else if (initialMarker.markerOnly) {
            PocketRagQualityDecision(false, "local_declared_insufficient")
        } else {
            evalGate(packet.query, localDraft, packet, "local_first")
        }
        Log.i(TAG, "POCKET_RAG_LOCAL_GATE reason=${decision.reason} accepted=${decision.acceptLocal} draftChars=${localDraft.length} coverage=${packet.coverageComplete}")
        if (!decision.acceptLocal) runCatching { onDraftRejected?.invoke() }
        if (!decision.acceptLocal && localDraft.isNotBlank()) {
            Log.d(TAG, "POCKET_RAG_REJECTED_DRAFT reason=${decision.reason} text='${localDraft.take(4000).replace('\n', ' ')}'")
        }
        if (decision.acceptLocal && localDraft.isNotBlank()) {
            return acceptLocal(key, packet, localDraft, decision.reason, cloudAttempted = false)
        }
        // 9 Nov 2026: a number the passages lack but the guide states elsewhere is confirmed there first.
        if (decision.reason.startsWith("unsupported_numeric_claim") && localDraft.isNotBlank()) {
            guideConfirmedAnswer(key, packet.query, localDraft, packet, "first_draft")?.let { return it }
        }
        // An otherwise grounded draft rejected only for an unsupported number: remove just that
        // sentence first (instant) instead of regenerating the whole answer.
        if (decision.reason.startsWith("unsupported_numeric_claim") && localDraft.isNotBlank()) {
            val salvaged = qualityGate.salvageUnsupportedNumericSentences(packet.query, localDraft, packet)
            if (!salvaged.isNullOrBlank() && salvaged != localDraft) {
                logAnswerLen("numeric_salvage", localDraft, salvaged)
                val salvageDecision = evalGate(packet.query, salvaged, packet, "numeric_salvage")
                if (salvageDecision.acceptLocal) {
                    Log.i(TAG, "POCKET_RAG_LOCAL_NUMERIC_SALVAGE_ACCEPT removedUnsupported=true stage=first_draft")
                    return acceptLocal(key, packet, salvaged, "local_recovery:numeric_salvage", cloudAttempted = false)
                }
            }
        }

        // A statement that copied a passage but swapped its named term (APBG -> ePBG): drop only
        // those statements and keep the rest when it still passes every check.
        if (decision.reason.startsWith("term_swap") && localDraft.isNotBlank()) {
            val salvaged = qualityGate.salvageSwappedTerms(localDraft, packet)
            if (!salvaged.isNullOrBlank() && salvaged != localDraft) {
                logAnswerLen("term_swap_salvage", localDraft, salvaged)
                val salvageDecision = evalGate(packet.query, salvaged, packet, "term_swap_salvage")
                if (salvageDecision.acceptLocal) {
                    Log.i(TAG, "POCKET_RAG_LOCAL_TERM_SWAP_SALVAGE_ACCEPT stage=first_draft")
                    return acceptLocal(key, packet, salvaged, "local_recovery:term_swap_salvage", cloudAttempted = false)
                }
            }
        }
        if ((decision.reason.startsWith("unsupported_numeric_claim") || decision.reason.startsWith("term_swap")) && localDraft.isNotBlank()) {
            runCatching { chainedSalvage(packet.query, localDraft, packet) }.getOrNull()?.let { salvaged ->
                logAnswerLen("chained_salvage", localDraft, salvaged)
                Log.i(TAG, "POCKET_RAG_LOCAL_CHAINED_SALVAGE_ACCEPT stage=first_draft")
                return acceptLocal(key, packet, salvaged, "local_recovery:chained_salvage", cloudAttempted = false)
            }
        }

        // R3-lite statement salvage as a function: with salvageBeforeRecovery it runs here on
        // the first draft (no second full generation on rejection); otherwise it keeps its old
        // place after the recovery pass.
        suspend fun tryStatementSalvage(draft: String, reason: String, stage: String): String? {
            if (!ArogyaFlags.r3Salvage || !reason.startsWith("low_evidence_support") || draft.isBlank()) return null
            // Item 4: one supported sentence is still shown (with "More detail") instead of a one-line dead end.
            val salvaged = qualityGate.salvageUnsupportedStatements(draft, packet.evidenceText)
                ?: qualityGate.salvageUnsupportedStatements(draft, packet.evidenceText, minSupported = 1)
            if (salvaged.isNullOrBlank() || salvaged == draft) return null
            logAnswerLen(stage, draft, salvaged)
            val salvageDecision = evalGate(packet.query, salvaged, packet, stage)
            if (salvageDecision.acceptLocal) {
                Log.i(TAG, "POCKET_RAG_STATEMENT_SALVAGE_ACCEPT removedUnsupported=true stage=$stage")
                return salvageWithFallback(salvaged, packet, stage)
            }
            Log.w(TAG, "POCKET_RAG_STATEMENT_SALVAGE_REJECT reason=${salvageDecision.reason} stage=$stage")
            return null
        }
        if (ArogyaFlags.salvageBeforeRecovery) {
            tryStatementSalvage(localDraft, decision.reason, "statement_salvage_early")?.let {
                return acceptLocal(key, packet, it, "statement_salvage_early", cloudAttempted = false)
            }
        }

        // A model refusal is not the same thing as missing KB evidence. When retrieval
        // produced a complete, query-related packet, give the local model one bounded
        // recovery pass before considering Cloud. This is especially important offline.
        var bestLocalDraft = localDraft
        if (shouldRetryLocally(decision, packet)) {
            Log.i(TAG, "POCKET_RAG_LOCAL_RECOVERY_START reason=${decision.reason} sources=${packet.sourceIds.size}")
            val recovery = runLocal(
                packet = packet,
                memory = memory,
                maxOutput = maxOutput,
                recoveryMode = true,
                timeoutMs = localRecoveryTimeoutMs
            )
            val recoveryMarker = qualityGate.sanitizeUnsupportedMarkers(recovery.text)
            val recoveryDraft = recoveryMarker.text
            val recoveryDecision = if (recovery.degenerate != null) {
                PocketRagQualityDecision(false, "degenerate_output:${recovery.degenerate}")
            } else if (recoveryMarker.markerOnly) {
                PocketRagQualityDecision(false, "local_declared_insufficient")
            } else {
                evalGate(packet.query, recoveryDraft, packet, "local_recovery")
            }
            if (recoveryDecision.acceptLocal && recoveryDraft.isNotBlank()) {
                return acceptLocal(key, packet, recoveryDraft, "local_recovery:${recoveryDecision.reason}", cloudAttempted = false)
            }
            Log.w(TAG, "POCKET_RAG_LOCAL_RECOVERY_REJECT reason=${recoveryDecision.reason}")
            runCatching { onDraftRejected?.invoke() }
            if (recoveryDraft.isNotBlank()) {
                Log.d(TAG, "POCKET_RAG_REJECTED_DRAFT reason=${recoveryDecision.reason} text='${recoveryDraft.take(400).replace('\n', ' ')}'")
            }
            // A blank or timed-out recovery must not replace the first draft's diagnosis: the
            // numeric salvage below still applies to the first draft.
            if (recoveryDraft.isNotBlank()) {
                bestLocalDraft = recoveryDraft
                decision = recoveryDecision
            }
        }

        // Numeric grounding failures are often formatting/local-generation defects rather
        // than evidence failures. After the bounded local rewrite, salvage only the
        // unsupported numeric sentence/line and re-run the full gate. This preserves
        // grounded local content without inventing or silently changing a number.
        if (decision.reason.startsWith("unsupported_numeric_claim") && bestLocalDraft.isNotBlank()) {
            val salvaged = qualityGate.salvageUnsupportedNumericSentences(
                packet.query,
                bestLocalDraft,
                packet
            )
            if (!salvaged.isNullOrBlank() && salvaged != bestLocalDraft) {
                logAnswerLen("numeric_salvage_late", bestLocalDraft, salvaged)
                val salvageDecision = qualityGate.evaluate(packet.query, salvaged, packet)
                if (salvageDecision.acceptLocal) {
                    Log.i(TAG, "POCKET_RAG_LOCAL_NUMERIC_SALVAGE_ACCEPT removedUnsupported=true")
                    return acceptLocal(
                        key,
                        packet,
                        salvaged,
                        "local_recovery:numeric_salvage",
                        cloudAttempted = false
                    )
                }
                Log.w(TAG, "POCKET_RAG_LOCAL_NUMERIC_SALVAGE_REJECT reason=${salvageDecision.reason}")
                decision = salvageDecision
            }
        }
        if (decision.reason.startsWith("unsupported_numeric_claim") && bestLocalDraft.isNotBlank()) {
            guideConfirmedAnswer(key, packet.query, bestLocalDraft, packet, "recovery")?.let { return it }
        }
        if ((decision.reason.startsWith("unsupported_numeric_claim") || decision.reason.startsWith("term_swap")) && bestLocalDraft.isNotBlank()) {
            runCatching { chainedSalvage(packet.query, bestLocalDraft, packet) }.getOrNull()?.let { salvaged ->
                logAnswerLen("chained_salvage_late", bestLocalDraft, salvaged)
                Log.i(TAG, "POCKET_RAG_LOCAL_CHAINED_SALVAGE_ACCEPT stage=recovery")
                return acceptLocal(key, packet, salvaged, "local_recovery:chained_salvage", cloudAttempted = false)
            }
        }

        // Phase 1.1 selected-KB policy: ordinary KB questions are local-only. Cloud
        // is introduced later only by an explicit user action or a manifest-backed
        // freshness requirement; a weak local draft is not consent to send KB data.
        if (canUseCloud() && shouldEscalateToCloud(decision, packet)) {
            val cloudAnswer = runCatching {
                cloud.generate(packet, memory, bestLocalDraft, decision.reason, maxOutput)
            }.onFailure { Log.w(TAG, "POCKET_RAG_CLOUD_EXCEPTION ${it.javaClass.simpleName}:${it.message}") }
                .getOrNull()
            val cloudDecision = evalGate(packet.query, cloudAnswer, packet, "cloud_escalation")
            if (cloudDecision.acceptLocal && !cloudAnswer.isNullOrBlank()) {
                val citedSources = packet.citedSourceIds(cloudAnswer)
                val disclosedCloud = KnowledgeSourcePresentation.renumberCitations(discloseUnsupportedClauses(packet, cloudAnswer), citedSources)
                sessions.record(key, packet.query, disclosedCloud, citedSources)
                Log.i(TAG, "POCKET_RAG_ACCEPT provider=CLOUD reason=${cloudDecision.reason} sources=${citedSources.size}")
                return PocketRagResult(
                    disclosedCloud,
                    AndroBaselineRuntime.Provider.CLOUD,
                    citedSources,
                    localAttempted = true,
                    cloudAttempted = true,
                    escalationReason = decision.reason
                )
            }
            if (!cloudAnswer.isNullOrBlank()) {
                Log.w(TAG, "POCKET_RAG_CLOUD_REJECT reason=${cloudDecision.reason}")
            }
        }

        // R3-lite: an answer rejected only for weak evidence support keeps its supported
        // sentences (at least 2 must remain) instead of falling back to copied key points.
        if (!ArogyaFlags.salvageBeforeRecovery) {
            tryStatementSalvage(bestLocalDraft, decision.reason, "statement_salvage")?.let {
                return acceptLocal(key, packet, it, "local_recovery:statement_salvage", cloudAttempted = false)
            }
        }

        // The model could not write a checked answer, but retrieval matched the question well: show
        // the key facts themselves (verbatim source sentences, with their sources) instead of a
        // failure message. Nothing is generated, so nothing can be invented.
        extractiveAnswer(packet)?.let { extractive ->
            Log.i(TAG, "POCKET_RAG_EXTRACTIVE_ANSWER reason=${decision.reason} chars=${extractive.length} best=${packet.bestRelevance}")
            return acceptLocal(key, packet, extractive, "extractive:${decision.reason}", cloudAttempted = false)
        }

        // Do not mislabel a generation failure as "out of context" when retrieval did
        // find evidence. The fallback states exactly what failed and keeps source refs.
        val fallback = if (packet.evidenceBlocks.isNotEmpty() && decision.reason.startsWith("local_declared_insufficient")) {
            // The model read the retrieved passages twice and found no answer in them. Say only
            // what is known: the search did not find an answer (not that the KB lacks one).
            "I couldn't find an answer to this in the selected knowledge base."
        } else if (packet.evidenceBlocks.isNotEmpty()) {
            "I found relevant information in the selected knowledge base, but the local model could not produce a reliable grounded answer from it."
        } else {
            "I couldn't find enough relevant information in the selected knowledge base to answer that reliably."
        }
        Log.w(
            TAG,
            "POCKET_RAG_INSUFFICIENT reason=${decision.reason} cloudAvailable=${canUseCloud()} " +
                "cloudAttempted=${canUseCloud() && shouldEscalateToCloud(decision, packet)} evidence=${packet.evidenceBlocks.size}"
        )
        return PocketRagResult(
            fallback,
            AndroBaselineRuntime.Provider.LOCAL,
            emptyList(),
            localAttempted = true,
            cloudAttempted = canUseCloud() && shouldEscalateToCloud(decision, packet),
            escalationReason = decision.reason,
            accepted = false
        )
    }

    /**
     * Part B3: fewer than 4 sentences survived while the passages hold more: keep them and
     * add the passages' own key facts under "From the guide (exact words)" instead of a stub.
     * Skipped when the facts add nothing over the salvaged text (high overlap) or the
     * evidence is weak (the extractive answer is null then). Nothing is generated here.
     */
    private suspend fun salvageWithFallback(salvaged: String, packet: PocketRagPacket, stage: String): String {
        if (ArogyaAnswerFormat.sentenceCount(salvaged) >= 4) return salvaged
        val extra = runCatching { extractiveAnswer(packet, exactWordsHeader = true) }.getOrNull()
        if (extra.isNullOrBlank()) return salvaged
        if (ArogyaAnswerFormat.overlap(salvaged, extra) >= 0.7) return salvaged
        val full = "$salvaged\n\n$extra"
        logAnswerLen(stage + "_exactwords", salvaged, full)
        return full
    }

    /**
     * Key-fact lines of the evidence ("• sentence" plus the app's calculation line) as a cited
     * answer, best facts first, about [EXTRACTIVE_CHARS] characters. Null when the re-ranker judged
     * the evidence weak (the facts would not answer the question) or there are no key-fact lines.
     */
    internal suspend fun extractiveAnswer(packet: PocketRagPacket, exactWordsHeader: Boolean = false): String? {
        val relevance = packet.bestRelevance ?: return null
        if (relevance < PocketRagPromptBuilder.WEAK_EVIDENCE_RELEVANCE) return null
        // The passages as the model read them (whole windows or key sentences), without the
        // "Source ID:" / "Source:" header lines and the bullet marks.
        val passageTexts = packet.evidenceBlocks.map { block ->
            block.sourceId to block.text.lines().map { it.trim() }
                .filterNot { it.isBlank() || it.startsWith("Source ID:") || it.startsWith("Source:") || it.startsWith("Note:") }
                .joinToString(" ") { it.removePrefix("\u2022").trim() }
                // List items inside a passage ("• Zinc helps … • Body loses zinc …", PDF bullet glyphs,
                // gaps of 3+ spaces left by removed glyphs) become sentence ends, so each item can be
                // chosen on its own instead of one long run-on line.
                .replace(Regex("\\s*[\\u2022\\u25AA\\u25CF\\uF0B7\\uF0A7\\uF0D8\\u0080-\\u009F]\\s*|(?:\\s*[\\u00A0\\u2000-\\u200B\\u202F\\u3000]\\s*)+|\\s{3,}"), "; ")
                .replace(Regex("(?:[;.]\\s*){2,}"), "; ")
                .replace(Regex("([:\\-–])\\s*;\\s*"), "$1 ")
                .replace(Regex("\\s+"), " ").replace("…", " ").trim().trimStart('.', ';', ' ')
        }.filter { it.second.isNotBlank() }
        if (passageTexts.isEmpty()) return null
        // The sentences that answer the question (word overlap with the question, best first; no
        // model involved), at most ~EXTRACTIVE_CHARS, shown in their passage order with their source.
        val facts = runCatching {
            GenericKbKeyFacts.select(packet.query, passageTexts.map { it.second }, null, EXTRACTIVE_CHARS, 10)
        }.getOrDefault(emptyList())
        val lines = mutableListOf<String>()
        facts.sortedWith(compareBy({ it.passage }, { it.index })).forEach { f ->
            val text = cleanFactSentence(f.text)
            if (text.length >= 25 && lines.none { it.contains(text.take(60)) }) lines += "\u2022 $text [${passageTexts[f.passage].first}]"
        }
        if (lines.isEmpty()) {
            // No sentence shares the question's words (e.g. a Hindi question over English text):
            // the start of the best passage, cut at a sentence end.
            val (id, text) = passageTexts.first()
            val cut = text.take(EXTRACTIVE_CHARS).let { t -> t.lastIndexOfAny(charArrayOf('.', '।')).takeIf { it > 80 }?.let { t.take(it + 1) } ?: t }
            lines += "\u2022 ${cleanFactSentence(cut)} [$id]"
        }
        // The pack's own long form of an abbreviation in the question (its glossary) leads the facts.
        packet.terms.split(';').map { it.trim() }.filter { it.contains('=') }.take(2).reversed().forEach { t ->
            val (abbr, long) = t.split('=', limit = 2).map { it.trim() }
            if (abbr.isNotBlank() && long.isNotBlank()) lines.add(0, "\u2022 $abbr: ${long.split(' ').joinToString(" ") { w -> if (w.length > 3) w.replaceFirstChar { it.uppercase() } else w }}")
        }
        val devanagari = KbReplyText.dominantScript(packet.query) == KbReplyText.Script.DEVANAGARI
        val header = if (exactWordsHeader) {
            if (devanagari) "गाइड के शब्दों में (सटीक शब्द):" else "From the guide (exact words):"
        } else if (devanagari) EXTRACTIVE_HEADER_HI else EXTRACTIVE_HEADER_EN
        return header + "\n" + lines.joinToString("\n")
    }

    /** A source sentence as shown to the user: page furniture (running titles, page numbers, bullets) removed. */
    private fun cleanFactSentence(raw: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        // Leading running header in capitals ("HANDBOOK FOR ASHA ON HOME BASED CARE … 60 5.4.9 Zinc…").
        t = t.replace(Regex("^(?:[A-Z][A-Z&,'’\\-]+\\s+){3,}(?:\\d+\\s+)?"), "")
        // Leading page / section numbers ("60 5.4.9 ").
        t = t.replace(Regex("^(?:\\d+(?:\\.\\d+)*\\.?\\s+){1,3}(?=[A-Za-z\\u0900-\\u097F(])"), "")
        // A paragraph number glued between a heading and its text ("… (AoN) 10. After …") ends a sentence.
        t = t.replace(Regex("(?<=[A-Za-z)])\\s+\\d{1,3}\\.\\s+(?=[A-Z])"), ". ")
        t = t.trim(' ', '•', '-', '–', '|', ';', ',')
        return t
    }

    private fun acceptCloud(key: String, packet: PocketRagPacket, answer: String, reason: String): PocketRagResult {
        val citedSources = packet.citedSourceIds(answer)
        val disclosed = KnowledgeSourcePresentation.renumberCitations(discloseUnsupportedClauses(packet, answer), citedSources)
        sessions.record(key, packet.query, disclosed, citedSources)
        Log.i(TAG, "POCKET_RAG_ACCEPT provider=CLOUD reason=$reason sources=${citedSources.size}")
        return PocketRagResult(
            disclosed, AndroBaselineRuntime.Provider.CLOUD, citedSources,
            localAttempted = false, cloudAttempted = true, escalationReason = reason
        )
    }

    private fun shouldEscalateToCloud(
        decision: PocketRagQualityDecision,
        packet: PocketRagPacket
    ): Boolean {
        // Only when the user turned on "Online backup" (off by default): the phone's answer failed its
        // checks twice and there is evidence to answer from. The online AI gets the same passages and
        // its answer passes the same checks, so it cannot add facts the knowledge base does not have.
        return runCatching { onlineBackupEnabled() }.getOrDefault(false) && packet.evidenceBlocks.isNotEmpty()
    }

    /** One local generation: its text, or why it is unusable ([degenerate] set: never shown). */
    private class LocalDraft(val text: String?, val degenerate: String? = null)

    private suspend fun runLocal(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        maxOutput: Int,
        recoveryMode: Boolean,
        timeoutMs: Long
    ): LocalDraft {
        val started = System.currentTimeMillis()
        var degenerate: String? = null

        suspend fun call() = runCatching { local.generate(packet, memory, maxOutput, recoveryMode) }
            .onFailure {
                if (it is PocketRagDegenerateOutput) degenerate = it.reason
                else Log.w(TAG, "POCKET_RAG_LOCAL_EXCEPTION recovery=$recoveryMode ${it.javaClass.simpleName}:${it.message}")
            }
            .getOrNull()
        var result = if (callerTimeoutEnabled) withTimeoutOrNull(timeoutMs.coerceAtLeast(1_000L)) { call() } else call()
        
        val elapsed = System.currentTimeMillis() - started
        // No question or answer text in the log (health data); timings are the generation's own.
        Log.i(
            TAG,
            "POCKET_RAG_LOCAL_DONE recovery=$recoveryMode best=${packet.bestRelevance?.let { "%.2f".format(it) } ?: "-"} " +
                "evidence=${packet.evidenceBlocks.size} history=${memory.size} totalMs=$elapsed " +
                "chars=${result?.length ?: 0} timedOut=${result == null && degenerate == null}"
        )
        // Glued or spaced repetition anywhere in the draft ("heinheinhein…", "है है है है …").
        if (degenerate == null && result != null) degenerate = KbReplyText.degenerateRepetition(result)
        // 8 Nov: a looping draft keeps its sentences before the loop (then every normal check)
        // instead of being thrown away for a second ~30–60 s generation.
        if (degenerate != null && degenerate!!.startsWith("repeat:") && result != null && ArogyaFlags.loopKeepBefore) {
            val draftText: String = result
            val kept = KbReplyText.keepBeforeLoop(draftText)
            Log.w(TAG, "POCKET_RAG_LOOP_KEEP_BEFORE stage=engine kept=${kept != null} before=${draftText.length} after=${kept?.length ?: 0}")
            // 9 Nov 2026 (AH): only a substantial text before the loop is kept; a short one goes to the recovery generation.
            if (kept != null && kept.length >= LOOP_KEEP_MIN_CHARS) { result = kept; degenerate = null }
        }
        if (degenerate != null) {
            Log.w(TAG, "POCKET_RAG_LOCAL_DEGENERATE recovery=$recoveryMode reason=$degenerate chars=${result?.length ?: 0} elapsed=${System.currentTimeMillis() - started}ms")
            return LocalDraft(null, degenerate)
        }
        if (result == null) {
            Log.w(TAG, "POCKET_RAG_LOCAL_TIMEOUT recovery=$recoveryMode timeout=${timeoutMs}ms elapsed=${System.currentTimeMillis() - started}ms")
        }
        // A degenerate repetition at the end ("है है है …") is cut; the answer before it is kept and
        // then checked by the quality gate like any other draft.
        // Near the output budget and not ending a sentence: it was cut at the limit. Characters per
        // token are at least ~2.5 for the scripts in use, so length >= budget x 2.5 marks the limit.
        val cleaned = result?.let { KbReplyText.removeRepeatedSentences(KbReplyText.trimRepeatedTail(it)) }
        val trimmed = cleaned?.let { KbReplyText.completeAtLimit(it, likelyAtLimit = it.length >= maxOutput * 5 / 2) }
        val resultLen = result?.length ?: 0
        if (trimmed != null && trimmed.length != resultLen) {
            Log.w(TAG, "POCKET_RAG_LOCAL_REPETITION_TRIMMED recovery=$recoveryMode before=$resultLen after=${trimmed.length}")
        }
        // Safety: a sentence stating what the guide says NOT to do ("cover with a blanket") is removed.
        val guarded = if (trimmed != null && ArogyaFlags.negationFlipGuard)
            runCatching { KbReplyText.dropFlippedProhibitions(trimmed, packet.evidenceText) }.getOrDefault(trimmed to 0) else null
        if (guarded != null && guarded.second > 0) Log.w(TAG, "POCKET_RAG_FLIPPED_PROHIBITION removed=${guarded.second} recovery=$recoveryMode")
        return LocalDraft(guarded?.first ?: trimmed)
    }

    private fun shouldRetryLocally(decision: PocketRagQualityDecision, packet: PocketRagPacket): Boolean {
        if (packet.evidenceBlocks.isEmpty()) return false
        // Wrong reply script is a formatting slip, not missing evidence: always one retry. A broken
        // (looping, incoherent or unsupported) generation also gets one fresh generation.
        if (decision.reason.startsWith("reply_script_mismatch") ||
            decision.reason.startsWith("degenerate_") ||
            decision.reason.startsWith("term_swap") ||
            decision.reason.startsWith("low_evidence_support") ||
            decision.reason.startsWith("wrong_expansion")
        ) return true
        if (!packet.coverageComplete) return false
        return decision.reason.startsWith("local_answer_blank") ||
            decision.reason.startsWith("local_declared_insufficient") ||
            decision.reason.startsWith("answer_query_mismatch") ||
            decision.reason.startsWith("no_lexical_grounding_signal") ||
            decision.reason.startsWith("reply_script_mismatch") ||
            decision.reason.startsWith("unsupported_numeric_claim")
    }

    private suspend fun acceptLocal(
        key: String,
        packet: PocketRagPacket,
        answer: String,
        reason: String,
        cloudAttempted: Boolean
    ): PocketRagResult {
        // 8 Nov 2026: per-sentence citations follow the passage each sentence actually comes from.
        val answer = if (ArogyaFlags.citeAllSupportingSources) runCatching { packet.reattributeCitations(answer) }.getOrDefault(answer) else answer
        val citedSources = packet.citedSourceIds(answer)
        // A written answer that no retrieved passage supports (no citation and no shared content,
        // e.g. the model answered in another language or from memory) is not shown as a KB answer:
        // the passages' own key facts are shown instead, with their sources.
        // A Hindi answer from English passages cannot share their words, so it carries no checked
        // citation; it is kept (the runtime's proof check matches it to its passages across languages)
        // rather than replaced by English passage text under a Hindi heading.
        val crossScript = KbReplyText.dominantScript(answer) == KbReplyText.Script.DEVANAGARI &&
            KbReplyText.dominantScript(packet.evidenceText) != KbReplyText.Script.DEVANAGARI
        if (citedSources.isEmpty() && !reason.startsWith("extractive") && !crossScript) {
            extractiveAnswer(packet)?.let { extractive ->
                Log.w(TAG, "POCKET_RAG_UNGROUNDED_DRAFT reason=$reason -> extractive")
                return acceptLocal(key, packet, extractive, "extractive:ungrounded_draft", cloudAttempted)
            }
        }
        // Internal passage markers ([S3], [S7]) become the numbers of the document rows shown.
        val withDisclosure = discloseUnsupportedClauses(packet, answer)
        val disclosed = KnowledgeSourcePresentation.renumberCitations(withDisclosure, citedSources)
        // 9 Nov audit: copied guide sentences that stood in for a failed answer are shown (labelled), but
        // never remembered as the assistant's answer, so a follow-up is not resolved against them.
        if (!reason.startsWith("extractive")) sessions.record(key, packet.query, disclosed, citedSources)
        Log.i(
            TAG,
            "POCKET_RAG_ACCEPT provider=LOCAL reason=$reason sources=${citedSources.size} " +
                "unsupportedDisclosed=${withDisclosure.length != answer.length} coverage=${packet.clauseCoverage.joinToString(",") { "${it.clauseId}:${it.state}" }}"
        )
        Log.d(TAG, "POCKET_RAG_ACCEPTED_TEXT reason=$reason text='${disclosed.take(4000).replace('\n', ' ')}'")
        return PocketRagResult(
            disclosed,
            AndroBaselineRuntime.Provider.LOCAL,
            citedSources,
            localAttempted = true,
            cloudAttempted = cloudAttempted,
            escalationReason = if (reason.startsWith("local_recovery:")) reason else ""
        )
    }

    private fun realTokens(text: String): Int {
        val deva = text.count { it in '\u0900'..'\u097F' }
        return ((text.length - deva) / 3.6 + deva / 1.4).toInt() + 1
    }

    /** Evidence-driven token budget; no topic/keyword routing. */
    internal fun outputBudget(packet: PocketRagPacket, cloud: Boolean = false): Int {
        val blockCount = packet.evidenceBlocks.size
        val chars = packet.evidenceText.length
        val desired = GenericKbTokenBudget.desiredOutputTokens(
            query = packet.query,
            clauses = packet.clauseCoverage.size.coerceAtLeast(GenericKbQueryPlanner.plan(packet.query, packet.query).clauses.size),
            evidence = chars + blockCount * 64
        )
        val context = if (cloud) ArogyaBudget.CLOUD_CONTEXT else contextWindowTokens().coerceAtLeast(1_024)
        // 9 Nov 2026: real token counts (English ~3.6 chars per token, Devanagari ~1.4) plus the measured prompt
        // reserve (instructions + system ~1,663 tokens) and a margin. The old 2-chars-per-token estimate almost
        // doubled the evidence, so "More detail" (more evidence) was left ~1,550 tokens, less than a normal answer.
        val inputEstimate = realTokens(packet.evidenceText + " " + packet.query) + blockCount * 40 + PROMPT_RESERVE_TOKENS + OUTPUT_MARGIN_TOKENS
        val availableOutput = (context - inputEstimate).coerceAtLeast(256)
        // The answer model picks the length from the question (PocketRagPromptBuilder.LENGTH); this
        // is the hard ceiling behind its largest tier, per independently asked question part.
        // Part B2: ceilings come from ArogyaBudget (kind base x query language), per engine.
        val parts = packet.clauseCoverage.size.coerceAtLeast(1)
        // Item 4 "More detail": PROCEDURE-style ceiling, and the full ceiling (not the estimate).
        val perPart = if (packet.detailMode) {
            // "More detail" never gets less room than the normal answer to the same question.
            val normal = runCatching { ArogyaQuestionType.ceilingPerPart(packet.kind, packet.query, cloud, inputEstimate, context) }.getOrDefault(0)
            maxOf(normal, runCatching { ArogyaBudget.askSopDetail(packet.query, cloud, inputEstimate, context) }
                .getOrDefault(MAX_ANSWER_TOKENS_PER_PART))
        } else if (ArogyaFlags.r11LowReasoning) {
            runCatching { ArogyaQuestionType.ceilingPerPart(packet.kind, packet.query, cloud, inputEstimate, context) }
                .getOrDefault(MAX_ANSWER_TOKENS_PER_PART)
        } else MAX_ANSWER_TOKENS_PER_PART
        val ceiling = perPart * parts
        // Ask answers: the full ceiling, not the size estimate (the estimate cut Gemma's five-part
        // answers at ~600 tokens, before "When to tell the ANM"). ArogyaFlags.askNoCap = false restores it.
        return if (packet.detailMode || ArogyaFlags.askNoCap) ceiling.coerceAtMost(availableOutput).coerceAtLeast(MIN_OUTPUT_TOKENS)
        else desired.coerceAtMost(ceiling).coerceAtMost(availableOutput).coerceAtLeast(MIN_OUTPUT_TOKENS)
    }
}
