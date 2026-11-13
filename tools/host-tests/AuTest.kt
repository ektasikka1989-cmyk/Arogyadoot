import com.example.arfas.*
import java.io.File
import org.json.JSONObject

// 12 Nov 2026 (AU): filler removal on the phone's real answers; fact-passage-only windows.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main(args: Array<String>) {
    val f = File(System.getProperty("eval") ?: "eval_answers_20.jsonl")
    var n = 0
    if (f.exists()) f.readLines().filter { it.isNotBlank() }.forEach { line ->
        val j = JSONObject(line); val t = j.optString("text"); if (t.isBlank()) return@forEach
        n++
        val out = ArogyaAnswerFormat.dropNotCoveredFiller(t)
        check("q${j.getInt("n")}-no-filler", !out.contains("This part is not covered") && !Regex("(?i)there (are|is) no [^.]{0,80}mentioned in the evidence").containsMatchIn(out), out.takeLast(200))
        check("q${j.getInt("n")}-no-empty-label", !Regex("(?i)(common mistakes to avoid|when to tell the anm|why it matters|what to do)\\s*:\\s*(\\n|$)").containsMatchIn(out), out.takeLast(200))
        // Nothing else removed: every sentence of the original that is not filler survives.
        val kept = t.split(Regex("(?<=[.!?])\\s+")).filter { s -> !s.contains("not covered in the selected") && !Regex("(?i)there (are|is) no").containsMatchIn(s) && s.length > 30 }
            .all { s -> out.contains(s.trim().removePrefix("Common mistakes to avoid:").removePrefix("When to tell the ANM:").trim().take(40)) }
        check("q${j.getInt("n")}-content-kept", kept)
    }
    check("real-answers-read", n >= 10, "n=$n")
    // App's own named disclosure stays.
    val own = "Answer: Give ORS.\n\nNot covered in the selected knowledge base: “and the cost”."
    check("own-disclosure-kept", ArogyaAnswerFormat.dropNotCoveredFiller(own) == own)
    check("only-filler-unchanged", ArogyaAnswerFormat.dropNotCoveredFiller("This part is not covered in the selected knowledge base.") == "This part is not covered in the selected knowledge base.")
    val hi = "उत्तर: तुरंत रेफर करें।\nआम गलतियाँ: This part is not covered in the selected knowledge base."
    check("hindi-empty-label", ArogyaAnswerFormat.dropNotCoveredFiller(hi) == "उत्तर: तुरंत रेफर करें।", ArogyaAnswerFormat.dropNotCoveredFiller(hi))
    val honest = "Answer: Give zinc for 14 days. The adult dose is not covered by the evidence."
    check("specific-gap-kept", ArogyaAnswerFormat.dropNotCoveredFiller(honest) == honest)

    // Windows: fact passages only.
    val ps = listOf("Trachoma stye swelling of the eyelid. ".repeat(3), "Avoid heat exposure between 12 and 4 pm. ".repeat(3),
        "Measles: fever and rash with red eyes. ".repeat(3), "Measles vaccine at 9 months. ".repeat(3), "Other passage text here. ".repeat(3))
    val facts = listOf(GenericKbKeyFacts.Fact(2, 0, "Measles: fever and rash with red eyes.", 5f), GenericKbKeyFacts.Fact(3, 0, "Measles vaccine at 9 months.", 3f))
    val all = GenericKbKeyFacts.passageWindows(ps, facts).map { it.first }
    val only = GenericKbKeyFacts.passageWindows(ps, facts, factPassagesOnly = true).map { it.first }
    check("default-fills", 1 in all || 4 in all, all.toString())
    check("facts-only", only == listOf(0, 2, 3), only.toString())
    check("no-facts-falls-back", GenericKbKeyFacts.passageWindows(ps, emptyList(), factPassagesOnly = true).size == GenericKbKeyFacts.passageWindows(ps, emptyList()).size)
    check("flags-on", ArogyaFlags.factPassagesOnly && ArogyaFlags.dropFiller)
    println("AuTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
