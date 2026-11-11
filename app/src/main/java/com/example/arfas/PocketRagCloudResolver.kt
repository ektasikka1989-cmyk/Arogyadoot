package com.example.arfas

import android.util.Log

/** Grounded Cloud resolver. It receives only selected-KB evidence and KB-only continuity. */
class PocketRagCloudResolver(
    private val provider: GroqChatProvider,
    /** Receives the draft while it arrives (on-screen preview); null = wait for the full reply. */
    private val onDelta: ((String) -> Unit)? = null
) : PocketRagCloudAnswerer {
    companion object {
        private const val TAG = "PocketRagCloudResolver"
        /** Cloud's reply when the KB evidence does not answer a "Check online" question. */
        const val NOT_IN_EVIDENCE = PocketRagEngine.NOT_IN_EVIDENCE
        /**
         * Whole user prompt (passages + question + instructions). ~16,000 chars ≈ 4,000 tokens: with the
         * system text and an answer it stays under Groq's free-tier 8,000 tokens per minute.
         */
        const val MAX_PROMPT_CHARS = 16_000
        /** Same limit in tokens (Hindi text is token-dense), leaving ~2,400 tokens for the answer. */
        const val MAX_PROMPT_TOKENS = 4_400

        /** Rough token count: ~3.6 Latin characters per token, Devanagari ~1.4 (as GroqChatProvider). */
        fun promptTokens(text: String): Int {
            val deva = text.count { it in '\u0900'..'\u097F' }
            return ((text.length - deva) / 3.6 + deva / 1.4).toInt() + 1
        }
    }

    /** True once any streamed text reached the UI (the engine clears it when the draft is rejected). */
    @Volatile var didStream = false
    /** Part B8: last delta arrival, for the 15 s idle limit (0 = none yet). */
    @Volatile var lastDeltaMs = 0L
        private set

    override suspend fun generate(
        packet: PocketRagPacket,
        memory: List<PocketRagTurnMemory>,
        localDraft: String?,
        reason: String,
        maxOutputTokens: Int
    ): String? {
        fun build(packet: PocketRagPacket): String {
        val referenced = packet.evidenceBlocks.map { it.sourceId }.toSet()
        val sources = packet.sources
            .filter { referenced.isEmpty() || it.id in referenced }
            .joinToString("\n") { source -> "${source.id}: ${source.display}" }
        return buildString {
            appendLine("<selected_kb_evidence>")
            appendLine(packet.evidenceText)
            appendLine("</selected_kb_evidence>")
            appendLine("<source_details>")
            appendLine(sources)
            appendLine("</source_details>")
            appendLine("<user_question>${packet.query}</user_question>")
            // Typed fast on a phone: the cleaned reading too (8 Nov 2026), as the phone model gets it.
            runCatching { ArogyaQuestionNormalizer.normalize(packet.query) }.getOrNull()?.takeIf { it.changed }?.let { n ->
                appendLine("<question_cleaned>${n.text.trim()}</question_cleaned>")
            }
            if (packet.followUpOf.isNotBlank()) {
                appendLine("<previous_question>${packet.followUpOf.take(400)}</previous_question>")
                appendLine("The user's question follows up the previous question; resolve 'it', 'continue', 'the second one' etc. against it.")
            }
            if (reason == "check_online") {
                if (!localDraft.isNullOrBlank()) {
                    appendLine("<on_device_answer>")
                    // A Hindi draft costs ~2.5x the tokens of English text: send less of it.
                    appendLine(localDraft.take(if (localDraft.any { it in '\u0900'..'\u097F' }) 1_600 else 4_000))
                    appendLine("</on_device_answer>")
                }
                appendLine("The user asked to check the on-device answer online. Verify it against the selected KB evidence: " +
                    "correct every wrong or missing fact, keep what is right, and give the corrected complete answer (not a review). " +
                    "Cite evidence with its source IDs like [S1]. Use only the evidence: never fill a gap from your own memory " +
                    "(for example, never guess what an abbreviation stands for). If the evidence does not state the answer, " +
                    "reply with exactly $NOT_IN_EVIDENCE and nothing else; the app then searches the web instead.")
                // 8 Nov 2026: the checked answer has the same five-part shape and full length as Cloud first.
                if (ArogyaFlags.r2LengthPrompt) {
                    runCatching { appendLine(packet.lengthLineOverride.ifBlank { ArogyaQuestionType.lengthLine(packet.kind, packet.query, cloud = true) }) }
                }
            } else {
                appendLine("Use only the selected KB evidence above as factual authority. Synthesize the best complete conversational answer, citing source IDs like [S1]. If the evidence cannot establish part of the request, say so rather than using outside knowledge.")
                if (ArogyaFlags.r2LengthPrompt) {
                    runCatching { appendLine(packet.lengthLineOverride.ifBlank { ArogyaQuestionType.lengthLine(packet.kind, packet.query, cloud = true) }) }
                }
            }
        }
        }
        // 8 Nov 2026: the request used to be cut to its first 8,000 characters, and the question and every
        // instruction come AFTER the passages – so Qwen never saw them. Now the least relevant passages
        // are dropped until the whole prompt fits; the question and instructions always arrive.
        var fitted = packet
        var current = build(fitted)
        while ((current.length > MAX_PROMPT_CHARS || promptTokens(current) > MAX_PROMPT_TOKENS) && fitted.evidenceBlocks.size > 1) {
            fitted = fitted.copy(evidenceBlocks = fitted.evidenceBlocks.dropLast(1))
            current = build(fitted)
        }
        if (fitted.evidenceBlocks.size < packet.evidenceBlocks.size)
            Log.i(TAG, "CLOUD_PROMPT_FITTED passages=${fitted.evidenceBlocks.size}/${packet.evidenceBlocks.size} chars=${current.length}")
        val messages = PocketRagPromptBuilder.continuityMemory(packet.query, memory, packet.followUpOf.isNotBlank()).flatMap { turn ->
            listOf("user" to turn.user, "assistant" to turn.assistant)
        }
        val t0 = System.currentTimeMillis()
        var firstMs = -1L
        val raw = provider.chatGenericKnowledgeText(
            messages = messages,
            currentQuery = current,
            maxOutputTokens = maxOutputTokens.coerceAtLeast(256),
            onDelta = onDelta?.let { cb ->
                { piece: String ->
                    if (firstMs < 0 && piece.isNotBlank()) firstMs = System.currentTimeMillis() - t0
                    didStream = true
                    lastDeltaMs = System.currentTimeMillis()
                    runCatching { cb(piece) }
                }
            }
        ).trim()
        Log.i(TAG, "POCKET_RAG_CLOUD_END reason=$reason chars=${raw.length} evidenceChars=${packet.evidenceText.length}")
        Log.i(TAG, "POCKET_RAG_CLOUD_MS total=${System.currentTimeMillis() - t0} firstWords=$firstMs chars=${raw.length} stream=$didStream")
        // Cloud is intentionally not rewritten or revalidated by the local model.
        return raw.takeIf { it.isNotBlank() }
    }
}
