import com.example.arfas.*
import kotlinx.coroutines.runBlocking

// 9 Nov 2026: gaps 2-5 (Groq per-minute limit, More detail room, family explanation, fallback label).
private var pass = 0; private var fail = 0
private fun check(n: String, ok: Boolean, d: String = "") { if (ok) { pass++; println("PASS $n") } else { fail++; println("FAIL $n $d") } }
class NoLocal : PocketRagLocalAnswerer { override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? = null }
class NoCloud2 : PocketRagCloudResolver(), PocketRagCloudAnswerer {
    override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = null }

fun main() = runBlocking {
    // Gap 2: shared per-minute count.
    ArogyaGroqMinute.reset()
    check("cap-fits", ArogyaGroqMinute.cap(7_500, 3_000) == 4_600)
    check("cap-small-prompt-unchanged", ArogyaGroqMinute.cap(1_200, 1_000) == 1_200)
    check("cap-min", ArogyaGroqMinute.cap(2_000, 7_590) == 256)
    val t0 = 1_000_000L
    check("first-request-now", ArogyaGroqMinute.waitMs(7_000, t0) == 0L)
    ArogyaGroqMinute.record(7_000, t0)                       // an online answer
    val w = ArogyaGroqMinute.waitMs(2_000, t0 + 10_000)       // family explanation 10 s later
    check("second-request-waits-for-minute", w == 50_000L, "$w")
    check("small-request-fits", ArogyaGroqMinute.waitMs(500, t0 + 10_000) == 0L)
    check("after-minute-free", ArogyaGroqMinute.waitMs(7_000, t0 + 60_001) == 0L)
    ArogyaGroqMinute.reset()
    val body = OpenAiCompatibleClient(ArogyaModelEndpoints.byId("groq")!!, { "k" }).requestBody("s", "u", 100)
    check("groq-reasoning-off", body.contains("\"reasoning_effort\":\"none\""), body)

    // Gap 4: phone output room, normal vs More detail, English and Hindi (8,192-token window).
    val sel = PocketRagSelection("generic", "asha", "ASHA guide")
    fun packet(q: String, chars: Int, blocks: Int, detail: Boolean): PocketRagPacket {
        val per = chars / blocks
        val bl = (1..blocks).map { PocketRagEvidenceBlock("E$it", "S$it", "Give ORS after every loose stool and continue feeding the child. ".repeat(per / 66 + 1).take(per)) }
        val p = PocketRagPacket(sel, q, bl, bl.map { PocketRagSource(it.sourceId, it.sourceId) }, coverageComplete = true, bestRelevance = 7f)
        return if (detail) p.copy(detailMode = true) else p
    }
    val engine = PocketRagEngine(NoLocal(), NoCloud2(), PocketRagQualityGate(), PocketRagSessionStore(), canUseCloud = { false }, contextWindowTokens = { 8_192 }, callerTimeoutEnabled = false)
    val en = "How is ORS prepared at home and what should the ASHA do?"
    val hi = "घर पर ओआरएस कैसे बनाएं और आशा को क्या करना चाहिए?"
    val nEn = engine.outputBudget(packet(en, 9_000, 6, false)); val dEn = engine.outputBudget(packet(en, 9_000, 6, true))
    val nHi = engine.outputBudget(packet(hi, 9_000, 6, false)); val dHi = engine.outputBudget(packet(hi, 9_000, 6, true))
    println("   budgets: normal en=$nEn detail en=$dEn normal hi=$nHi detail hi=$dHi")
    check("detail-not-less-than-normal-en", dEn >= nEn, "$dEn < $nEn")
    check("detail-not-less-than-normal-hi", dHi >= nHi, "$dHi < $nHi")
    check("detail-room-was-1550-now-more", dEn >= 2_400 && dHi >= 2_400, "en=$dEn hi=$dHi")
    // Window: evidence (9,000 chars ≈ 2,500 tokens) + prompt 1,700 + answer must stay within 8,192.
    check("detail-fits-window", 9_000 / 3.6 + 1_700 + dHi <= 8_192, "$dHi")

    // Gap 4: family explanation.
    val fp = ArogyaAiExtras.familyPrompt("Give ORS. Refer if blood in stool.", "ORS saves lives.", true)
    check("family-escalation-first", fp.indexOf("First say when to call") in 0 until fp.indexOf("what not to do"))
    check("family-6-10", "6 to 10" in fp && ArogyaAiExtras.FAMILY_MAX_SENTENCES == 10)
    val ten = (1..11).joinToString(" ") { "Sentence $it is here." }.replace(Regex("\\d+"), "x")
    check("family-keeps-10", ArogyaAiExtras.groundedSentences(ten, "x", max = ArogyaAiExtras.FAMILY_MAX_SENTENCES).split(". ").size == 10)

    // Gap 5: copied guide lines read as plain guide text for the family; normal answers unchanged.
    val extractive = PocketRagEngine.EXTRACTIVE_HEADER_EN + "\n• Wash your hands with soap [S1]\n• Stir well until the powder is mixed [S1, S2]"
    val plain = ArogyaAiExtras.plainGuideText(extractive)
    check("plain-guide-text", plain == "Wash your hands with soap. Stir well until the powder is mixed.", plain)
    check("plain-normal-unchanged", ArogyaAiExtras.plainGuideText("Answer: Give ORS.\nWhat to do:\n1. Give ORS.") == "Answer: Give ORS.\nWhat to do:\n1. Give ORS.")
    check("label-says-ai-failed", PocketRagEngine.EXTRACTIVE_HEADER_EN.startsWith("The AI could not write a checked answer"))
    println("BudgetGapsTest: $pass passed, $fail failed"); if (fail > 0) kotlin.system.exitProcess(1)
}
