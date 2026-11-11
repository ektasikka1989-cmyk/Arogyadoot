import com.example.arfas.ArogyaAnswerFormat
import com.example.arfas.KbReplyText
import com.example.arfas.KnowledgeSourcePresentation
var pf = 0; var pp = 0
fun p(c: Boolean, m: String, d: () -> String = { "" }) { if (c) { pp++; println("PASS $m") } else { pf++; println("FAIL $m\n     ${d()}") } }

/** The phone answer path after generation, in the app's order (PocketRagEngine.runLocal → accept → card). */
fun pipeline(draft: String, evidence: String, ids: List<String>): String {
    var t = KbReplyText.trimRepeatedTail(draft)
    t = KbReplyText.removeRepeatedSentences(t)
    t = KbReplyText.dropFlippedProhibitions(t, evidence).first
    return KnowledgeSourcePresentation.renumberCitations(t, ids)
}
fun shown(final: String) = ArogyaAnswerFormat.sections(ArogyaAnswerFormat.stripCitationMarkers(final))

fun main() {
    val ids = listOf("kbref:S1 :: Home Based Newborn Care — page 62", "kbref:S2 :: ASHA Module 6 — page 18", "kbref:S4 :: Induction Module — page 50")
    val ev = "Danger signs in a newborn: difficulty in breathing or chest indrawing; fast breathing (more than 60 breaths per minute); " +
        "convulsions; lethargic or unconscious; baby feels cold or has fever; yellow palms and/or soles; pus from eyes or umbilicus. " +
        "Sponge the whole body with tepid water. Do not use cold water as it causes shivers. Do not cover with a blanket. Refer immediately."

    // A. The 22:39 "More detail" answer: misspelled Hindi label, run-together citations, steps.
    val a = "उततर: Danger signs in a newborn include: Difficulty in breathing or chest indrawing [S1]. Fast breathing (more than 60 breaths per minute) [S1]. " +
        "Convulsions [S1S1]. Being lethargic or unconscious [S1S1]. Baby feeling cold or having fever [S1S1]. Yellow palms and/or soles [S1S1]. Pus from eyes or umbilicus [S2].\n" +
        "Why it matters: These signs mean the baby may have a serious illness and needs care quickly [S1].\n" +
        "What to do: 1. Refer the baby immediately to the nearest facility [S1]. 2. Keep the baby warm and continue breastfeeding on the way [S2].\n" +
        "When to tell the ANM: Tell the ANM at once if you see any one of these signs [S1].\n" +
        "Common mistakes to avoid: Do not wait for the sign to go away on its own [S1]."
    val fa = pipeline(a, ev, ids)
    val sa = shown(fa)
    p(!Regex("\\[S\\d").containsMatchIn(fa), "A: no internal [S…] markers left (incl. [S1S1])") { fa }
    p(sa.map { it.heading } == listOf("उत्तर", "Why it matters", "What to do", "When to tell the ANM", "Common mistakes to avoid"), "A: five headings (misspelled label recognised)") { sa.map { it.heading }.toString() }
    p(sa.first().body.contains("more than 60 breaths per minute") && sa.first().body.contains("Convulsions"), "A: numbers and every sign kept") { sa.first().body }
    p(sa.first { it.heading == "What to do" }.body.lines().size == 2, "A: steps on their own lines") { sa.first { it.heading == "What to do" }.body }

    // B. The A/B draft: labels mid-line, a flipped "do not", the key action restated in three places.
    val b = "Answer: Give the first dose of paracetamol and refer the baby to the hospital immediately [S4]. Fever above 39.5 C is high fever [S4]. " +
        "Why it matters: High fever in a baby can be a sign of a serious illness [S1]. What to do: 1. Sponge the whole body with tepid water to avoid shivering and cover with a blanket [S4]. " +
        "2. Give the first dose of paracetamol and refer the baby to the hospital immediately [S4]. When to tell the ANM: if the baby has fits or cannot feed [S1]. " +
        "Common mistakes to avoid: Do not use cold water [S4]. Summary: give the first dose of paracetamol and refer the baby to hospital immediately."
    val fb = pipeline(b, ev, ids)
    val sb = shown(fb)
    p(!fb.contains("cover with a blanket"), "B: flipped 'cover with a blanket' removed") { fb }
    p(fb.length >= b.length * 0.75, "B: not shrunk (restated key action is not a loop)") { "${fb.length} of ${b.length}: $fb" }
    p(sb.map { it.heading }.containsAll(listOf("Answer", "Why it matters", "What to do", "When to tell the ANM", "Common mistakes to avoid")), "B: headings found mid-line") { sb.map { it.heading }.toString() }
    p(fb.contains("39.5"), "B: decimal number intact") { fb }

    // C. A looping draft: the good start is kept, the loop is cut.
    val c = "Answer: Refer the baby to the hospital immediately and keep the baby warm on the way [S1]. Why it matters: fever in a newborn can be serious [S1]. " +
        "What to do: sponge with tepid water [S4]. Checking baby's fever regularly helps you know when to refer the baby [S4]. ".repeat(1) +
        "Checking baby's fever regularly helps you know when to refer the baby [S4]. ".repeat(9) + "Checking baby's fe"
    val kept = KbReplyText.keepBeforeLoop(c)
    p(kept != null && kept.startsWith("Answer: Refer the baby") && Regex("Checking baby").findAll(kept).count() <= 1, "C: loop cut, good start kept") { kept.toString() }
    val fc = KnowledgeSourcePresentation.renumberCitations(kept ?: "", ids)
    p(shown(fc).map { it.heading }.take(3) == listOf("Answer", "Why it matters", "What to do"), "C: kept text still shows its headings") { shown(fc).map { it.heading }.toString() }

    println(if (pf == 0) "ALL $pp PASS" else "$pf FAILED, $pp passed")
    if (pf > 0) kotlin.system.exitProcess(1)
}
