import com.example.arfas.ArogyaNotFromGuide as G
var gf = 0; var gp = 0
fun g(c: Boolean, m: String, d: () -> String = { "" }) { if (c) { gp++; println("PASS $m") } else { gf++; println("FAIL $m ${d()}") } }
fun main() {
    val general = "This is not covered by the selected knowledge base. General answer (not from its documents):\n\nA bullet train is a high-speed train that runs on dedicated tracks."
    val b = G.answerBody(general, web = false)
    g(b == "A bullet train is a high-speed train that runs on dedicated tracks.", "general answer body extracted") { b.toString() }
    val hi = "यह चुने हुए ज्ञान आधार में नहीं है। सामान्य उत्तर (इसके दस्तावेज़ों से नहीं):\n\nयह एक सामान्य उत्तर है जो गाइड से नहीं है।"
    g(G.answerBody(hi, web = false)?.startsWith("यह एक सामान्य उत्तर") == true, "Hindi general answer body extracted")
    g(G.answerBody("I couldn't find enough relevant information in the ASHA guide to answer that reliably.", web = false) == null, "not-found message is not an answer")
    g(G.answerBody("The selected knowledge base does not answer this, and the web search could not be reached just now. Please try again in a moment.", web = true) == null, "web unavailable message is not an answer")
    g(G.answerBody("Dengue vaccines are not part of India's routine immunization schedule as of 2026; prevention relies on mosquito control.", web = true) != null, "web answer counts")
    g(G.answerBody("Some text without a general label that is long enough to look like an answer.", web = false) == null, "offline text without the general label is not treated as an answer")
    val l = G.labelled("A bullet train is a high-speed train.", listOf("Immunization", "Newborn care"), web = false, hindi = false)
    g(l.startsWith("⚠ Not from your ASHA guides.") && "Check online" in l && "ANM" in l && "A bullet train" in l && "Closest guide topics: Immunization; Newborn care" in l, "English label, answer, topics") { l }
    val lw = G.labelled("Answer.", emptyList(), web = true, hindi = false)
    g("web search" in lw && "Closest guide topics" !in lw, "web label, no empty topics line") { lw }
    val lh = G.labelled("उत्तर।", listOf("टीकाकरण"), web = false, hindi = true)
    g(lh.startsWith("⚠ यह आपकी ASHA गाइड से नहीं है।") && "ऑनलाइन जाँचें" in lh && "ANM" in lh && "गाइड में सबसे करीबी विषय: टीकाकरण" in lh, "Hindi label") { lh }
    println(if (gf == 0) "ALL $gp PASS" else "$gf FAILED, $gp passed")
    if (gf > 0) kotlin.system.exitProcess(1)
}
