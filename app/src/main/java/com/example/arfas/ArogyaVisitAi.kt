package com.example.arfas

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext

/**
 * ArogyaDoot's visit layer is a WRAPPER over the base platform. Guide questions go through the
 * platform's own turn (AndroBaselineRuntime.chat with the installed knowledge base selected): the
 * same AndroLLM, PocketSage RAG, semantic search, re-ranking and grounding the platform chat uses,
 * unchanged. The visit layer adds only:
 *  - deterministic newborn / postnatal danger-sign alert BEFORE any model (ArogyaDangerSigns);
 *  - visit context in the question (age group) so retrieval finds the right population;
 *  - visit-note organisation using ArogyaDoot's generateDetached + ArogyaCapture (labels only).
 */
enum class ArogyaAiTask { EXPLAIN, ORGANISE_NOTES, HANDOVER }
data class ArogyaAiRequest(val task:ArogyaAiTask,val visit:ArogyaVisit,val question:String="",val hindi:Boolean=false,val topic:String="",val previousExchange:String="",val onlineCheck:Boolean=false,
    /** Worker already saw the urgent alert and asked for the guide's guidance: alert is prepended, not repeated alone. */
    val afterSafetyPrompt:Boolean=false,
    /** Live progress: stage and the answer text as it is being written (a draft until checked). */
    val onProgress:((ArogyaProgress)->Unit)? = null)
data class ArogyaExtraction(val fields:List<ExtractedField>,val modelLabel:String,val cloudUsed:Boolean,val ms:Long,val note:String)
data class ArogyaAiResult(val text:String,val provider:String,val elapsedMs:Long,val sources:List<ArogyaSource> = emptyList(),val canCopyToNotes:Boolean=false,val online:Boolean=false,val checkedAt:String="",val sourceOnly:Boolean=false,val captureItems:List<ArogyaCaptureItem> = emptyList(),val timingsMs:Map<String,Long> = emptyMap(),val safetyPrompt:Boolean=false,
    /** "The guide says": ArogyaDoot's own chosen source sentence for this answer, with its source label. */
    val quote:String="",val quoteSource:String="",
    /** R8: the web/general answer was replaced by the ANM message; closest guide topics shown with it. */
    val r8Blocked:Boolean=false,val r8Topics:List<String> = emptyList(),
    /** Item 5: 1 = guide answer, 2 = official website, 3 = not found. Per-sentence citations. */
    val tier:Int = 1,val segments:List<ArogyaOfficialAnswer.CitedSegment> = emptyList(),
    /** Item 5a: the ☁ Check online answer kept beside the offline one (Offline | Online tabs). */
    val onlineText:String="",val onlineSources:List<ArogyaSource> = emptyList(),
    val onlineSegments:List<ArogyaOfficialAnswer.CitedSegment> = emptyList(),val onlineMs:Long=0L,val onlineTier:Int=0,
    val onlineProvider:String="",
    /** Part C3: labeled stored copy shown only when the model failed/timed out (strong match). */
    val storedCopy:Boolean=false)

class ArogyaVisitAi(private val runtime:AndroBaselineRuntime,private val features:Phase1FeatureBundle,private val onlineKey:()->String = {""},
    /** ArogyaDoot's own attachment manager: photo/PDF text is read on the phone (ML Kit OCR, PDF text). */
    private val attachments: Phase1AttachmentManager? = null,
    /** Same model-load boundary ArogyaDoot's chat service waits on before every turn. */
    private val ensureLocalModel: suspend () -> Boolean = { true },
    /** App context for the Cloud-first → phone retry (policy flip, restored after). Null = no retry. */
    private val appContext: Context? = null) {
    private val mutex=Mutex()
    @Volatile private var lastVisitId: String? = null
    /** Item 6: true while an AI answer is being written (guide installs wait for it). */
    val isBusy: Boolean get() = mutex.isLocked

    /** Set by the screen: which cloud model/key to use and the Settings order (Cloud first / Local first). */
    @Volatile var cloudChoice: (() -> ArogyaCloudChoice)? = null

    /**
     * Every ArogyaDoot AI action goes through here. Cloud first: cloud model, then the phone if there is no signal,
     * no key, a slow network or an unusable reply. Local first: the phone, then the cloud if the phone model is not
     * ready or its reply is unusable. [accept] checks each reply (grounding etc.); returns the result and who made it.
     */
    private suspend fun <T> route(feature: String, prompt: String, maxTokens: Int, cloudPrompt: String = prompt,
                                  system: String = "Follow the instructions exactly.", cloud: Boolean = true,
                                  cloudTokens: Int = maxTokens, lang: String = "en",
                                  onDelta: ((String) -> Unit)? = null,
                                  accept: (String) -> T?): Pair<T?, String> {
        val c = runCatching { withContext(Dispatchers.IO) { cloudChoice?.invoke() } }.getOrNull()
        val ep = c?.endpoint; val key = c?.key.orEmpty()
        var emitted = false
        // Part B8: word-by-word deltas per engine; "" resets the shown draft when the turn
        // falls back to the other engine, so two half-answers never concatenate on screen.
        val emit: (String) -> Unit = { d -> if (d.isNotEmpty()) emitted = true; onDelta?.invoke(d) }
        // Why the cloud did not answer (shown on the answer when Cloud first was on): never a silent switch to the phone.
        var cloudWhy = when {
            !cloud -> ""
            ep != null && key.isNotBlank() -> ""
            appContext == null -> "cloud not set up"
            !MetaOnline.hasInternet(appContext) -> "no internet"
            else -> "no Groq key saved"
        }
        Log.i(TAG, "AROGYA_ROUTE_DECISION feature=$feature cloudFirst=${c?.cloudFirst == true} cloudUsable=${ep != null && key.isNotBlank()} why=\"$cloudWhy\"")
        suspend fun cloud(): Pair<T?, String>? {
            if (!cloud || ep == null || key.isBlank()) return null
            val t = System.currentTimeMillis()
            // 9 Nov 2026: what fits Groq's per-minute limit (prompt + max_tokens), not a 32K context; a long reply
            // gets 60 s instead of 25 s; a used-up minute is waited out briefly instead of being refused.
            val promptTok = GroqChatProvider.estimateTokens(system + "\n" + cloudPrompt) + 16
            val budget = ArogyaGroqMinute.cap(ArogyaBudget.tokens(cloudTokens, lang, ArogyaBudget.estimatePromptTokens(cloudPrompt.length), ArogyaBudget.CLOUD_CONTEXT, feature, "cloud"), promptTok)
            val deadline = if (budget > LONG_REPLY_TOKENS) CLOUD_LONG_DEADLINE_MS else CLOUD_DEADLINE_MS
            ArogyaGroqMinute.waitMs(promptTok + budget).takeIf { it in 1..ArogyaGroqMinute.MAX_WAIT_MS }?.let { w ->
                Log.i(TAG, "AROGYA_GROQ_MINUTE_WAIT feature=$feature ms=$w tokens=${promptTok + budget}"); delay(w) }
            ArogyaGroqMinute.record(promptTok + budget)
            Log.i(TAG, "AROGYA_CLOUD_BUDGET feature=$feature prompt≈$promptTok maxTokens=$budget deadlineS=${deadline / 1000}")
            val client = OpenAiCompatibleClient(ep, { key }, deadline.toInt())
            val raw = if (onDelta == null) {
                ArogyaCloud.withDeadline(deadline, feature) { client.complete(system, cloudPrompt, budget) }
            } else {
                val streamed = ArogyaCloud.withDeadline(deadline, feature) { runBlocking { client.completeStream(system, cloudPrompt, budget, emit) } }
                if (!streamed.isNullOrBlank()) streamed
                else if (coroutineContext.isActive) ArogyaCloud.withDeadline(deadline, feature) { client.complete(system, cloudPrompt, budget) }
                else null
            }
            Log.i(TAG, "AROGYA_ROUTE feature=$feature engine=cloud model=${ep.model} ok=${raw != null} streamed=${onDelta != null} ms=${System.currentTimeMillis() - t}")
            if (raw == null) { cloudWhy = "no reply within ${deadline / 1000} s or the call failed"; return null }
            val ok = accept(raw)
            if (ok == null) { cloudWhy = "its answer did not pass the check against the source"; return null }
            return ok to ep.label
        }
        suspend fun phone(): Pair<T?, String>? = try {
            val model = readyModel(); val t = System.currentTimeMillis()
            // 8 Nov 2026: clamp against the model's active window (8,192 when the KB extension is on), not an
            // assumed 4,096, and count Devanagari prompt text properly.
            // 9 Nov 2026 (AD): the real KV size when the engine reported a smaller one than it was opened with.
            val ctx = model.usableContextTokens.takeIf { it > 0 } ?: ArogyaBudget.PHONE_CONTEXT
            // A prompt the phone cannot hold is refused before any word: say so instead of trying twice.
            val known = model.learnedKbCapacityTokens
            if (known > 0 && ArogyaKvCapacity.estimateTokens(prompt) + 256 > known) {
                throw IllegalStateException("prompt_too_long estTokens=${ArogyaKvCapacity.estimateTokens(prompt)} capacity=$known")
            }
            val budget = ArogyaBudget.tokens(maxTokens, lang, promptTokensOf(prompt) + 64, ctx, feature, "phone")
            val limit = if (feature in EXTRACT_FEATURES) EXTRACT_LIMIT_MS else 300_000L
            val raw = generate(prompt, model, budget, onPartial = if (onDelta == null) null else emit, limitMs = limit).orEmpty()
            Log.i(TAG, "AROGYA_ROUTE feature=$feature engine=phone ms=${System.currentTimeMillis() - t} chars=${raw.length}")
            val label = "On-phone model (offline): " + (model.loadedModelFileName?.substringBeforeLast('.') ?: "local")
            // 8 Nov 2026: one retry when the phone's reply could not be used (prose instead of JSON, a cut
            // reply); the same checks apply to the retry.
            accept(raw)?.let { it to label } ?: run {
                if (emitted) { emitted = false; onDelta?.invoke("") }
                val raw2 = generate(prompt + RETRY_SUFFIX, model, budget, onPartial = if (onDelta == null) null else emit, limitMs = limit).orEmpty()
                val ok2 = accept(raw2)
                Log.i(TAG, "AROGYA_ROUTE_RETRY feature=$feature ok=${ok2 != null} chars=${raw2.length}")
                ok2?.let { it to label }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "AROGYA_ROUTE feature=$feature engine=phone failed ${e.message?.take(120)}"); null }
        for (engine in ArogyaRouting.order(c?.cloudFirst == true, cloud && ep != null && key.isNotBlank())) {
            (if (engine == "cloud") cloud() else phone())?.let { r ->
                // Cloud first, but the phone answered: the label says which cloud model was tried and why it was not used.
                if (engine == "phone" && c?.cloudFirst == true && cloud && cloudWhy.isNotBlank()) {
                    val name = (ep ?: appContext?.let { ArogyaCloud.textEndpoint(it) })?.label?.substringBefore(" —") ?: "Qwen3.8 27B"
                    return r.first to "$name • online — not used: $cloudWhy · ${r.second}"
                }
                return r
            }
            if (emitted) { emitted = false; onDelta?.invoke("") }
        }
        return null to ""
    }
    fun cancel() { runtime.requestCancelActiveGeneration("arogya_user_cancel"); runtime.localModelManager?.requestCancelActiveGeneration("arogya_user_cancel") }
    /** Gap item 2: drop the in-flight cloud call when the network dies; local work continues. */
    fun cancelCloud() { runtime.cancelCloudCall() }

    /**
     * Ask SOP "New conversation": drops this visit's runtime session context (pocketRagSessions
     * + saved turns) so the next question starts fresh. lastVisitId is kept: the cleared
     * session belongs to this visit, and the next answer re-seeds it.
     */
    suspend fun newConversation() {
        runCatching { runtime.startNewChat(clearHistory = true, mode = "NORMAL") }
        Log.i(TAG, "AROGYA_SOP_NEW_CONVERSATION visit=$lastVisitId")
    }

    /** One line for the AI page: which model file is loaded and whether the guide is selected. */
    fun modelStatus(): String {
        val m = runtime.localModelManager
        val name = m?.loadedModelFileName?.substringBeforeLast('.') ?: "not loaded yet"
        val state = when { m == null -> "no local model"; m.permanentlyDisabled -> "disabled"; m.isReady -> "ready"; else -> "loading" }
        val kb = runCatching { features.isKnowledgeEnabled() }.getOrDefault(false)
        return "Language model: $name ($state) · Gemma 3n, runs offline\nASHA guide: " + (if (kb) "selected, searchable offline" else "not selected yet")
    }

    /** On-phone Meta vision stack (MobileSAM + DINOv2) for offline drug-kit counting. */
    fun vision(): MetaVisionEngine = runtime.metaVision
    /** The on-phone Gemma model (its vision is used for photo descriptions and kit-count estimates). */
    fun localModel(): LocalModelManager? = runtime.localModelManager

    /** True when the on-phone counting estimate can run: Gemma's vision is ready (the DINOv2/SAM counter is retired). */
    fun visionOk(): Boolean = runtime.localModelManager?.isReadyForVision == true

    /** Structured twin of modelStatus() for the AI table: short loaded file name ("" when none) + ready flag. */
    fun modelInfo(): Pair<String, Boolean> {
        val m = runtime.localModelManager
        return (m?.loadedModelFileName?.substringBeforeLast('.').orEmpty()) to
            (m != null && m.isReady && !m.permanentlyDisabled)
    }

    /** Loads the model and selects the ASHA guide as soon as ArogyaDoot opens, so the first question does not wait ~30 s. */
    /** R9 marker of the installed guide pack (2 = v2, 0 = v1, -1 = none). */
    fun guideVersion(): Int = runCatching { features.guideVersion() }.getOrDefault(-1)

    /** Official guides library rows + topic headings (IO thread; empty when no pack installed). */
    suspend fun guideLibrary(): List<GuideDoc> = withContext(Dispatchers.IO) { runCatching { features.guideLibrary() }.getOrDefault(emptyList()) }
    suspend fun guideTopics(sourceName: String): List<String> = withContext(Dispatchers.IO) { runCatching { features.guideTopics(sourceName) }.getOrDefault(emptyList()) }

    /** Evidence size from the profile switch (1 = R1 6 x 1100, 2 = 5 x 900 candidate, 0 = legacy 4 x 900). */
    private fun applyEvidenceSize() {
        when (ArogyaFlags.r1Profile) {
            2 -> { GenericKbKeyFacts.WINDOW_PASSAGES = 5; GenericKbKeyFacts.WINDOW_CHARS = 900 }
            0 -> { GenericKbKeyFacts.WINDOW_PASSAGES = 4; GenericKbKeyFacts.WINDOW_CHARS = 900 }
            // 8 Nov 2026: 1,500 chars per passage (was 1,100) so a list of signs or steps is not cut
            // in half; still 6 passages (~9,000 chars, well inside Gemma's 8K guide context).
            else -> { GenericKbKeyFacts.WINDOW_PASSAGES = 6; GenericKbKeyFacts.WINDOW_CHARS = 1_500 }
        }
    }

    suspend fun warmUp(appContext: Context? = null) {
        applyEvidenceSize()
        val t = System.currentTimeMillis()
        val model = runCatching { ensureLocalModel() }.getOrDefault(false)
        val kb = runCatching { features.ensureKnowledgeReady() && (features.isKnowledgeEnabled() || features.setKnowledgeEnabled(true)) }.getOrDefault(false)
        val search = kb && ArogyaFlags.speedWarmSearch && runCatching { features.warmKnowledgeSearch() }.getOrDefault(false)
        Log.i(TAG, "AROGYA_WARMUP model=$model kb=$kb ms=${System.currentTimeMillis() - t}")
        Log.i(TAG, "AROGYA_WARMUP_SEARCH warmed=$search")
        // Part C1: the 100 verified questions embed in the background (same call the KB uses).
        if (appContext != null) runCatching {
            val ctx = appContext.applicationContext
            val asset = ctx.assets.open("verified_answers.json").use { it.readBytes() }
            val engine = features.semanticEngine
            ArogyaVerifiedAnswers.warmAsync(asset, java.io.File(ctx.filesDir, ArogyaVerifiedAnswers.CACHE_NAME)) { q ->
                runBlocking { engine.embed(q) }
            }
        }.onFailure { Log.w(TAG, "AROGYA_GOLD warm kick failed ${it.message}") }
    }

    // ── Attachments for a guide question (ArogyaDoot's pending-attachment path, unchanged) ──
    /** Reads a photo or PDF on the phone and holds it for the next question. Returns its name, or null if unreadable. */
    suspend fun attach(uri: android.net.Uri): String? = mutex.withLock {
        val mgr = attachments ?: return@withLock null
        val staged = mgr.stageUri(uri) ?: return@withLock null
        // Guide questions run with the knowledge base on, and that path never hands pixels to the
        // on-phone vision model. So Gemma looks at the photo now and its factual description joins
        // the photo's OCR as on-phone evidence (offline; nothing is sent anywhere).
        if (staged.kind == Phase1AttachmentKind.IMAGE) {
            val bmp = staged.visionBitmaps.firstOrNull { !it.isRecycled && it.width > 0 && it.height > 0 }
            val seen = if (bmp != null) ArogyaLocalVision.describe(runtime.localModelManager, bmp) else null
            if (seen != null) mgr.replacePending(staged.copy(
                localVisualContext = (staged.localVisualContext.trim() + "\n[" + ArogyaLocalVision.LABEL + " — an AI description, check it]\n" + seen).trim(),
                localEvidenceSubstantial = true))
        }
        staged.displayName
    }
    fun attachedNames(): List<String> = attachments?.peekPendingAll()?.map { it.displayName }.orEmpty()

    /** A PDF / Word / slides / text file is attached (not only photos): the composer shows the guide toggle. */
    fun hasDocumentAttached(): Boolean = attachments?.peekPendingAll()?.any { it.kind != Phase1AttachmentKind.IMAGE } == true

    /**
     * 8 Nov 2026 "Use ASHA guide too" toggle for an attached document. On (default): the answer comes from
     * the ASHA guides and the document's matching lines join the search. Off: the answer comes from the
     * document itself, labelled "From your document, not the ASHA guides".
     */
    @Volatile var useGuideWithDocument: Boolean = true
    /** Plain-words reason the last attach failed ("" = unknown). */
    fun lastAttachProblem(): String = attachments?.lastStageProblem.orEmpty()
    fun clearAttachments() { attachments?.clearPending() }

    suspend fun run(request:ArogyaAiRequest):ArogyaAiResult {
        applyEvidenceSize()
        val started = System.nanoTime()
        // 1. Danger-sign rules: instant, no model. URGENT stops here unless the worker asked for guidance after it.
        val danger = ArogyaDangerSigns.evaluate(request.question, request.visit)
        val alert = ArogyaDangerSigns.message(danger)
        if (danger.urgent && !request.afterSafetyPrompt && alert != null) {
            val ms = (System.nanoTime()-started)/1_000_000
            Log.i(TAG, "AROGYA_TIMING event=urgent_warning ms=$ms")
            return ArogyaAiResult(alert, "Immediate safety rules • no model used", ms, safetyPrompt=true)
        }
        val result = mutex.withLock {
            when (request.task) {
                ArogyaAiTask.EXPLAIN -> askArogyaDoot(request)
                else -> organiseNotes(request)
            }
        }
        Log.i(TAG, "AROGYA_TIMING event=ai_result task=${request.task} total_ms=${result.elapsedMs} accepted=${!result.sourceOnly}")
        // The warning was already shown on screen; the answer carries only a one-line reminder, never the full warning again.
        // The short warning stays on screen above the answer; it is never repeated inside the answer.
        return result
    }

    /** The visit's age group in plain words, so ArogyaDoot retrieves guidance for the right population. */
    private fun visitContext(v: ArogyaVisit): String {
        val days = ArogyaWorkflow.parseDate(v.birthDate)?.let { b -> ArogyaWorkflow.parseDate(v.date)?.let { java.time.temporal.ChronoUnit.DAYS.between(b, it) } }
        return when {
            days != null && days in 0..28 -> "newborn, $days days old"
            days != null && days in 29..59 -> "young infant, $days days old"
            days != null && days < 365 * 5 -> "child, ${days / 30} months old"
            v.kind == ArogyaKind.NEWBORN -> "newborn"
            v.kind == ArogyaKind.CHILD -> "young child"
            else -> ""
        }
    }

    private suspend fun askArogyaDoot(request: ArogyaAiRequest): ArogyaAiResult {
        val t0 = System.currentTimeMillis()
        val question = request.question.trim()
        check(question.length <= 700) { "Shorten the question to 700 characters." }
        check(question.isNotEmpty() || attachments?.peekPendingAll().orEmpty().isNotEmpty()) { "Type or speak a question first." }
        // Load / warm the local model first, exactly like ArogyaDoot's chat service does before a turn.
        val modelReady = runCatching { ensureLocalModel() }.getOrDefault(false)
        Log.i(TAG, "AROGYA_LOCAL_MODEL_READY $modelReady")
        // Select the installed knowledge base exactly as ArogyaDoot's own KB mode does.
        check(features.ensureKnowledgeReady()) { "The ASHA guide is not ready. Open Setup → Install ASHA guide. Your visit is saved." }
        if (!features.isKnowledgeEnabled()) check(features.setKnowledgeEnabled(true)) { "Could not select the ASHA guide. Open Setup and select it. Your visit is saved." }
        // One ArogyaDoot conversation per visit: follow-ups stay within this visit, never another household's.
        if (lastVisitId != request.visit.id) {
            // A new household: the previous visit's attachment copies (card photos, PDFs) are deleted, not kept on disk.
            val previous = runCatching { runtime.currentConversationId() }.getOrDefault("")
            runCatching { runtime.startNewChat(clearHistory = true, mode = "NORMAL") }
            if (lastVisitId != null) runtime.forgetConversationAttachments(previous)
            lastVisitId = request.visit.id
        }
        runCatching { runtime.ensureConversationOpen() }
        // The question goes to ArogyaDoot as the worker wrote it (an appended age phrase split retrieval
        // into two clauses on the phone: 30 s instead of 15 s).
        val asked = question.ifBlank { "What does this attachment show, and what should the ASHA do?" }
        val online = request.onlineCheck && !ArogyaOnlinePolicy.localOnly(question)
        // ArogyaDoot's own turn: AndroLLM + PocketSage + semantic search + grounding, unchanged.
        val turnFiles = attachments?.claimPendingAttachmentsForTurn().orEmpty()
        runCatching { runtime.lastTurnEarlierAttachment = "" }
        if (turnFiles.isNotEmpty()) Log.i(TAG, "AROGYA_ATTACHMENTS count=${turnFiles.size} kinds=${turnFiles.joinToString { it.kind.name }}")
        val docTurn = turnFiles.any { it.kind != Phase1AttachmentKind.IMAGE }
        runtime.useGuideWithDocument = useGuideWithDocument
        if (docTurn) Log.i(TAG, "AROGYA_DOCUMENT_GUIDE useGuide=$useGuideWithDocument")
        request.onProgress?.invoke(ArogyaProgress("search"))
        val draft = StringBuilder()
        var answered = false
        var firstWordsMs = -1L
        suspend fun doChat(): AndroBaselineRuntime.TurnResult = withTimeout(ASK_TIMEOUT_MS) {
            runtime.chat(if (online) ArogyaOnlinePolicy.scrub(asked, request.visit) else asked, forceExternalInformation = online, turnAttachments = turnFiles,
                onLocalDelta = { d ->
                    if (firstWordsMs < 0 && d.isNotBlank()) firstWordsMs = System.currentTimeMillis() - t0
                    synchronized(draft) { draft.append(d) }; request.onProgress?.invoke(ArogyaProgress("write", synchronized(draft) { draft.toString() })) },
                // Review fix: when the checker rejects a streamed draft (cloud or local), clear it
                // so the next answer never appends to rejected text; the card shows "checking…".
                onLocalAbort = { synchronized(draft) { draft.setLength(0) }; request.onProgress?.invoke(ArogyaProgress("checking")) })
        }
        // Part C3: when the model fails or times out and a verified answer matches
        // strongly, show its stored copy with the "AI unavailable" label instead of
        // an error. Anything else (cancel, setup checks) still throws as before.
        fun storedFallback(reason: String): ArogyaAiResult? {
            val elapsed = System.currentTimeMillis() - t0
            // Same query C2 matched seconds ago: match() reuses its cached vector.
            val scored = runCatching { ArogyaVerifiedAnswers.match(question) }.getOrNull() ?: return null
            val hindiQ = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
            val built = ArogyaGoldPin.storedCopyIfStrong(scored, hindiQ) ?: return null
            Log.i(TAG, "AROGYA_GOLD fallback id=${scored.item.id} score=${"%.3f".format(java.util.Locale.US, scored.score)} reason=$reason")
            val first = scored.item.sources.firstOrNull()
            return ArogyaAiResult(built.first, "ArogyaDoot • stored copy", elapsed, sources = built.second,
                quote = first?.quote.orEmpty(),
                quoteSource = if (first == null) "" else "${first.title}, page ${first.page}",
                timingsMs = mapOf("pramaan_turn" to elapsed), tier = 1, storedCopy = true)
        }
        // UI2 speed pass: an offline (phone-model) turn reads at most 4,000 chars of evidence.
        // Cloud turns (online, or the Cloud-first setting) keep the full budget.
        GenericKnowledgeContextProvider.phoneEvidenceCap = !online && runtime.answerPolicy() != AnswerPolicy.CLOUD_FIRST
        // 8 Nov 2026: the patient's age group from the visit record joins the guide search (extra view only).
        GenericKnowledgeContextProvider.visitAgeHint = visitContext(request.visit)
        if (GenericKnowledgeContextProvider.visitAgeHint.isNotBlank()) Log.i(TAG, "AROGYA_VISIT_AGE ${GenericKnowledgeContextProvider.visitAgeHint}")
        val turn = try {
            try {
                doChat()
            } catch (e: Exception) {
                // A real cancel (back button, Stop) must never trigger a retry — but a cancel
                // caused by the network dying mid-call falls back to the phone instead.
                if (e is CancellationException && e !is TimeoutCancellationException && !ArogyaNetWatch.justLost()) throw e
                if (runtime.answerPolicy() != AnswerPolicy.CLOUD_FIRST && !ArogyaNetWatch.justLost()) {
                    storedFallback("model_failed")?.let { return it }
                    throw e
                }
                Log.w(TAG, "AROGYA_ASK_FALLBACK cloud failed (${e.message?.take(100)}), retrying on the phone")
                // In-memory override only: the saved setting is never touched, so a
                // crash mid-retry cannot leave it changed.
                runtime.policyOverride = AnswerPolicy.LOCAL_FIRST
                GenericKnowledgeContextProvider.phoneEvidenceCap = true
                try { doChat() } finally { runtime.policyOverride = null }
            }.also { answered = true }
        } catch (e: TimeoutCancellationException) {
            storedFallback("timeout")?.let { return it }
            Log.w(TAG, "AROGYA_ASK_TIMEOUT after ${ASK_TIMEOUT_MS / 1000}s")
            error("The answer is taking too long. Try again, or ask a shorter question — your visit is saved.")
        } catch (e: Exception) {
            // Retry-path failure (not a cancel, not a setup check — those are outside this try).
            if (e is CancellationException) throw e
            storedFallback("model_failed")?.let { return it }
            throw e
        } finally {
            GenericKnowledgeContextProvider.phoneEvidenceCap = false
            GenericKnowledgeContextProvider.visitAgeHint = ""
            // Used attachments are released; on failure they stay attached for a retry.
            attachments?.releaseTurnAttachments(turnFiles.map { it.id }, consumed = answered)
        }
        // 8 Nov 2026: a Check online that ended on the phone (online call refused, too slow or rejected)
        // says so; the phone's answer is never shown in the Online tab as if it came from online.
        if (online && turn.provider == AndroBaselineRuntime.Provider.LOCAL) {
            val outcome = PocketRagEngine.lastCloudOutcome
            val why = runCatching { ArogyaCloudOutcome.why(outcome, runtime.cloudEngineLabel(), runtime.groqProviderStatus().name) }
                .getOrNull()?.substringAfter("not used: ", "")?.trim().orEmpty()
            Log.w(TAG, "AROGYA_CHECK_ONLINE_FAILED outcome=$outcome")
            error(if (request.hindi) "ऑनलाइन जाँच पूरी नहीं हुई" + (if (why.isNotBlank()) " — $why" else "") + "। ऊपर का ऑफ़लाइन उत्तर वैसा ही है।"
                  else "The online check could not finish" + (if (why.isNotBlank()) " — $why" else "") + ". The offline answer above is unchanged.")
        }
        return buildResult(turn, question, System.currentTimeMillis() - t0, firstWordsMs, detail = false, onlineCheck = request.onlineCheck,
            fromDocument = docTurn && !useGuideWithDocument, visitAge = runCatching { visitContext(request.visit) }.getOrDefault(""))
    }

    /**
     * Item 4 "More detail": the same question regenerates from the SAME passages (the
     * runtime's detail cache; no new search) with the longer detail length line, up to the
     * model ceiling. The caller replaces the answer in place; sources come from the same
     * evidence, so the source line stays the same.
     */
    suspend fun moreDetail(question: String, visit: ArogyaVisit, onProgress: ((ArogyaProgress) -> Unit)? = null): ArogyaAiResult = mutex.withLock {
        val t0 = System.currentTimeMillis()
        val asked = question.trim()
        check(asked.isNotEmpty()) { "Ask a question first." }
        runCatching { ensureLocalModel() }
        check(features.ensureKnowledgeReady()) { "The ASHA guide is not ready. Open Setup → Install ASHA guide. Your visit is saved." }
        if (!features.isKnowledgeEnabled()) check(features.setKnowledgeEnabled(true)) { "Could not select the ASHA guide. Open Setup and select it. Your visit is saved." }
        if (lastVisitId != visit.id) { runCatching { runtime.startNewChat(clearHistory = true, mode = "NORMAL") }; lastVisitId = visit.id }
        runCatching { runtime.ensureConversationOpen() }
        onProgress?.invoke(ArogyaProgress("search"))
        val draft = StringBuilder()
        var firstWordsMs = -1L
        // 8 Nov 2026: More detail reads more of the guide: 8 passages for this turn (normal answers 6),
        // the full candidate pool, and the visit's age group; restored after the turn.
        if (ArogyaFlags.detailFreshSearch) { GenericKbKeyFacts.WINDOW_PASSAGES = DETAIL_PASSAGES; GenericKbKeyFacts.WINDOW_CHARS = 1_500 }
        // 9 Nov 2026: on the phone, More detail reads the same evidence cap as a normal answer (4,500 chars since 11 Nov, AI1):
        // Gemma's 8K window is shared by evidence and answer, and 12,000 characters left a Hindi detailed answer
        // less room than the normal one. Online (Cloud first) keeps the full evidence.
        GenericKnowledgeContextProvider.phoneEvidenceCap = runtime.answerPolicy() != AnswerPolicy.CLOUD_FIRST
        GenericKnowledgeContextProvider.visitAgeHint = visitContext(visit)
        val turn = try {
            withTimeout(ASK_TIMEOUT_MS) {
                runtime.chat(asked, moreDetail = true,
                    onLocalDelta = { d ->
                        if (firstWordsMs < 0 && d.isNotBlank()) firstWordsMs = System.currentTimeMillis() - t0
                        synchronized(draft) { draft.append(d) }; onProgress?.invoke(ArogyaProgress("write", synchronized(draft) { draft.toString() })) },
                    onLocalAbort = { synchronized(draft) { draft.setLength(0) }; onProgress?.invoke(ArogyaProgress("checking")) })
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "AROGYA_ASK_TIMEOUT detail after ${ASK_TIMEOUT_MS / 1000}s")
            error("The fuller answer is taking too long. Your first answer is unchanged.")
        } finally {
            applyEvidenceSize()
            GenericKnowledgeContextProvider.visitAgeHint = ""
        }
        return buildResult(turn, asked, System.currentTimeMillis() - t0, firstWordsMs, detail = true, onlineCheck = false,
            visitAge = runCatching { visitContext(visit) }.getOrDefault(""))
    }

    /** Shared turn → card result: breakdown log, tiers, sources, quote, dose check. */
    private suspend fun buildResult(
        turn: AndroBaselineRuntime.TurnResult,
        question: String,
        elapsed: Long,
        firstWordsMs: Long,
        detail: Boolean,
        onlineCheck: Boolean,
        fromDocument: Boolean = false,
        /** The visit's age ("child, 8 months old"), for the dose table when the question names no age. */
        visitAge: String = ""
    ): ArogyaAiResult {
        // Joined offline with the stage lines by timestamp: POCKET_RAG_SEARCH_MS (search/rerank),
        // POCKET_RAG_EVIDENCE_MS, POCKET_RAG_LOCAL_GEN_MS / POCKET_RAG_CLOUD_MS (first words),
        // LLAMA_GENERATE (prompt/prefill/generation), POCKET_RAG_GATE_MS (checker), recovery lines.
        Log.i(TAG, "AROGYA_ASK_BREAKDOWN detail=$detail online=${turn.provider == AndroBaselineRuntime.Provider.CLOUD} totalMs=$elapsed firstWordsMs=$firstWordsMs sources=${turn.sourceIds.size} profile=${ArogyaFlags.r1Profile} chars=${turn.text.length}")
        val online = turn.provider == AndroBaselineRuntime.Provider.CLOUD
        val mode = if (onlineCheck) "check_online" else if (online) "cloud" else "offline"
        val notFoundMarker = turn.sourceIds.firstOrNull { it.startsWith(KnowledgeSourcePresentation.NOT_FOUND_PREFIX) }
        val liveWeb = turn.sourceIds.any { it.startsWith("freshness:") }
        val hasGuideAnswer = turn.text.trim().isNotBlank() && notFoundMarker == null && !liveWeb
        // UI6: the model's [S1]/[1] tags are dropped from the shown text; the card draws its own
        // tappable superscripts from the checked citations (segments are built from this same text).
        // 11 Nov 2026 (AO): a copy of the question written as a title before "Answer:" is dropped.
        val text = ArogyaAnswerFormat.stripCitationMarkers(ArogyaAnswerFormat.dropEchoedQuestion(turn.text.trim(), question))
        val hasAnswer = text.isNotBlank()
        val provider = when (turn.provider) {
            AndroBaselineRuntime.Provider.LOCAL -> "ArogyaDoot • offline (${runtime.localModelManager?.loadedModelFileName?.substringBeforeLast('.') ?: "local model"})".let { local ->
                // Cloud first was on but the phone answered: say why, so the switch is never silently ignored.
                if (runtime.answerPolicy() != AnswerPolicy.CLOUD_FIRST || onlineCheck) local
                else ArogyaCloudOutcome.why(PocketRagEngine.lastCloudOutcome, runtime.cloudEngineLabel(), runCatching { runtime.groqProviderStatus().name }.getOrDefault(""))?.let { "$it · $local" } ?: local }
            AndroBaselineRuntime.Provider.CLOUD -> "${runtime.cloudEngineLabel()} • online"
            else -> "ArogyaDoot"
        }
        // Sources exactly as ArogyaDoot presents them: document rows (title, pages, official link) and the
        // passages behind each row with the sentences the answer came from.
        val rows = runCatching { KnowledgeSourcePresentation.visibleSources(turn.sourceIds) }.getOrDefault(emptyList())
        val passages = runCatching { KnowledgeSourcePresentation.passagesForRows(turn.sourceIds) }.getOrDefault(emptyList())
        val sources = rows.mapIndexed { i, row ->
            val url = KnowledgeSourcePresentation.openTarget(row).orEmpty()
            val label = row.substringBefore(" | ").trim()
            val title = KnowledgeSourcePresentation.cleanTitle(label.substringBefore(" — ").trim())
            var pages = label.substringAfter(" — ", "").trim()
            if (pages.isBlank()) {
                // Every source shows a page: recover it from this row's own kbref when the
                // grouped row lost it (diagnostic tells Batch 7 where it went missing).
                val sid = "S${i + 1}"
                pages = turn.sourceIds.asSequence()
                    .filter { it.startsWith("kbref:", ignoreCase = true) && it.contains(sid, ignoreCase = true) }
                    .mapNotNull { Regex("(?i)—\\s*pages?\\s+\\d+(?:\\s*[–-]\\s*\\d+)?").find(it)?.value?.trim() }
                    .firstOrNull().orEmpty()
                Log.i(TAG, "AROGYA_SOURCE_PAGE_FALLBACK row=$sid pages='$pages' title='${title.take(60)}'")
            }
            val used = passages.getOrNull(i).orEmpty()
            ArogyaSource("${i + 1}", title, pages, used.joinToString("\n\n") { it.text }, url, used.flatMap { it.highlights }.distinct())
        }
        // Item 5b Tier 2: online and the guides are silent — an allowlisted official page
        // may still ground the answer. Anything else falls through to Tier 3 (R8 stays on).
        if (!hasGuideAnswer && liveWeb && online) {
            val tier2 = runCatching { withContext(Dispatchers.IO) { buildTier2(question, turn.text, sources, elapsed) } }.getOrNull()
            if (tier2 != null) {
                Log.i(TAG, "AROGYA_ANSWER_TIER tier=2 mode=$mode sources=guide:${sources.size},web:${tier2.sources.count { it.kind == "web" }}")
                return tier2
            }
        }
        // R8 guideline-only mode (Ask SOP) = Tier 3: a live-web or general answer —
        // recognised by the kbnotfound:/freshness: markers, never by verification:optional —
        // becomes the ANM message with the closest guide topics. Grounded and "Check online"
        // answers pass through.
        if (ArogyaFlags.r8GuidelineOnly && (notFoundMarker != null || liveWeb)) {
            val topics = if (notFoundMarker != null) runCatching { KnowledgeSourcePresentation.topicsOf(notFoundMarker) }.getOrDefault(emptyList()) else emptyList()
            // 8 Nov 2026: when an answer exists (the phone's general answer, or a web answer), show it
            // under a clear "not from your guides" label with Check online recommended (the card
            // highlights that button for tier 3), instead of hiding it behind the ANM message.
            val hindiQ0 = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
            val body = ArogyaNotFromGuide.answerBody(turn.text, web = liveWeb)
            if (ArogyaFlags.r8LabelledAnswer && body != null) {
                Log.i(TAG, "AROGYA_NOT_FROM_GUIDE kind=${if (liveWeb) "live_web" else "general"} chars=${body.length} topics=${topics.size}")
                Log.i(TAG, "AROGYA_ANSWER_TIER tier=3 mode=$mode labelled=true")
                return ArogyaAiResult(ArogyaNotFromGuide.labelled(body, topics, web = liveWeb, hindi = hindiQ0), provider, elapsed,
                    online = online, sourceOnly = true,
                    timingsMs = mapOf("pramaan_turn" to elapsed), r8Blocked = false, r8Topics = topics, tier = 3)
            }
            Log.i(TAG, "AROGYA_R8_BLOCKED kind=${if (liveWeb) "live_web" else "general"} topics=${topics.size}")
            val hindiQ = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
            val msg = if (hindiQ) "यह आपकी आधिकारिक गाइड या आधिकारिक सरकारी वेबसाइटों में नहीं है — अपनी ANM से पूछें।"
            else "This is not in your official guides or official government websites — ask your ANM."
            val topicLine = if (topics.isEmpty()) "" else (if (hindiQ) "\n\nगाइड में सबसे करीबी विषय: " else "\n\nClosest guide topics: ") + topics.joinToString("; ")
            Log.i(TAG, "AROGYA_ANSWER_TIER tier=3 mode=$mode sources=guide:${sources.size},web:0")
            return ArogyaAiResult(msg + topicLine, "ArogyaDoot", elapsed,
                online = online, sourceOnly = true,
                timingsMs = mapOf("pramaan_turn" to elapsed), r8Blocked = true, r8Topics = topics, tier = 3)
        }
        // 9 Nov 2026 (AD): none of the answer's statements was found in the passages (proof 0/n): the
        // "guide says" box would show an unrelated passage (a mother's urine after delivery for a child's
        // question) as if it backed the answer. No quote then, and the answer says plainly it is not confirmed.
        val proof = runCatching { AnswerProof.parseMarker(turn.sourceIds) }.getOrNull()
        // The proof compares answer sentences with the (English) passages; a Hindi answer scores low against
        // English text even when it says what the guide says (phone log 22:19: menstrual-pain answer, 0/7). Only a
        // same-script answer can be called unconfirmed.
        val sameScript = KbReplyText.dominantScript(text) != KbReplyText.Script.DEVANAGARI
        val unproven = sameScript && proof != null && proof.second > 0 && proof.first == 0
        val quote = if (unproven) null else runCatching { KnowledgeSourcePresentation.quoteOf(turn.sourceIds) }.getOrNull()
        if (unproven) Log.w(TAG, "AROGYA_UNPROVEN_ANSWER proof=${proof?.first}/${proof?.second} quote=dropped")
        // Dose safety: every dose answer is logged for the M1 human review list; a dose
        // whose evidence context names only other age groups carries an ANM caution line.
        var finalText = if (hasAnswer) text else "No answer came back from the guide. Rephrase the question or ask your ANM. Your visit is unchanged."
        // 11 Nov 2026 (AP): the ASHA sees when an answer used the photo or document she attached to an earlier question.
        val earlier = runCatching { runtime.lastTurnEarlierAttachment }.getOrDefault("")
        if (earlier.isNotBlank() && hasAnswer) {
            val hi = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
            val what = if (earlier == "photo") (if (hi) "पहले लगाई फ़ोटो" else "the photo you attached earlier")
                else (if (hi) "पहले लगाया दस्तावेज़" else "the document you attached earlier")
            finalText = (if (hi) "📎 यह उत्तर $what को भी ध्यान में रखता है।" else "📎 This answer also uses $what.") + "\n\n" + finalText
            Log.i(TAG, "AROGYA_ATTACHMENT_FOLLOW_UP used=$earlier")
        }
        if (unproven && hasAnswer && !text.startsWith("⚠")) {
            finalText = (if (KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI)
                "⚠ गाइड के मिले हुए अंश इस उत्तर की पुष्टि नहीं करते — अपनी ANM से ज़रूर पूछें।"
            else "⚠ The guide passages found do not confirm this answer — check with your ANM.") + "\n\n" + finalText
        }
        // 9 Nov audit: also checked when only the ANSWER gives a dose (a symptom question answered with
        // "zinc 20 mg"). Advisory only — a caution line is added, the answer is never withheld.
        if (ArogyaFlags.doseAgeMatch && hasAnswer && (ArogyaDoseCheck.isDoseQuestion(question) || ArogyaDoseCheck.isDoseQuestion(text) || ArogyaDoseTable.stated(text).isNotEmpty())) {
            val group = ArogyaDoseCheck.askedGroup(question)
            val evidence = sources.joinToString("\n") { it.excerpt }
            val verdict = if (ArogyaDoseCheck.isDoseQuestion(question))
                runCatching { ArogyaDoseCheck.check(question, text, evidence) }.getOrDefault(ArogyaDoseCheck.Verdict.UNCERTAIN)
            else ArogyaDoseCheck.Verdict.UNCERTAIN
            val strength = runCatching { ArogyaDoseCheck.strengthRisk(text, evidence) }.getOrNull()
            // 9 Nov: the guide's own age-specific dose (ArogyaDoseTable, with page) — checked first.
            val tableFinding = if (ArogyaFlags.doseTable) runCatching { ArogyaDoseTable.check(question, text, visitAge) }.getOrNull() else null
            val nums = runCatching { ArogyaAiExtras.numbers(text).distinct().take(6).joinToString(",") }.getOrDefault("")
            Log.i(TAG, "AROGYA_DOSE_CHECK group=${group.name} verdict=${verdict.name} strengthRisk=${strength ?: "-"} table=${tableFinding?.let { "${it.medicine}:${it.reason}:${it.stated}" } ?: "-"} nums=[$nums] q='${question.take(80)}'")
            val hindiQ = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
            when {
                tableFinding != null -> finalText += "\n\n" + ArogyaDoseTable.caution(tableFinding, hindiQ)
                strength != null -> finalText += "\n\n" + ArogyaDoseCheck.strengthCaution(strength, hindiQ)
                verdict == ArogyaDoseCheck.Verdict.MISMATCH -> finalText += "\n\n" + ArogyaDoseCheck.caution(group, hindiQ)
            }
        }
        // "Use ASHA guide too" was off: the answer is from the attached document, and says so first.
        if (fromDocument && hasAnswer) {
            val hindiQ = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
            finalText = (if (hindiQ) "📄 यह आपके दस्तावेज़ से है, ASHA गाइड से नहीं।" else "📄 From your document, not the ASHA guides.") + "\n\n" + finalText
        }
        // Item 5d: per-sentence guide citations (from the model text; the dose caution is app chrome).
        val segments = if (hasAnswer && sources.isNotEmpty()) ArogyaOfficialAnswer.guideSegments(text, sources, turn.text) else emptyList()
        Log.i(TAG, "AROGYA_ANSWER_TIER tier=1 mode=$mode sources=guide:${sources.size},web:0")
        return ArogyaAiResult(
            finalText,
            provider, elapsed, sources = sources, online = turn.provider == AndroBaselineRuntime.Provider.CLOUD,
            checkedAt = if (turn.provider == AndroBaselineRuntime.Provider.CLOUD) java.time.Instant.now().toString() else "",
            sourceOnly = !hasAnswer, timingsMs = mapOf("pramaan_turn" to elapsed),
            quote = quote?.takeIf { !it.mismatch }?.text.orEmpty(), quoteSource = quote?.source.orEmpty(),
            tier = 1, segments = segments
        )
    }

    /**
     * Item 5b Tier 2: the live-web answer is kept only when official pages ground it.
     * Non-allowlisted references are discarded; each kept sentence must occur
     * (normalised) in a fetched official page or a guide passage; medication/dose,
     * urgency-downgrade and patient-assessment sentences fail the whole answer.
     * Null → the caller falls back to Tier 3.
     */
    private suspend fun buildTier2(question: String, webText: String, guideSources: List<ArogyaSource>, elapsed: Long): ArogyaAiResult? {
        val (answer, refs) = ArogyaOfficialSources.parseRefs(webText)
        val clean = ArogyaAnswerFormat.stripCitationMarkers(answer.removePrefix("Not from the selected knowledge base — answered online:").trim())
        if (clean.isBlank() || refs.isEmpty()) return null
        if (ArogyaOnlinePolicy.DOWNGRADE.containsMatchIn(clean) || ArogyaOnlinePolicy.PATIENT_ASSESSMENT.containsMatchIn(clean)) {
            Log.i(TAG, "AROGYA_TIER2_REJECT safety")
            return null
        }
        val pairs = refs.take(3).mapNotNull { ref ->
            if (!ArogyaNetWatch.online) { Log.i(TAG, "AROGYA_NET_LOST cancelled=tier2_fetch"); return null }
            val f = runCatching { withTimeoutOrNull(9_000) { ArogyaOfficialSources.fetch(ref.url) } }.getOrNull()
            if (f == null || f.text.isBlank()) null else ref to f
        }
        if (pairs.isEmpty()) {
            Log.i(TAG, "AROGYA_TIER2_REJECT no_fetched_pages refs=${refs.size}")
            return null
        }
        val segments = ArogyaOfficialAnswer.webSegments(clean, pairs.map { it.second }, guideSources)
        if (segments.isEmpty()) {
            Log.i(TAG, "AROGYA_TIER2_REJECT ungrounded")
            return null
        }
        val hindiQ = KbReplyText.dominantScript(question) == KbReplyText.Script.DEVANAGARI
        val usedWeb = segments.flatMap { it.sourceIds }.filter { it.startsWith("W") }.distinct()
        val usedGuide = segments.flatMap { it.sourceIds }.filter { !it.startsWith("W") }.distinct()
        val webSources = usedWeb.mapNotNull { id ->
            val idx = id.removePrefix("W").toIntOrNull()?.minus(1) ?: return@mapNotNull null
            val (ref, p) = pairs.getOrNull(idx) ?: return@mapNotNull null
            ArogyaSource(id, ref.title.ifBlank { p.title }.ifBlank { p.url },
                if (p.url.lowercase().contains(".pdf")) "PDF" else "Web page",
                p.text.take(2_000), p.url,
                segments.filter { id in it.sourceIds }.map { it.text }.take(6),
                kind = "web", date = p.date, domain = ArogyaOfficialSources.domainOf(p.url))
        }
        // Guide sources cited too (a sentence may match both): guide rows first, then web.
        val citedGuides = usedGuide.mapNotNull { id -> guideSources.firstOrNull { it.id == id } }
        // Newer-guidance flag: a web-only sentence from a page newer than the guide document.
        var conflictLine = ""
        val webOnly = segments.any { seg -> seg.sourceIds.any { it.startsWith("W") } && seg.sourceIds.none { !it.startsWith("W") } }
        if (webOnly) {
            val lib = runCatching { features.guideLibrary() }.getOrDefault(emptyList())
            fun guideDateOf(title: String): String {
                val row = lib.firstOrNull { it.title.equals(title, ignoreCase = true) || it.sourceName.equals(title, ignoreCase = true) }
                    ?: lib.firstOrNull { it.title.contains(title, ignoreCase = true) || title.contains(it.title, ignoreCase = true) }
                return row?.publicationDate?.ifBlank { row?.retrievedUtc }.orEmpty()
            }
            val gDate = citedGuides.map { guideDateOf(it.title) }.firstOrNull { it.isNotBlank() }.orEmpty()
            val newer = webSources.firstOrNull { ArogyaOfficialSources.isNewer(it.date, gDate) }
            if (newer != null) conflictLine = if (hindiQ) "\n\n${newer.domain} (${newer.date}) पर नई जानकारी मिली है — अपनी ANM से पक्का करें।"
            else "\n\nNewer guidance found on ${newer.domain} (${newer.date}) — confirm with your ANM."
        }
        val body = segments.joinToString(" ") { it.text } + conflictLine
        Log.i(TAG, "AROGYA_TIER2_ACCEPT sentences=${segments.size} web=${webSources.size} guides=${citedGuides.size} conflict=${conflictLine.isNotBlank()}")
        return ArogyaAiResult(body, "ArogyaDoot • online (official website)", elapsed,
            sources = citedGuides + webSources, online = true,
            checkedAt = java.time.Instant.now().toString(),
            timingsMs = mapOf("pramaan_turn" to elapsed), tier = 2, segments = segments)
    }

    private suspend fun organiseNotes(request: ArogyaAiRequest): ArogyaAiResult {
        val start = System.currentTimeMillis()
        val notes = (if (request.task == ArogyaAiTask.HANDOVER) request.visit.notes + "\n" + request.visit.referralNote else request.visit.notes).trim()
        check(notes.isNotBlank()) { "Speak or type your visit observations first." }
        check(notes.length <= 6500) { "This recording is too long for one AI review. Keep the original notes and review them manually." }
        val (items, by) = route("organise_notes", ArogyaCapture.prompt(notes), 1000, ArogyaCapture.prompt(ArogyaOnlinePolicy.scrub(notes, request.visit)), cloudTokens = 2000, lang = ArogyaBudget.langOf(notes)) { ArogyaCapture.parse(it, notes) }
        items ?: error("The AI could not organise this recording. Your original words are saved; edit them or retry.")
        return ArogyaAiResult(ArogyaCapture.render(items), "ArogyaDoot • $by", System.currentTimeMillis() - start, canCopyToNotes = true, captureItems = items)
    }

    private suspend fun generate(prompt: String, model: LocalModelManager, maxTokens: Int = 420, onPartial: ((String) -> Unit)? = null,
                                 limitMs: Long = 300_000L): String? = coroutineScope {
        val expired = AtomicBoolean(false)
        // On-device Llama can legitimately take several minutes on supported phones.
        // Preserve cancellation, but do not abort a healthy local turn after 90 seconds.
        // 11 Nov 2026 (AN): copying details off a photo's text has a 3-minute limit (EXTRACT_LIMIT_MS).
        val watchdog = launch { delay(limitMs); expired.set(true); model.requestCancelActiveGeneration("arogya_time_limit_${limitMs / 1000}s") }
        try {
            val result = model.generateDetachedStream(prompt, maxTokens, "arogya_visit", onPartial)
            check(!expired.get()) { "Local AI exceeded the time limit. No draft was accepted; your visit is unchanged." }
            result
        } catch (e: CancellationException) { model.requestCancelActiveGeneration("arogya_cancel"); throw e }
        finally { withContext(NonCancellable) { watchdog.cancelAndJoin() } }
    }

    private suspend fun readyModel(): LocalModelManager {
        runCatching { ensureLocalModel() }
        val model = runtime.localModelManager
        check(model != null && model.isReady && !model.permanentlyDisabled) { "The local AI model is not ready. Open Setup and load your installed model." }
        return model
    }

    /** Simple words for the family, rewritten from the guide answer only. New numbers are removed; falls back to the guide sentence. */
    suspend fun familyExplain(answer: String, quote: String, hindi: Boolean): String = familyExplainDetailed(answer, quote, hindi).first

    /** Same, plus the engine label + time for the A5 result chip. Part B8: streams the draft via [onProgress]. */
    suspend fun familyExplainDetailed(answer: String, quote: String, hindi: Boolean, onProgress: ((ArogyaProgress) -> Unit)? = null): Triple<String, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        val buf = StringBuilder()
        // 9 Nov 2026: a copied-guide-lines answer (the model could not write one) is read as plain guide text.
        val answer = ArogyaAiExtras.plainGuideText(answer)
        val (opt, by) = route("family_explain", ArogyaAiExtras.familyPrompt(answer, quote, hindi), 450,
            ArogyaAiExtras.familyPrompt(ArogyaOnlinePolicy.scrubGeneric(answer), ArogyaOnlinePolicy.scrubGeneric(quote), hindi),
            cloudTokens = 900, lang = if (hindi) "hi" else "en",
            onDelta = if (onProgress == null) null else { d -> if (d.isEmpty()) buf.clear() else buf.append(d); onProgress(ArogyaProgress("draft", buf.toString())) }) { out ->
            // Numbers from the guide only (as before), plus (8 Nov 2026) no reversed "do not" rule and no
            // medicine the guide answer does not name: this is the text the family hears.
            val grounded = ArogyaAiExtras.groundedSentences(out, answer + "\n" + quote, max = ArogyaAiExtras.FAMILY_MAX_SENTENCES)
            val noMeds = ArogyaAiExtras.dropUngroundedMedicines(grounded, answer + "\n" + quote)
            val safe = KbReplyText.dropFlippedProhibitions(noMeds, answer + "\n" + quote)
            if (safe.second > 0 || noMeds.length < grounded.length) Log.w(TAG, "AROGYA_FAMILY_GUARD flipped=${safe.second} medicineDropped=${noMeds.length < grounded.length}")
            safe.first.takeIf { it.isNotBlank() } }
        val clean = opt.orEmpty()
        Log.i(TAG, "AROGYA_TIMING event=family_explain ms=${System.currentTimeMillis() - t} chars=${clean.length}")
        check(clean.isNotBlank()) { "Could not make a simple explanation. Read the guide sentence to the family instead." }
        Triple(clean, by, System.currentTimeMillis() - t)
    }

    /** Part B5: AI only polishes the fixed template's wording; the worker edits it before sending. */
    suspend fun anmMessage(facts: String, visit: ArogyaVisit? = null): String = anmMessageDetailed(facts, visit).first

    /** Same, plus the engine label that ran it (for the demo's honest "AI at work" panel). */
    suspend fun anmMessageDetailed(facts: String, visit: ArogyaVisit? = null, hindi: Boolean = false): Pair<String, String> =
        polishAnmMessage(facts, visit, hindi).let { it.first to it.second }

    /**
     * Part B5 "Improve wording": polish [template] without changing any fact. The phone
     * sees the template; the cloud sees it with identifiers as [Pn] tokens (restored
     * afterwards, so names and phones never leave the device). Returns the message, the
     * engine label, and a note: "" when polished, else why the template is shown instead.
     */
    suspend fun polishAnmMessage(template: String, visit: ArogyaVisit?, hindi: Boolean, extraTokens: List<String> = emptyList(), onProgress: ((ArogyaProgress) -> Unit)? = null): Triple<String, String, String> = mutex.withLock {
        val t = System.currentTimeMillis()
        val v = visit ?: ArogyaVisit()
        val values = listOf(v.name, v.village, v.birthDate) + extraTokens
        val (tokenized, used) = ArogyaAnmTemplate.tokenize(template, values)
        val toks = used.mapIndexed { i, _ -> "[P${i + 1}]" }
        val cloudPrompt = if (visit != null) ArogyaOnlinePolicy.scrub(tokenized, v) else ArogyaOnlinePolicy.scrubGeneric(tokenized)
        val lang = if (hindi) "hi" else ArogyaBudget.langOf(template)
        val buf = StringBuilder()
        val (opt, by) = route("anm_message", ArogyaAiExtras.anmPrompt(template, hindi), 600, ArogyaAiExtras.anmPrompt(cloudPrompt, hindi), cloudTokens = 1200, lang = lang,
            onDelta = if (onProgress == null) null else { d ->
                if (d.isEmpty()) buf.clear() else buf.append(d)
                onProgress(ArogyaProgress("draft", ArogyaAnmTemplate.detokenize(buf.toString(), used)))
            }) { out ->
            ArogyaAiExtras.groundedSentences(out, template + "\n" + tokenized, max = 60, lineMode = true).takeIf { it.length >= 30 } }
        val cloudRan = !by.startsWith("On-phone")
        val seen = if (cloudRan) tokenized else template
        val polishedRaw = opt.orEmpty()
        val signs = ArogyaAnmTemplate.signNames(seen, v, hindi)
        val reason = if (polishedRaw.isBlank()) "the AI returned nothing usable"
        else ArogyaAnmTemplate.verifyPolished(seen, polishedRaw, signs, if (cloudRan) toks else emptyList())
        val clean = if (reason == null) ArogyaAnmTemplate.detokenize(polishedRaw, used) else template
        Log.i(TAG, "AROGYA_TIMING event=anm_message ms=${System.currentTimeMillis() - t} chars=${clean.length} polished=${reason == null} reason=${reason ?: "ok"}")
        Triple(clean, by, reason.orEmpty())
    }

    /** Daily refresher: one question per guide passage, kept only if the right answer is in the passage. */
    suspend fun refresher(count: Int = 3): List<ArogyaQuizItem> = refresherDetailed(count).first

    /** Same, plus the engine label + time for the A5 result chip. Passages in [excludeIds] are skipped so repeats stop. */
    suspend fun refresherDetailed(count: Int = 3, excludeIds: Set<String> = emptySet()): Triple<List<ArogyaQuizItem>, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        val pool = ArogyaSources.all.values.filter { it.excerpt.length > 120 }
        val pickIds = ArogyaPractice.pickFresh(pool.map { it.id }, excludeIds, count + 2).toSet()
        val picks = pool.filter { it.id in pickIds }
        val out = ArrayList<ArogyaQuizItem>()
        var by = ""
        for (src in picks) {
            if (out.size >= count) break
            val (item, label) = route("daily_practice", ArogyaAiExtras.quizPrompt(src.excerpt), 400) { ArogyaAiExtras.parseQuiz(it, src) }
            item?.let { out += it; by = label }
        }
        Log.i(TAG, "AROGYA_TIMING event=refresher ms=${System.currentTimeMillis() - t} items=${out.size}")
        check(out.isNotEmpty()) { "Could not prepare today's questions. Try again." }
        Triple(out, by, System.currentTimeMillis() - t)
    }

    /** Reads an MCP card photo/PDF: text by ML Kit on the phone, fields by Llama, and only fields found in the text are kept. */
    suspend fun readCard(uri: android.net.Uri): ArogyaCardReading = readCardDetailed(uri).first

    /** Same, plus the engine label that read the fields (for the demo's honest "AI at work" panel). */
    suspend fun readCardDetailed(uri: android.net.Uri): Pair<ArogyaCardReading, String> = mutex.withLock {
        val t = System.currentTimeMillis()
        val mgr = attachments ?: error("Card reading is not available.")
        mgr.clearPending()
        // A photo is read once below (positioned OCR lines). Only a PDF goes through the general attachment
        // reader (page text/OCR); staging a photo too ran OCR, object and barcode detection a second time.
        val isPhoto = appContext?.contentResolver?.getType(uri)?.startsWith("image/") == true
        val staged = if (isPhoto) null else (mgr.stageUri(uri) ?: error(mgr.lastStageProblem.ifBlank { "Could not read this file. Use a clear photo or a PDF." }))
        val claimed = if (staged != null) mgr.claimPendingAttachmentsForTurn() else emptyList()
        try {
            // A card page is a table: ML Kit's plain text lists it column by column, so a vaccine and its date land on
            // different lines and the date cannot be trusted. Rebuild the rows from line positions when it is a photo.
            var photo: Pair<android.graphics.Bitmap, List<ArogyaTableOcr.Line>>? = null
            val rowText = runCatching {
                val ctx = appContext ?: return@runCatching ""
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
                val raw = ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                    ?: return@runCatching ""
                val up = ArogyaPhotoGuard.upright(ctx, uri, raw)
                val ocr = MLKitProcessor()
                try { val ls = ocr.recognizeLines(up); photo = up to ls; ArogyaTableOcr.rowText(ls) } finally { runCatching { ocr.close() } }
            }.getOrDefault("")
            val plain = if (staged != null) listOf(staged.extractedText, staged.localVisualContext).filter { it.isNotBlank() }.joinToString("\n").trim()
                else photo?.second?.joinToString("\n") { it.text }.orEmpty().trim()
            if (staged == null && photo == null) error("Could not read this file. Use a clear photo or a PDF.")
            // Online (Cloud first): Muse Spark reads the card like a person (handwritten dates stay on their vaccine's
            // row), Aadhaar blacked out first. Its transcript goes through the same card reader and checks.
            suspend fun vision(): String? {
                val ctx = appContext ?: return null; val (bmp, ls) = photo ?: return null
                return ArogyaPhotoVision.transcribe(ctx, bmp, ls, ArogyaPhotoText.Kind.CARD)?.let { ArogyaPhotoText.cardText(it) }?.takeIf { it.length >= 15 }
            }
            lastCardPhoto = photo
            var seen = if (appContext != null && photo != null && ArogyaPhotoVision.wanted(appContext, rowText.length >= 15)) vision() else null
            val text = seen ?: if (rowText.length >= 15) rowText else plain
            Log.i(TAG, "AROGYA_CARD_TEXT rows=${rowText.lines().size} using=${when { seen != null -> "vision"; rowText.length >= 15 -> "rows"; else -> "plain" }}")
            check(text.length >= 15) { "No readable text was found. Take a closer photo in good light, card flat." }
            // Only values found in the card's own text are kept. Local-only: a card photo
            // is identifiers by design, and names must never reach a cloud prompt.
            var lastRaw = ""
            val lang = ArogyaBudget.langOf(text)
            suspend fun readHalf(half: String): List<ArogyaCardField> {
                val (o, _) = route("card_read", ArogyaAiExtras.cardPrompt(half), 1600, cloud = false, cloudTokens = 2400, lang = ArogyaBudget.langOf(half)) { out ->
                    ArogyaAiExtras.parseCard(out, half) }
                return o.orEmpty()
            }
            // 11 Nov 2026 (AN): an empty card reading is an answer ("no card values on this photo"), never a reason to
            // run the phone model again (a photo of something else used to take several minutes here).
            val (opt, by0) = route("card_read", ArogyaAiExtras.cardPrompt(text), 1600, cloud = false, cloudTokens = 2400, lang = lang) { out ->
                lastRaw = out
                ArogyaAiExtras.parseCard(out, text) }
            var fields = opt.orEmpty()
            var by = by0
            // Part B6: cut JSON (unclosed brackets) → read each half and merge, so no row is lost.
            if (fields.isEmpty() && lastRaw.isNotBlank() && !ArogyaExtractSplit.jsonComplete(lastRaw)) {
                val (h1, h2) = ArogyaExtractSplit.halves(text)
                if (h2.isNotBlank()) {
                    fields = ArogyaExtractSplit.mergeCard(readHalf(h1), readHalf(h2))
                    by = (by + " + split-read").trim(' ', '+')
                    Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=card_read parts=2 fields=${fields.size}")
                }
            }
            // The phone's OCR found nothing on the card → Gemma looks at the photo on the phone (card photos never
            // go online). Same card reader and checks; the label tells the ASHA an AI read it, so every value is checked.
            if (fields.isEmpty() && seen == null && photo != null) {
                ArogyaLocalVision.readPhoto(runtime.localModelManager, photo!!.first, ArogyaPhotoText.Kind.CARD)?.let { ArogyaPhotoText.cardText(it) }?.takeIf { it.length >= 15 }?.let { v ->
                    val (o2, by2) = route("card_read", ArogyaAiExtras.cardPrompt(v), 1600, cloud = false, cloudTokens = 2400, lang = ArogyaBudget.langOf(v)) { out ->
                        ArogyaAiExtras.parseCard(out, v) }
                    if (!o2.isNullOrEmpty()) { fields = o2; by = ArogyaLocalVision.LABEL + " · " + by2; seen = v }
                }
            }
            if (fields.isEmpty() && seen == null && appContext != null && photo != null && ArogyaPhotoVision.wanted(appContext, false)) {
                vision()?.let { v ->
                    val (o2, by2) = route("card_read", ArogyaAiExtras.cardPrompt(v), 1600, cloud = false, cloudTokens = 2400, lang = ArogyaBudget.langOf(v)) { out ->
                        ArogyaAiExtras.parseCard(out, v) }
                    if (!o2.isNullOrEmpty()) { fields = o2; by = ArogyaPhotoVision.LABEL + " · " + by2; seen = v }
                }
            }
            if (seen != null && !by.startsWith(ArogyaPhotoVision.LABEL) && !by.startsWith(ArogyaLocalVision.LABEL)) by = ArogyaPhotoVision.LABEL + " · " + by
            Log.i(TAG, "AROGYA_TIMING event=card_read ms=${System.currentTimeMillis() - t} ocrChars=${text.length} fields=${fields.size}")
            ArogyaCardReading(seen ?: text, fields) to by
        } finally { mgr.releaseTurnAttachments(claimed.map { it.id }, consumed = true) }
    }

    /** The last card photo read (bitmap + ML Kit lines), so the ASHA can have it read again online. */
    @Volatile private var lastCardPhoto: Pair<android.graphics.Bitmap, List<ArogyaTableOcr.Line>>? = null

    /**
     * Card photos never leave the phone (ArogyaPhotoVision.transcribe refuses them), so this returns null
     * for cards; kept for the medicine path and API compatibility.
     */
    /** The last card photo read again by Gemma on the phone (card photos never go online). Null when unavailable. */
    suspend fun readCardOnPhone(): Pair<ArogyaCardReading, String>? = mutex.withLock {
        val (bmp, _) = lastCardPhoto ?: return@withLock null
        val v = ArogyaLocalVision.readPhoto(runtime.localModelManager, bmp, ArogyaPhotoText.Kind.CARD)?.let { ArogyaPhotoText.cardText(it) }?.takeIf { it.length >= 15 } ?: return@withLock null
        val (o, by) = route("card_read", ArogyaAiExtras.cardPrompt(v), 1600, cloud = false, cloudTokens = 2400, lang = ArogyaBudget.langOf(v)) { out ->
            ArogyaAiExtras.parseCard(out, v) }
        Log.i(TAG, "AROGYA_CARD_TEXT using=gemma_on_request fields=${o?.size ?: 0}")
        if (o.isNullOrEmpty()) null else ArogyaCardReading(v, o) to (ArogyaLocalVision.LABEL + " · " + by)
    }

    suspend fun readCardOnline(): Pair<ArogyaCardReading, String>? = mutex.withLock {
        val ctx = appContext ?: return@withLock null
        val (bmp, ls) = lastCardPhoto ?: return@withLock null
        val v = ArogyaPhotoVision.transcribe(ctx, bmp, ls, ArogyaPhotoText.Kind.CARD)?.let { ArogyaPhotoText.cardText(it) }?.takeIf { it.length >= 15 } ?: return@withLock null
        val (o, by) = route("card_read", ArogyaAiExtras.cardPrompt(v), 1600, cloud = false, cloudTokens = 2400, lang = ArogyaBudget.langOf(v)) { out ->
            ArogyaAiExtras.parseCard(out, v) }
        Log.i(TAG, "AROGYA_CARD_TEXT using=vision_on_request fields=${o?.size ?: 0}")
        if (o.isNullOrEmpty()) null else ArogyaCardReading(v, o) to (ArogyaPhotoVision.LABEL + " · " + by)
    }

    /**
     * A4 medicine scan: name, batch, expiry and quantity off a strip/box photo. The OCR text
     * comes from ML Kit on the phone; the deterministic parse runs first and the model (via
     * route()) only fills what is missing. Every kept value is grounded in the OCR text.
     * Returns the scan, the engine label and the parse time (OCR time is measured by the caller).
     */
    /**
     * Add-person photo/PDF: copy the person's details off OCR text. Aadhaar numbers
     * are masked before the model sees the text; every kept value is grounded in it.
     * Returns the field map, the engine label and the time.
     */
    suspend fun extractPersonFields(ocr: String): Triple<Map<String, String>, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        // The record's own Aadhaar is kept (stored encrypted, shown masked). This stays on the phone: never a cloud prompt.
        val clean = ocr
        check(clean.trim().length >= 4) { "No readable text was found. Take a closer photo in good light." }
        val det = mutableMapOf<String, String>()
        Regex("""(?<!\d)(\d{10})(?!\d)""").find(clean)?.let { det["phone"] = it.groupValues[1] }
        var by = "On-phone rules (offline): patterns"
        var lastRaw = ""
        suspend fun readHalf(half: String): Map<String, String> {
            val (o, _) = route("person_extract", ArogyaPersonExtract.prompt(half), 1200, cloud = false, cloudTokens = 2000, lang = ArogyaBudget.langOf(half)) { out ->
                ArogyaPersonExtract.ground(ArogyaPersonExtract.parse(out), half)
            }
            return o.orEmpty()
        }
        // Local-only: the OCR is identifiers by design, and names must never reach a cloud prompt.
        val (opt, label) = route("person_extract", ArogyaPersonExtract.prompt(clean), 1200, cloud = false, cloudTokens = 2000, lang = ArogyaBudget.langOf(clean)) { out ->
            lastRaw = out
            ArogyaPersonExtract.ground(ArogyaPersonExtract.parse(out), clean)
        }
        opt?.let { by = label }
        var model = opt.orEmpty()
        // Part B6: a cut last line (or almost nothing from a long page) → read each half and merge.
        if ((ArogyaExtractSplit.lastLineCut(lastRaw) || (model.size < 2 && clean.length > 800)) && lastRaw.isNotBlank()) {
            val (h1, h2) = ArogyaExtractSplit.halves(clean)
            if (h2.isNotBlank()) {
                model = ArogyaExtractSplit.mergePerson(ArogyaExtractSplit.mergePerson(model, readHalf(h1)), readHalf(h2))
                by = (by + " + split-read").trim(' ', '+')
                Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=person_extract parts=2 fields=${model.size}")
            }
        }
        val fields = ArogyaPersonExtract.ground(det + model, clean)
        Log.i(TAG, "AROGYA_TIMING event=person_extract ms=${System.currentTimeMillis() - t} fields=${fields.size}")
        Triple(fields, by, System.currentTimeMillis() - t)
    }

    /**
     * Part B6: a register page with several people → one field map per block, each
     * grounded in its own block. Phone-only (identifiers); at most 10 blocks.
     */
    suspend fun extractPersonList(ocr: String): Triple<List<Map<String, String>>, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        val clean = ocr
        check(clean.trim().length >= 4) { "No readable text was found. Take a closer photo in good light." }
        val blocks = ArogyaExtractSplit.splitBlocks(clean).ifEmpty { listOf(clean) }
        var by = "On-phone rules (offline): patterns"
        val out = ArrayList<Map<String, String>>()
        var emptyBlocks = 0
        for (block in blocks) {
            // 11 Nov 2026 (AN): text that is not a list of people (a guide page, a notice) stops after three blocks
            // with nobody in them instead of asking the phone model about every block.
            if (out.isEmpty() && emptyBlocks >= 3) { Log.i(TAG, "AROGYA_PERSON_LIST_STOP blocks_without_people=$emptyBlocks"); break }
            val det = mutableMapOf<String, String>()
            Regex("""(?<!\d)(\d{10})(?!\d)""").find(block)?.let { det["phone"] = it.groupValues[1] }
            val (opt, label) = route("person_extract", ArogyaPersonExtract.prompt(block), 1200, cloud = false, cloudTokens = 2000, lang = ArogyaBudget.langOf(block)) { raw ->
                ArogyaPersonExtract.ground(ArogyaPersonExtract.parse(raw), block)
            }
            val fields = ArogyaPersonExtract.ground(det + (opt ?: emptyMap()), block)
            if (fields.isNotEmpty() && !fields["name"].isNullOrBlank()) {
                out += fields
                if (opt != null) by = label
            } else emptyBlocks++
        }
        Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=person_extract parts=${blocks.size} people=${out.size}")
        Log.i(TAG, "AROGYA_TIMING event=person_extract_list ms=${System.currentTimeMillis() - t} people=${out.size}")
        Triple(out, by, System.currentTimeMillis() - t)
    }

    suspend fun scanMedicine(ocr: String): Triple<MedScan, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        check(ocr.trim().length >= 4) { "No readable text was found. Take a closer photo in good light." }
        var scan = ArogyaMedScan.parse(ocr)
        var by = "On-phone rules (offline): regex"
        if (scan.name.isBlank() || scan.batch.isBlank() || scan.expiry.isBlank()) {
            var lastRaw = ""
            val (opt, label) = route("medicine_scan", ArogyaMedScan.prompt(ocr), 600, ArogyaMedScan.prompt(ArogyaOnlinePolicy.scrubGeneric(ocr)), cloudTokens = 1200, lang = ArogyaBudget.langOf(ocr)) { out ->
                lastRaw = out
                ArogyaMedScan.ground(ArogyaMedScan.parse(out), ocr)
            }
            var model = opt ?: MedScan()
            // Part B6: a cut last line → read each half and merge.
            if (lastRaw.isNotBlank() && ArogyaExtractSplit.lastLineCut(lastRaw)) {
                val (h1, h2) = ArogyaExtractSplit.halves(ocr)
                if (h2.isNotBlank()) {
                    suspend fun readHalf(half: String): MedScan {
                        val (o, _) = route("medicine_scan", ArogyaMedScan.prompt(half), 600, ArogyaMedScan.prompt(ArogyaOnlinePolicy.scrubGeneric(half)), cloudTokens = 1200, lang = ArogyaBudget.langOf(half)) { out ->
                            ArogyaMedScan.ground(ArogyaMedScan.parse(out), half)
                        }
                        return o ?: MedScan()
                    }
                    model = ArogyaExtractSplit.mergeMed(ArogyaExtractSplit.mergeMed(model, readHalf(h1)), readHalf(h2))
                    Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=medicine_scan parts=2")
                }
            }
            if (opt != null || model != MedScan()) {
                scan = ArogyaExtractSplit.mergeMed(scan, model)
                if (opt != null) by = label
            }
        }
        scan = ArogyaMedScan.ground(scan, ocr)
        Log.i(TAG, "AROGYA_TIMING event=medicine_scan ms=${System.currentTimeMillis() - t} name=${scan.name.isNotBlank()} batch=${scan.batch.isNotBlank()} expiry=${scan.expiry.isNotBlank()} qty=${scan.qty}")
        Triple(scan, by, System.currentTimeMillis() - t)
    }

    /**
     * Voice stock entry: a spoken stock change → grounded lines the ASHA confirms.
     * Deterministic parse first; route() (phone or cloud per the Home switch) only fills
     * in when the rules find nothing. Every kept line's item and number are in the transcript.
     */
    suspend fun voiceStock(transcript: String, catalog: List<String>): Triple<List<VoiceStockLine>, String, Long> = mutex.withLock {
        val t = System.currentTimeMillis()
        check(transcript.trim().length >= 4) { "Speak the stock change first." }
        var lines = ArogyaVoiceStock.ground(ArogyaVoiceStock.parseRules(transcript, catalog), transcript, catalog)
        var by = "On-phone rules (offline): patterns"
        // Rules understood only part of it: the model reads the whole note too, and new items are added (rules win per item).
        if (lines.isNotEmpty() && ArogyaVoiceStock.unmatched(transcript, catalog, lines).isNotEmpty()) {
            val rulesLines = lines
            val (more, label) = route("voice_stock", ArogyaVoiceStock.prompt(transcript, catalog), 800,
                ArogyaVoiceStock.prompt(ArogyaOnlinePolicy.scrubGeneric(transcript), catalog), cloudTokens = 1500, lang = ArogyaBudget.langOf(transcript)) { out ->
                ArogyaVoiceStock.ground(ArogyaVoiceStock.parseModel(out), transcript, catalog).takeIf { it.isNotEmpty() } }
            more?.let { m -> lines = rulesLines + m.filter { x -> rulesLines.none { it.item == x.item } }; by = "$by + $label" }
        }
        if (lines.isEmpty()) {
            var lastRaw = ""
            val (opt, label) = route("voice_stock", ArogyaVoiceStock.prompt(transcript, catalog), 800,
                ArogyaVoiceStock.prompt(ArogyaOnlinePolicy.scrubGeneric(transcript), catalog), cloudTokens = 1500, lang = ArogyaBudget.langOf(transcript)) { out ->
                lastRaw = out
                ArogyaVoiceStock.ground(ArogyaVoiceStock.parseModel(out), transcript, catalog).takeIf { it.isNotEmpty() }
            }
            opt?.let { lines = it; by = label }
            // Part B6: nothing parsed from a long note (the model answer was cut) → read each half and merge.
            if (lines.isEmpty() && lastRaw.isNotBlank() && transcript.length > 400) {
                val (h1, h2) = ArogyaExtractSplit.halves(transcript)
                if (h2.isNotBlank()) {
                    suspend fun readHalf(half: String): List<VoiceStockLine> {
                        val (o, _) = route("voice_stock", ArogyaVoiceStock.prompt(half, catalog), 800,
                            ArogyaVoiceStock.prompt(ArogyaOnlinePolicy.scrubGeneric(half), catalog), cloudTokens = 1500, lang = ArogyaBudget.langOf(half)) { out ->
                            ArogyaVoiceStock.ground(ArogyaVoiceStock.parseModel(out), half, catalog).takeIf { it.isNotEmpty() }
                        }
                        return o.orEmpty()
                    }
                    lines = ArogyaExtractSplit.mergeVoice(readHalf(h1), readHalf(h2))
                    if (lines.isNotEmpty()) by = (by + " + split-read").trim(' ', '+')
                    Log.i(TAG, "AROGYA_EXTRACT_SPLIT feature=voice_stock parts=2 lines=${lines.size}")
                }
            }
        }
        Log.i(TAG, "AROGYA_TIMING event=voice_stock ms=${System.currentTimeMillis() - t} lines=${lines.size}")
        Triple(lines, by, System.currentTimeMillis() - t)
    }

    /**
     * Finish Visit step 1: notes → structured fields. Uses the chosen hosted Meta model when a key is
     * saved (notes are scrubbed of the beneficiary's name first); otherwise, or if that call fails,
     * the phone's own model. Every field is checked against the notes; temperature and weight are
     * also read by fixed rules. The result is only a proposal for the ASHA to tick.
     */
    suspend fun extractFields(visit: ArogyaVisit): ArogyaExtraction = mutex.withLock {
        val notes = visit.notes.trim()
        check(notes.isNotBlank()) { "Speak or type your visit observations first." }
        check(notes.length <= 6000) { "These notes are too long for one review. Shorten them first." }
        val t = System.currentTimeMillis()
        val (ai, by) = route("finish_visit", ArogyaFinishVisit.extractionPrompt(notes), 1400, ArogyaFinishVisit.extractionPrompt(ArogyaOnlinePolicy.scrub(notes, visit)),
            system = "You extract structured fields from health-worker notes. Reply with JSON only.", cloudTokens = 2400, lang = ArogyaBudget.langOf(notes)) { raw -> ArogyaFinishVisit.parseExtraction(raw, notes).takeIf { it.isNotEmpty() } }
        val fields = ai.orEmpty()
        val rules = ArogyaFinishVisit.ruleFallback(notes).filter { r -> fields.none { it.key == r.key } }
        val ms = System.currentTimeMillis() - t
        val label = by.ifBlank { "Fixed patterns only (no model)" }
        Log.i(TAG, "AROGYA_FINISH_EXTRACT model=\"$label\" ms=$ms aiFields=${fields.size} ruleFields=${rules.size}")
        ArogyaExtraction(fields + rules, label, !label.startsWith("On-phone") && !label.startsWith("Fixed"), ms, "")
    }

    /**
     * Voice note → one column of an official form. Follows the app's Local first / Cloud first setting:
     * Cloud first tries the cloud model (identifiers removed) and falls back to the phone; Local first uses the
     * phone model and asks the cloud only if the phone model is unavailable or fills nothing.
     */
    suspend fun fillForm(b: Beneficiary, spec: FormSpec, column: Int, note: String): Pair<Map<String, FilledValue>, ArogyaExtraction> = mutex.withLock {
        val choice = runCatching { withContext(Dispatchers.IO) { cloudChoice?.invoke() } }.getOrNull()
        val endpoint = choice?.endpoint; val key = choice?.key.orEmpty(); val cloudFirst = choice?.cloudFirst == true
        val text = note.trim()
        check(text.isNotBlank()) { "Speak or type what you saw first." }
        check(text.length <= 6000) { "This note is too long. Shorten it first." }
        val t = System.currentTimeMillis()
        var label = ""; var wasCloud = false; var msg = ""
        val cloudReady = endpoint != null && key.isNotBlank()
        suspend fun cloud(): Map<String, FilledValue>? = try {
            // 8 Nov 2026: 3,000 output tokens (was 900, long forms lost their last rows), x2.5 for Hindi notes.
            // 9 Nov 2026: capped to Groq's per-minute limit (Hindi asked 7,500 + prompt: always refused), 60 s.
            val formSystem = "You fill official health forms from notes. Reply with JSON only."
            val cloudPrompt = ArogyaOfficialForms.prompt(spec, column, ArogyaOfficialForms.scrub(b, text))
            val promptTok = GroqChatProvider.estimateTokens(formSystem + "\n" + cloudPrompt) + 16
            val cloudBudget = ArogyaGroqMinute.cap(ArogyaBudget.tokens(FORM_CLOUD_TOKENS, ArogyaBudget.langOf(text), promptTokensOf(cloudPrompt), ArogyaBudget.CLOUD_CONTEXT, "fill_form", "cloud"), promptTok)
            ArogyaGroqMinute.waitMs(promptTok + cloudBudget).takeIf { it in 1..ArogyaGroqMinute.MAX_WAIT_MS }?.let { delay(it) }
            ArogyaGroqMinute.record(promptTok + cloudBudget)
            val raw = ArogyaCloud.withDeadline(CLOUD_LONG_DEADLINE_MS, "fill_form") {
                OpenAiCompatibleClient(endpoint!!, { key }, CLOUD_LONG_DEADLINE_MS.toInt()).complete(formSystem, cloudPrompt, cloudBudget)
            } ?: error("no reply within ${CLOUD_LONG_DEADLINE_MS / 1000} s")
            label = endpoint!!.label; wasCloud = true
            ArogyaOfficialForms.parse(raw, spec, column, text)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { msg = (msg + " Online model not used: ${e.message?.take(120)}").trim(); Log.w(TAG, "AROGYA_FORM_ONLINE_FAILED ${endpoint?.id} ${e.message?.take(200)}"); null }
        suspend fun phone(): Map<String, FilledValue>? = try {
            val model = readyModel()
            // Allow long ANC forms to return complete grounded JSON objects. 8 Nov 2026: up to 2,400 output
            // tokens, clamped to what fits the model's active window after the prompt (2,000 unchecked
            // could overflow a 4K window on a long form).
            val phonePrompt = ArogyaOfficialForms.prompt(spec, column, text)
            val ctx = model.usableContextTokens.takeIf { it > 0 } ?: ArogyaBudget.PHONE_CONTEXT
            val phoneBudget = ArogyaBudget.tokens(FORM_PHONE_TOKENS, ArogyaBudget.langOf(text), promptTokensOf(phonePrompt) + 64, ctx, "fill_form", "phone")
            val raw = generate(phonePrompt, model, phoneBudget)
            label = "On-phone model (offline): " + (model.loadedModelFileName?.substringBeforeLast('.') ?: "local")
            val parsed = ArogyaOfficialForms.parse(raw.orEmpty(), spec, column, text)
            Log.i(TAG, "AROGYA_FORM_LOCAL_RESULT rawChars=${raw?.length ?: 0} aiRows=${parsed.count { it.value.source == "ai" }} ruleRows=${parsed.count { it.value.source != "ai" }}")
            parsed
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            Log.w(TAG, "AROGYA_FORM_LOCAL_FAILED type=${e.javaClass.simpleName} reason=${e.message?.take(160)}")
            msg = (msg + " On-phone model failed: ${e.message?.take(100)}").trim()
            null
        }
        var values: Map<String, FilledValue>? = null
        if (cloudFirst && cloudReady) values = cloud()
        if (values == null) values = phone()
        // Local First never sends visit notes to cloud merely because local extraction
        // returned no AI fields. The worker can switch to Cloud First explicitly.
        if (values == null) { label = "Fixed patterns only (no model)"; values = ArogyaOfficialForms.parse("", spec, column, text) }
        // Cloud first, but the cloud did not fill the form: the label names it and says why (never a silent switch).
        if (cloudFirst && (endpoint == null || label != endpoint.label)) {
            val why = when {
                !cloudReady && appContext != null && !MetaOnline.hasInternet(appContext) -> "no internet"
                !cloudReady -> "no Groq key saved"
                else -> msg.substringAfter("Online model not used:", "the call failed").trim().substringBefore(" On-phone").ifBlank { "the call failed" }
            }
            label = "${endpoint?.label?.substringBefore(" —") ?: "Qwen3.8 27B"} • online — not used: $why · $label"
        }
        val ms = System.currentTimeMillis() - t
        // Never silent: say how many rows came from the model and how many from the fixed rules.
        val aiN = values!!.count { it.value.source == "ai" }; val ruleN = values!!.size - aiN
        if (aiN == 0) msg = (msg + " The AI model gave no usable rows; $ruleN read from the note by fixed rules.").trim()
        Log.i(TAG, "AROGYA_FORM_FILL_SPLIT form=${spec.id} ai=$aiN rules=$ruleN prompt_rows=${ArogyaOfficialForms.relevantFields(spec, column, text).size}")
        Log.i(TAG, "AROGYA_FORM_FILL form=${spec.id} col=$column policy=${if (cloudFirst) "cloud_first" else "local_first"} model=\"$label\" cloud=$wasCloud ms=$ms filled=${values!!.size}/${ArogyaOfficialForms.fieldsFor(spec, column).size}")
        values!! to ArogyaExtraction(emptyList(), label, wasCloud, ms, msg)
    }

    /** Prompt tokens: ~4 chars per token, but Devanagari takes ~2-3x more tokens than its characters suggest. */
    private fun promptTokensOf(prompt: String): Int = prompt.length / 4 + prompt.count { it in '\u0900'..'\u097F' } / 2

    companion object {
        /** 11 Nov 2026 (AN): photo/document reading features: at most 3 minutes per phone generation. */
        val EXTRACT_FEATURES = setOf("person_extract", "medicine_scan", "card_read", "card_extract")
        const val EXTRACT_LIMIT_MS = 180_000L
        private const val TAG = "ArogyaDemo"; const val CLOUD_DEADLINE_MS = 25_000L
        /** 9 Nov 2026: replies longer than [LONG_REPLY_TOKENS] (long Hindi forms, notes) get 60 s. */
        const val CLOUD_LONG_DEADLINE_MS = 60_000L; const val LONG_REPLY_TOKENS = 1_500; const val ASK_TIMEOUT_MS = 300_000L
        /** Form fill output (English base; Hindi x2.5 via ArogyaBudget), clamped to the window. */
        const val FORM_PHONE_TOKENS = 2_400
        const val FORM_CLOUD_TOKENS = 3_000
        /** Passages read for "More detail" (normal answers read 6); ~12,000 chars fits the 8K window with a 2,400-token answer. */
        const val DETAIL_PASSAGES = 8
        /** Added to the prompt for the one retry when the phone's first reply could not be used. */
        private const val RETRY_SUFFIX = "\n\nYour previous reply could not be used. Reply again in exactly the format asked above, with nothing before or after it."
    }
}
