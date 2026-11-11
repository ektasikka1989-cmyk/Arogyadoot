import com.example.arfas.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray

// 9 Nov 2026: replay of phone 20:19 — Gemma's correct recipe numbers were removed because the 6 passages it read
// lacked them; the whole guide confirms them (stand-in for the keyword index: word overlap over every guide chunk).
private var pass = 0; private var fail = 0
private fun check(n: String, ok: Boolean, d: String = "") { if (ok) { pass++; println("PASS $n") } else { fail++; println("FAIL $n $d") } }
class Draft(private val reply: String) : PocketRagLocalAnswerer { var calls = 0
    override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? { calls++; return reply } }
class NoCloud3 : PocketRagCloudResolver(), PocketRagCloudAnswerer {
    override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? = null }

fun main() = runBlocking {
    val a = JSONArray(java.io.File("simtests/all_rows.json").readText()); val guide = (0 until a.length()).map { a.getString(it) }
    fun words(t: String) = Regex("[\\p{L}]{3,}").findAll(t.lowercase()).map { it.value }.toSet()
    var lookups = 0
    // Stand-in for this question's search results: the 27 chunks sharing most words with the question.
    val qw = words("How do I make ORS at home? How is ORS prepared at home?")
    val candidates = guide.withIndex().sortedByDescending { (words(it.value) intersect qw).size }.take(27).map { "Guide chunk ${it.index}" to it.value }
    val lookup: suspend (String) -> List<Pair<String, String>> = { _ -> lookups++; candidates }
    // The phone's passages (no "50 gm" page): Module 7 p28 table, HBYC p68, Integrated C p22.
    val p28 = guide.first { it.contains("1 glass of water 1 litre of water") }
    val p68 = guide.first { it.contains("Teaching the caregivers use of ORS") }
    val c22 = guide.first { it.contains("Measure one litre of clean drinking water") }
    val sel = PocketRagSelection("generic", "asha", "ASHA guide")
    val q = "How do I make ORS at home?"
    val blocks = listOf(PocketRagEvidenceBlock("E1", "S1", p28), PocketRagEvidenceBlock("E2", "S2", p68), PocketRagEvidenceBlock("E3", "S3", c22))
    val packet = PocketRagPacket(sel, q, blocks, blocks.map { PocketRagSource(it.sourceId, "${it.sourceId} :: page") }, coverageComplete = true, bestRelevance = 8.5f)
    val draft = "Answer: To make ORS at home, first wash your hands with soap and water [S1]. Pour all the ORS powder from a packet into a clean container [S1]. Measure one litre of clean drinking water and pour it into the container [S3]. Stir until all the powder is mixed [S1].\n" +
        "What to do:\n1. If the ORS packet is not available, mix one litre of water with 50 gm of sugar and 5 gm of salt [S1].\n2. Taste it: it should taste like tears [S2].\n" +
        "When to tell the ANM: Tell the ANM if the child cannot drink [S2]."
    val g = PocketRagQualityGate()
    check("draft-rejected-for-50", g.evaluate(q, draft, packet).reason.startsWith("unsupported_numeric_claim"), g.evaluate(q, draft, packet).reason)
    val local = Draft(draft)
    val engine = PocketRagEngine(local, NoCloud3(), PocketRagQualityGate(), PocketRagSessionStore(), canUseCloud = { false }, contextWindowTokens = { 8_192 },
        callerTimeoutEnabled = false, guideLookup = lookup)
    val r = engine.answer(packet, PocketRagEngine.CloudMode.NONE)
    println("   reason=${r.escalationReason} extra=${r.extraEvidence.size} sources=${r.sourceIds}")
    println("   text=" + r.text.replace("\n", " | ").take(700))
    check("recipe-kept", "50 gm of sugar and 5 gm of salt" in r.text, r.text.take(300))
    check("one-generation", local.calls == 1, "${local.calls}")
    check("guide-confirmed", r.escalationReason == "local_recovery:guide_confirmed" && r.extraEvidence.size == 1, r.escalationReason)
    check("new-source-shown", r.sourceIds.any { "Guide chunk" in it }, r.sourceIds.toString())
    check("lookups-few", lookups in 1..3, "$lookups")
    // A number the guide does not state anywhere is not confirmed (removed or replaced as before).
    val wrong = draft.replace("50 gm of sugar", "70 gm of sugar")
    val local2 = Draft(wrong)
    val r2 = PocketRagEngine(local2, NoCloud3(), PocketRagQualityGate(), PocketRagSessionStore(), canUseCloud = { false }, contextWindowTokens = { 8_192 },
        callerTimeoutEnabled = false, guideLookup = lookup).answer(packet, PocketRagEngine.CloudMode.NONE)
    check("wrong-number-not-confirmed", "70 gm" !in r2.text && r2.escalationReason != "local_recovery:guide_confirmed", r2.escalationReason + " " + r2.text.take(200))
    // Without the lookup (other KBs): behaviour as before.
    val r3 = PocketRagEngine(Draft(draft), NoCloud3(), PocketRagQualityGate(), PocketRagSessionStore(), canUseCloud = { false }, contextWindowTokens = { 8_192 },
        callerTimeoutEnabled = false).answer(packet, PocketRagEngine.CloudMode.NONE)
    check("no-lookup-unchanged", "50 gm" !in r3.text && r3.extraEvidence.isEmpty(), r3.text.take(200))
    // A medicine dose is never confirmed from another passage (another row of a dose table could hold it).
    val zincTable = "Give Zinc Supplements For 14 Days in case of children Age Dose 2–6 months 10 mg (½ tablet) daily 6 months–5 years 20 mg (1 tablet) daily"
    val dosePacket = PocketRagPacket(sel, "Zinc dose for a 4 month old baby?", listOf(PocketRagEvidenceBlock("E1", "S1", "Give ORS after every loose stool.")), listOf(PocketRagSource("S1", "S1 :: p")), coverageComplete = true, bestRelevance = 6f)
    val doseEngine = PocketRagEngine(Draft("x"), NoCloud3(), PocketRagQualityGate(), PocketRagSessionStore(), canUseCloud = { false }, contextWindowTokens = { 8_192 },
        callerTimeoutEnabled = false, guideLookup = { _ -> listOf("Zinc table" to zincTable) })
    check("dose-never-confirmed", doseEngine.confirmFromGuide(dosePacket.query, "Answer: Give one zinc tablet of 20 mg daily for 14 days [S1].", dosePacket) == null)
    check("candidates-only", candidates.any { it.second.contains("50 gm of sugar") }, "the 50 gm page is among this question's results")
    println("GuideConfirmTest: $pass passed, $fail failed"); if (fail > 0) kotlin.system.exitProcess(1)
}
