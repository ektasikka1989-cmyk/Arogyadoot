import com.example.arfas.*
var cf = 0; var cp = 0
fun c(ok: Boolean, m: String, d: () -> String = { "" }) { if (ok) { cp++; println("PASS $m") } else { cf++; println("FAIL $m :: ${d()}") } }
fun main() {
    val blocks = listOf(
        PocketRagEvidenceBlock("E1", "S1", "Danger signs in a newborn: fast breathing of more than 60 breaths per minute, chest indrawing, convulsions and lethargy. Refer immediately."),
        PocketRagEvidenceBlock("E2", "S2", "If the baby is unable to suckle, give expressed breast milk with a katori and spoon. Keep the baby warm using kangaroo mother care."),
        PocketRagEvidenceBlock("E3", "S3", "Under Janani Shishu Suraksha Karyakram free treatment, medicines and transport are provided for sick newborns."),
        PocketRagEvidenceBlock("E4", "S4", "Pregnant women should take one iron folic acid tablet daily for 180 days after the first trimester."))
    val sources = listOf(PocketRagSource("S1", "S1 :: Home Based Newborn Care — page 62"), PocketRagSource("S2", "S2 :: ASHA Module 6 — page 18"),
        PocketRagSource("S3", "S3 :: Integrated ASHA Training Module Part B — page 55"), PocketRagSource("S4", "S4 :: ANC Guidelines — page 12"))
    val p = PocketRagPacket(PocketRagSelection("generic", "asha", "ASHA guide"), "What are the danger signs in a newborn and what should I do?", blocks, sources, coverageComplete = true)
    // Gemma style: [S1] after every sentence, content from S1, S2 and S3.
    val gemma = "Danger signs include fast breathing of more than 60 breaths per minute, chest indrawing and convulsions [S1]. " +
        "If the baby is unable to suckle, give expressed breast milk with a katori and spoon [S1]. Keep the baby warm using kangaroo mother care [S1]. " +
        "Free treatment, medicines and transport are provided under Janani Shishu Suraksha Karyakram [S1]."
    val ids = p.citedSourceIds(gemma).map { it.substringAfter("kbref:").substringBefore(" ::") }
    c(ids.containsAll(listOf("S1", "S2", "S3")), "Gemma wrote only [S1]: S2 and S3 are credited from content") { ids.toString() }
    c("S4" !in ids, "an unrelated retrieved passage (IFA) is not credited") { ids.toString() }
    val only1 = "Danger signs include fast breathing of more than 60 breaths per minute, chest indrawing and convulsions [S1]. Refer immediately [S1]."
    val ids1 = p.citedSourceIds(only1).map { it.substringAfter("kbref:").substringBefore(" ::") }
    c(ids1 == listOf("S1"), "an answer using one passage still shows one source") { ids1.toString() }
    val re = p.reattributeCitations(gemma)
    c(re.contains("katori and spoon [S2]") && re.contains("kangaroo mother care [S2]") && re.contains("Karyakram [S3]") && re.contains("convulsions [S1]"),
        "each sentence's marker follows its passage (S1, S2, S2, S3)") { re }
    c(p.reattributeCitations(only1) == only1, "correct markers are left alone")
    val shown = KnowledgeSourcePresentation.renumberCitations(re, p.citedSourceIds(re))
    c(shown.contains("[1]") && shown.contains("[2]") && shown.contains("[3]"), "card shows three citation numbers") { shown }
    ArogyaFlags.citeAllSupportingSources = false
    val old = p.citedSourceIds(gemma).map { it.substringAfter("kbref:").substringBefore(" ::") }
    c(old == listOf("S1"), "(old behaviour, flag off: only S1 – the reported issue)") { old.toString() }
    println(if (cf == 0) "ALL $cp PASS" else "$cf FAILED, $cp passed")
}
