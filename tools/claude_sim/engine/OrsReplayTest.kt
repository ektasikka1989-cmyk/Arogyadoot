import com.example.arfas.*
import kotlinx.coroutines.runBlocking

// 9 Nov 2026: replay of the phone failure "How do I make ORS at home?" (log 18:21). The real engine and gate.
private var pass = 0; private var fail = 0
private fun check(n: String, ok: Boolean, d: String = "") { if (ok) { pass++; println("PASS $n") } else { fail++; println("FAIL $n $d") } }
class OneLocal(private val reply: String) : PocketRagLocalAnswerer {
    var calls = 0
    override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? { calls++; return reply }
}
class NoCloud : PocketRagCloudResolver(), PocketRagCloudAnswerer {
    override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = null
}

fun main() = runBlocking {
    val full = "Guide the mother for preparing ORS in the following way. Nowadays, one litre plastic water bottles are available and can be used to measure the correct quantity of water. (a) Wash your hands with soap (b) Pour all the ORS powder into a container having capacity of 1 litre (c) Measure 1 litre of drinking water (boiled & cooled) & pour it in a container (d) Stir well until the powder is mixed thoroughly Demonstration of preparation of ORS If the ORS packet is not available, teach the mother how to make home-made ORS: For one glass (200 ml) of water, add a pinch of salt and a spoon of sugar. (See in the diagram how a pinch of salt is taken with three fingers and how a spoon of sugar is measured). Alternatively, one litre of water with 50 gm of sugar (8 spoons) and 5 gm (a teaspoon ) of salt. A juice of half a lime can be squeezed in. Taste to see that it is not too salty, or too sugary. It should taste of tears."
    val cutAt = full.indexOf("For one glass (200") + "For one glass (200".length
    val cut = full.substring(0, cutAt)          // what the 4,000-character cap gave the phone
    val other = "Give frequent small sips from a cup. If vomiting occurs, wait 10 minutes and give ORS more slowly. Continue giving extra fluid until diarrhoea stops."
    val draft = "Answer: To make ORS at home, first wash your hands with soap [S1]. Pour all the ORS powder into a container of 1 litre [S1]. Measure 1 litre of drinking water, boiled and cooled, and pour it into the container [S1]. Stir well until the powder is mixed thoroughly [S1].\n" +
        "Why it matters: ORS replaces the water and salts lost in diarrhoea [S2].\n" +
        "What to do:\n1. If there is no ORS packet, mix one litre of water with 50 gm of sugar and 5 gm of salt [S1].\n2. Give frequent small sips from a cup [S2].\n3. If vomiting occurs, wait 10 minutes and give ORS more slowly [S2].\n" +
        "When to tell the ANM: Tell the ANM if the child cannot drink [S2].\n" +
        "Common mistakes to avoid: Do not stop giving fluid until the diarrhoea stops [S2]."
    val sel = PocketRagSelection("generic", "asha", "ASHA guide")
    val q = "How do I make ORS at home?"
    suspend fun run(name: String, evidence: String): PocketRagResult {
        val packet = PocketRagPacket(sel, q, listOf(PocketRagEvidenceBlock("E1", "S1", evidence), PocketRagEvidenceBlock("E2", "S2", other)),
            listOf(PocketRagSource("S1", "S1 :: ASHA Module 7 — page 27"), PocketRagSource("S2", "S2 :: Integrated ASHA Training Module C — page 23")),
            coverageComplete = true, bestRelevance = 7.04f)
        val local = OneLocal(draft)
        val engine = PocketRagEngine(local, NoCloud(), PocketRagQualityGate(), PocketRagSessionStore(), canUseCloud = { false },
            contextWindowTokens = { 8_192 }, callerTimeoutEnabled = false)
        val r = engine.answer(packet, PocketRagEngine.CloudMode.NONE)
        println("== $name: reason='${r.escalationReason}' generations=${local.calls} text=${r.text.replace("\n", " | ").take(260)}")
        return r
    }
    val g = PocketRagQualityGate()
    val cutPacket = PocketRagPacket(sel, q, listOf(PocketRagEvidenceBlock("E1", "S1", cut), PocketRagEvidenceBlock("E2", "S2", other)), listOf(PocketRagSource("S1", "S1"), PocketRagSource("S2", "S2")), coverageComplete = true, bestRelevance = 7f)
    check("cut-evidence-rejects-50", g.evaluate(q, draft, cutPacket).reason.startsWith("unsupported_numeric_claim"), g.evaluate(q, draft, cutPacket).reason)
    val a = run("full evidence (9,000 cap)", full)
    check("full-evidence-gemma-answer-kept", !a.text.startsWith(PocketRagEngine.EXTRACTIVE_HEADER_EN) && a.text.contains("50 gm"), a.text.take(200))
    val b = run("cut evidence (old 4,000 cap)", cut)
    check("cut-evidence-not-copied-sentences", !b.text.startsWith(PocketRagEngine.EXTRACTIVE_HEADER_EN), b.text.take(200))
    check("cut-evidence-bad-number-removed", !b.text.contains("50 gm") && b.text.contains("Stir well"), b.text.take(300))
    // Gemma typed "\n" as two characters (phone log): the search line must not swallow "CALC: NONE".
    val rw = GenericKbQuestionRewrite.parse("SEARCH: How is ORS prepared at home?\\nCALC: NONE", q)
    check("literal-backslash-n", rw?.search == "How is ORS prepared at home?" && rw.calc == null, rw.toString())
    check("cut-window-digit-not-complete", GenericKbKeyFacts.completeSentences("Wash your hands with soap and pour all the ORS powder into a clean one litre container. Measure one litre of boiled and cooled drinking water and pour it in. Stir well until mixed. For one glass (200 …").endsWith("mixed. …"))
    // Phone 19:43: "wait 1 minute" (the guide: 10 minutes) passed the number check (single digits were never checked).
    val vomit = "If the child vomits, wait for 10 minutes and then give ORS more slowly."
    val wrongMinute = draft.replace("3. If vomiting occurs, wait 10 minutes and give ORS more slowly [S2].", "3. If vomiting occurs, wait 1 minute and give ORS more slowly [S2].")
    check("wrong-minute-rejected", g.evaluate(q, wrongMinute, PocketRagPacket(sel, q, listOf(PocketRagEvidenceBlock("E1", "S1", full), PocketRagEvidenceBlock("E2", "S2", other)), listOf(PocketRagSource("S1", "S1"), PocketRagSource("S2", "S2")), coverageComplete = true, bestRelevance = 7f)).reason.contains("1 minute"))
    val gm = PocketRagQualityGate()
    check("unit-same-number-ok", gm.unsupportedNumericClaims(q, "Wait 10 minutes [S1].", vomit).isEmpty())
    check("unit-word-number-ok", gm.unsupportedNumericClaims(q, "Use 1 litre of water [S1].", "Use one litre of water.").isEmpty())
    check("unit-hrs-ok", gm.unsupportedNumericClaims(q, "Throw it away after 24 hours [S1].", "Discard ORS if kept for more than 24 hrs.").isEmpty())
    check("unit-wrong-hours", gm.unsupportedNumericClaims(q, "Throw it away after 48 hours [S1].", "Discard ORS if kept for more than 24 hrs. Give 48 spoons.").isNotEmpty())
    check("unit-question-age-ok", gm.unsupportedNumericClaims("My 8 months old baby has diarrhoea", "For a child of 8 months, give ORS [S1].", vomit).isEmpty())
    check("unit-gram-forms", gm.unsupportedNumericClaims(q, "Add 5 grams of salt [S1].", "1 tea spoon of salt (5gm)").isEmpty())
    // The wrong-minute sentence is replaced by the guide's own sentence (no second generation, nothing lost).
    val pk2 = PocketRagPacket(sel, q, listOf(PocketRagEvidenceBlock("E1", "S1", full), PocketRagEvidenceBlock("E2", "S2", other + " " + vomit)), listOf(PocketRagSource("S1", "S1"), PocketRagSource("S2", "S2")), coverageComplete = true, bestRelevance = 7f)
    val fixed = gm.salvageUnsupportedNumericSentences(q, wrongMinute, pk2).orEmpty()
    check("wrong-minute-replaced-by-guide", "wait 10 minutes" in fixed && "wait 1 minute" !in fixed && "Stir well" in fixed && "50 gm" in fixed, fixed.substringAfter("What to do").replace("\n", " | ").take(600))
    check("replaced-answer-passes", gm.evaluate(q, fixed, pk2).acceptLocal, gm.evaluate(q, fixed, pk2).reason)
    val l2 = OneLocal(wrongMinute)
    val r4 = PocketRagEngine(l2, NoCloud(), PocketRagQualityGate(), PocketRagSessionStore(), canUseCloud = { false }, contextWindowTokens = { 8_192 }, callerTimeoutEnabled = false).answer(pk2, PocketRagEngine.CloudMode.NONE)
    check("engine-one-generation-only", l2.calls == 1 && "wait 10 minutes" in r4.text && "wait 1 minute" !in r4.text && !r4.text.startsWith(PocketRagEngine.EXTRACTIVE_HEADER_EN), "calls=${l2.calls} ${r4.escalationReason} ${r4.text.take(200)}")
    val pk3 = PocketRagPacket(sel, q, listOf(PocketRagEvidenceBlock("E1", "S1", full), PocketRagEvidenceBlock("E2", "S2", "Give frequent small sips from a cup. " + vomit)), listOf(PocketRagSource("S1", "S1"), PocketRagSource("S2", "S2")), coverageComplete = true, bestRelevance = 7f)
    val fixed3 = gm.salvageUnsupportedNumericSentences(q, wrongMinute, pk3).orEmpty()
    check("replaced-with-guide-wording", "If the child vomits, wait for 10 minutes and then give ORS more slowly. [S2]" in fixed3, fixed3.substringAfter("What to do").take(300))
    val unrelated = "Answer: Wash your hands [S1].\nWhat to do:\n1. Keep the baby warm for 3 hours [S1]."
    check("no-replacement-when-unrelated", "10 minutes" !in gm.salvageUnsupportedNumericSentences(q, unrelated, pk3).orEmpty())
    println("OrsReplayTest: $pass passed, $fail failed"); if (fail > 0) kotlin.system.exitProcess(1)
}
