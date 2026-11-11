import com.example.arfas.*

// 9 Nov 2026 (AH): a sentence repeated by adjacent overlapping passages is given to the model once.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    val s = "ASHAs should motivate every pregnant woman in their area to go to an appropriate health facility for delivery."
    val p1 = "Source: S1 :: Induction module page 69\nInstitutional delivery is safest. $s"
    val p2 = "Source: S1 :: Induction module page 69\n$s JSY gives cash assistance to mothers who deliver in a facility."
    val out = ArogyaEvidenceDedupe.dedupe(listOf(p1, p2))
    check("first-kept", s in out[0], out[0])
    check("repeat-removed", s !in out[1], out[1])
    check("rest-kept", "JSY gives cash assistance" in out[1], out[1])
    check("header-kept", out[1].startsWith("Source: S1 :: Induction module page 69"), out[1])
    check("short-kept", ArogyaEvidenceDedupe.dedupe(listOf("Yes. Refer.", "Yes. Refer."))[1].contains("Yes"))
    check("no-repeat-unchanged", ArogyaEvidenceDedupe.dedupe(listOf("Give ORS after every loose stool. Give zinc for 14 days.")) == listOf("Give ORS after every loose stool. Give zinc for 14 days."))
    val hi = "बच्चे को हर पतले दस्त के बाद ओआरएस का घोल देना चाहिए।"
    check("hindi-repeat-removed", hi !in ArogyaEvidenceDedupe.dedupe(listOf("क. $hi", "$hi ख."))[1])
    println("EvidenceDedupeTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
