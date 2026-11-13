package com.example.arfas

import android.util.Log
import kotlinx.coroutines.flow.collect

/** PocketSage-style single local generation over a selected evidence packet. */
class PocketRagLocalGenerator(
    private val modelManager: LocalModelManager?,
    /** Receives the answer while it is written (on-screen preview); null = no preview. */
    private val onDelta: ((String) -> Unit)? = null
) : PocketRagLocalAnswerer {
    companion object {
        private const val TAG = "PocketRagLocalGenerator"
        /** Text kept from before a decoder loop must be at least this long to count as an answer. */
        const val LOOP_KEEP_MIN_CHARS = 500
        /** Headed parts (Answer, What to do, …) a kept first-generation text needs (AQ). */
        const val LOOP_KEEP_MIN_SECTIONS = 2
        /** Recovery answer sampling (topK 40 / topP 0.95 of the engine): low enough to copy numbers, high enough not to loop. */
        const val RECOVERY_TEMPERATURE = 0.3
    }

    /** The last choice (question → evidence block ids), so the recovery generation reads the same passages. */
    @Volatile private var lastPick: Pair<String, List<String>>? = null

    private suspend fun pickPassages(lm: LocalModelManager, packet: PocketRagPacket): PocketRagPacket {
        // Same question and same passages only (block ids repeat across questions).
        val key = packet.query.trim() + "|" + packet.evidenceBlocks.joinToString(",") { it.text.hashCode().toString() }
        val ids = lastPick?.takeIf { it.first == key }?.second ?: run {
            val t = System.currentTimeMillis()
            val reply = runCatching {
                lm.generateDetachedStream(ArogyaPassagePick.prompt(packet.query, packet.evidenceBlocks.map { it.text }),
                    ArogyaPassagePick.MAX_OUTPUT_TOKENS, "kb_pick", null)
            }.getOrNull()
            val idx = ArogyaPassagePick.withBest(ArogyaPassagePick.parse(reply, packet.evidenceBlocks.size), packet.evidenceBlocks.size)
            Log.i(TAG, "KB_PASSAGE_PICK ms=${System.currentTimeMillis() - t} reply=${reply?.replace('\n', ' ')?.take(40)} " +
                "picked=${idx?.joinToString(",") { (it + 1).toString() } ?: "all"} of=${packet.evidenceBlocks.size}")
            val chosen = idx?.map { packet.evidenceBlocks[it].id }.orEmpty()
            lastPick = key to chosen
            chosen
        }
        if (ids.isEmpty()) return packet
        val blocks = ids.mapNotNull { id -> packet.evidenceBlocks.firstOrNull { it.id == id } }
        return if (blocks.isEmpty()) packet else packet.copy(evidenceBlocks = blocks)
    }

    override suspend fun generate(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        maxOutputTokens: Int,
        recoveryMode: Boolean
    ): String? {
        val lm = modelManager ?: return null
        if (!lm.isReady || !lm.isTextWarm) {
            Log.w(TAG, "POCKET_RAG_LOCAL_NOT_READY state=${lm.state} ready=${lm.isReady} textWarm=${lm.isTextWarm} disabled=${lm.permanentlyDisabled}")
            return null
        }
        var completed: BaselineGenerationResult? = null
        val t0 = System.currentTimeMillis()
        var firstTokenMs = -1L
        val timedDelta = onDelta?.let { cb ->
            { piece: String ->
                if (firstTokenMs < 0 && piece.isNotBlank()) firstTokenMs = System.currentTimeMillis() - t0
                cb(piece)
            }
        }
        suspend fun run(p: PocketRagPacket) {
            completed = null
            val prompt = PocketRagPromptBuilder.buildParts(p, memory, recoveryMode)
            lm.inferTextGrounded(
                evidence = p.modelEvidenceText,
                userQueryOnly = prompt.userMessage,
                history = emptyList(),
                strictGrounding = false,
                sourceGroundingRequired = true,
                knowledgePackGrounding = true,
                maxOutputTokenOverride = maxOutputTokens.coerceAtLeast(256),
                systemPromptAddon = prompt.systemAddon,
                onToken = timedDelta,
                // 11 Nov 2026 (AJ): greedy decoding (most likely token every time) is what loops; the recovery after a
                // loop samples gently (low temperature) so it does not walk into the same loop again.
                temperatureOverride = if (recoveryMode) RECOVERY_TEMPERATURE else null,
                onComplete = { completed = it }
            ).collect { }
        }
        // 9 Nov 2026 (AD): on some phones the engine's real KV cache (e.g. 4096 tokens) is smaller than
        // the window it was opened with (8192); a longer prompt is refused before any word and the
        // answer came out blank. When the real size is known, passages that would not fit are left out
        // (best first are kept); when the engine refuses, its reported size is remembered and this
        // answer is retried once with the passages that fit.
        fun fitted(p: PocketRagPacket, capacity: Int, margin: Int): PocketRagPacket {
            val prompt = PocketRagPromptBuilder.buildParts(p.copy(evidenceBlocks = emptyList()), memory, recoveryMode)
            val fixed = ArogyaKvCapacity.BASE_PROMPT_TOKENS + ArogyaKvCapacity.estimateTokens(prompt.systemAddon + prompt.userMessage)
            val keep = ArogyaKvCapacity.keepWithin(p.evidenceBlocks.map { ArogyaKvCapacity.estimateTokens(it.text) }, fixed, capacity - margin)
            return if (keep >= p.evidenceBlocks.size) p else p.copy(evidenceBlocks = p.evidenceBlocks.take(keep))
        }
        // 12 Nov 2026 (AW): pick, then write (ArogyaPassagePick); the recovery generation reuses the same choice.
        // A question with two or more parts ("the JSY incentive and which documents") keeps every passage: a pick could
        // keep only one part's passages.
        val chosen = if (ArogyaFlags.pickPassages && packet.evidenceBlocks.size > 1 && packet.clauseCoverage.size <= 1) pickPassages(lm, packet) else packet
        val known = lm.learnedKbCapacityTokens
        val first = if (known > 0) fitted(chosen, known, 0) else chosen
        if (first !== chosen) Log.i(TAG, "POCKET_RAG_CAPACITY_FIT capacity=$known keep=${first.evidenceBlocks.size} of=${chosen.evidenceBlocks.size} recovery=$recoveryMode")
        run(first)
        val refused = completed?.error
        if (ArogyaKvCapacity.isCapacityError(refused) && first.evidenceBlocks.size > 1) {
            val cap = ArogyaKvCapacity.reported(refused) ?: ArogyaBudget.PHONE_CONTEXT
            lm.noteKbCapacity(cap)
            // A margin on the retry (the estimate let this prompt through) and at least one passage fewer.
            val fit = fitted(first, cap, 400)
            val retry = if (fit.evidenceBlocks.size < first.evidenceBlocks.size) fit
                else first.copy(evidenceBlocks = first.evidenceBlocks.take(first.evidenceBlocks.size - 1))
            Log.w(TAG, "POCKET_RAG_CAPACITY_RETRY capacity=$cap keep=${retry.evidenceBlocks.size} of=${first.evidenceBlocks.size} recovery=$recoveryMode")
            run(retry)
        }
        val result = completed ?: return null
        val clean = KbReplyText.normalizeCitations(FixSightOutputSanitizer.sanitize(result.text).trim())
        Log.i(TAG, "POCKET_RAG_LOCAL_END completed=${result.completed} raw=${result.text.length} clean=${clean.length} stop=${result.stopReason} error=${result.error}")
        Log.i(TAG, "POCKET_RAG_LOCAL_GEN_MS total=${System.currentTimeMillis() - t0} firstToken=$firstTokenMs chars=${clean.length} recovery=$recoveryMode")
        // The decoder's loop guard stopped a repeating generation. Its text is broken, not a
        // shorter answer: it is never shown; the engine retries once and otherwise fails safely.
        if (result.stopReason.contains("loop_guard")) {
            // Keep the answer written before the loop (it then goes through every check like any
            // draft) instead of discarding it and spending a full second generation (~60 s).
            val kept = if (ArogyaFlags.loopKeepBefore) KbReplyText.keepBeforeLoop(clean) else null
            Log.w(TAG, "POCKET_RAG_LOOP_KEEP_BEFORE kept=${kept != null} before=${clean.length} after=${kept?.length ?: 0}")
            // 9 Nov 2026 (AH): a short text before the loop (phone 23:27: two lines) is not an answer; the engine's
            // one recovery generation then writes it again (quality over speed).
            // 11 Nov 2026 (AQ, phone 22:55): 799 Hindi characters kept before a loop were only the "उत्तर:" part, shown
            // half-written. The first generation keeps its text only when it has at least two headed parts; otherwise
            // the recovery generation writes the answer again. The recovery's own text is kept on its length alone.
            val headed = kept?.let { k -> runCatching { ArogyaAnswerFormat.sections(k).count { it.heading.isNotEmpty() } }.getOrDefault(0) } ?: 0
            if (kept != null && kept.length >= LOOP_KEEP_MIN_CHARS && (recoveryMode || headed >= LOOP_KEEP_MIN_SECTIONS)) return kept
            Log.w(TAG, "POCKET_RAG_LOOP_KEEP_REJECTED chars=${kept?.length ?: 0} sections=$headed recovery=$recoveryMode")
            throw PocketRagDegenerateOutput("decoder_loop_guard")
        }
        return clean.takeIf { result.completed && it.isNotBlank() }
    }
}
