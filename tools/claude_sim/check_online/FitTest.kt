import com.example.arfas.*
import kotlinx.coroutines.runBlocking
var ff = 0; var fp = 0
fun f(c: Boolean, m: String, d: () -> String = { "" }) { if (c) { fp++; println("PASS $m") } else { ff++; println("FAIL $m :: ${d()}") } }
/** Same arithmetic as GroqChatProvider.chatGenericKnowledgeText (estimateTokens, REQUEST_TOKEN_BUDGET 7,600, system ≈700). */
fun groqMax(query: String, requested: Int): Pair<Int, Int> {
    val input = PocketRagCloudResolver.promptTokens(query) + 700
    return input to requested.coerceIn(512, 4096).coerceAtMost((7600 - input).coerceAtLeast(512))
}
fun main() = runBlocking {
    val passage = "Danger signs in a newborn include difficulty in breathing, chest indrawing, fast breathing of more than 60 breaths per minute, convulsions, lethargy, feeling cold or having fever, yellow palms and soles, and pus from the eyes or umbilicus. The ASHA should refer the baby immediately to the nearest facility and inform the ANM. Keep the baby warm and continue breastfeeding on the way. "
    val blocks = (1..16).map { PocketRagEvidenceBlock("E$it", "S$it", (passage.repeat(5)).take(1_600)) }
    val sources = (1..16).map { PocketRagSource("S$it", "S$it :: Home Based Newborn Care — page ${40 + it}") }
    fun packet(q: String) = PocketRagPacket(PocketRagSelection("generic", "asha", "ASHA guide"), q, blocks, sources, coverageComplete = true, bestRelevance = 6f)
    val draftEn = ("Answer: Danger signs in a newborn include fast breathing and convulsions [S1]. ").repeat(45).take(4_000)
    val draftHi = ("उत्तर: नवजात शिशु के खतरे के लक्षण में तेज़ साँस लेना और झटके आना शामिल हैं [S1]। ").repeat(40).take(4_000)

    for ((name, q, draft) in listOf(Triple("English", "What are the danger signs in a newborn?", draftEn),
                                     Triple("Hindi", "नवजात शिशु के खतरे के लक्षण क्या हैं?", draftHi),
                                     Triple("Short", "ORS?", draftEn.take(300)))) {
        val p = GroqChatProvider(); val r = PocketRagCloudResolver(p)
        r.generate(packet(q), emptyList(), draft, "check_online", 4000)
        val sent = p.lastQuery
        val (input, max) = groqMax(sent, p.lastMax)
        println("-- $name: prompt=${sent.length} chars, passages=${Regex("\\[E\\d+ \\|").findAll(sent).count()}/16, input≈$input tokens, answer allowance=$max, total=${input + max}")
        f(sent.contains("<user_question>$q</user_question>"), "$name: the question reaches the model")
        f(sent.contains("check the on-device answer online") && sent.contains("Cite evidence"), "$name: the check instructions reach the model")
        f(Regex("'(Answer|उत्तर):'").containsMatchIn(sent), "$name: the five-part shape reaches the model")
        f(input + max <= 7_600, "$name: input + answer fit Groq's 8,000 tokens/minute") { "${input + max}" }
        f(max >= 2_400, "$name: room for a full answer (>= 2,400 tokens)") { "$max" }
        f(sent.length <= 24_000, "$name: never cut by the 24,000-char safety limit")
    }
    println(if (ff == 0) "ALL $fp PASS" else "$ff FAILED, $fp passed")
}
