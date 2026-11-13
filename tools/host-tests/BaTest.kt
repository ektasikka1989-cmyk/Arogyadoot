import com.example.arfas.*
private var pass = 0; private var fail = 0
private fun check(n: String, ok: Boolean, d: String = "") { if (ok) { pass++; println("PASS $n") } else { fail++; println("FAIL $n $d") } }
fun main() {
    check("yn-row", GenericKbKeyFacts.isFormRow("Pus filled pustules Yes/No Yes/No Yes/No Yes/No Cracks or redness on the skin fold"))
    check("yn-slash", GenericKbKeyFacts.isFormRow("Are the eyes swollen or with pus Y/N Y/N Weight (on day 7, 14, 21, 28 and 42)"))
    check("days-header", GenericKbKeyFacts.isFormRow("Record the observations Day 1 Day 3 Day 7 Day 14 Action by the ASHA"))
    check("grid", GenericKbKeyFacts.isFormRow("Ask/Examine Date of ASHA's visit"))
    check("prose-kept", !GenericKbKeyFacts.isFormRow("Umbilical cord is swollen, red or has pus discharge. Refer to the nearest health facility."))
    check("single-yn-kept", !GenericKbKeyFacts.isFormRow("Ask the mother: is the baby feeding well? (Yes/No) If no, refer."))
    check("dose-table-kept", !GenericKbKeyFacts.isFormRow("2–6 months 10 mg once a day for 14 days; 6 months–5 years 20 mg once a day"))
    val units = GenericKbKeyFacts.candidates(listOf("Pus filled pustules Yes/No Yes/No Yes/No Yes/No. Umbilical cord is swollen, red or has pus discharge. Refer immediately."))
    check("candidates-cleaned", units.none { it.text.contains("Yes/No") } && units.any { it.text.contains("swollen") }, units.map { it.text }.toString())
    check("pure-form-dropped", GenericKbKeyFacts.candidates(listOf("Pus filled pustules Yes/No Yes/No Yes/No Yes/No Yes/No Yes/No Cracks Yes/No Yes/No")).isEmpty())
    check("flags", ArogyaFlags.formRowsOff && !ArogyaFlags.pickPassages && ArogyaFlags.guideFirst)
    println("BaTest: $pass passed, $fail failed"); if (fail > 0) kotlin.system.exitProcess(1)
}
