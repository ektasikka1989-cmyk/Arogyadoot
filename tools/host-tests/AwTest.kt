import com.example.arfas.*

// 12 Nov 2026 (AW): pick-then-write parsing; phone answer shape follows the material.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    check("pick-2-1", ArogyaPassagePick.parse("2,1", 4) == listOf(1, 0))
    check("pick-spaces", ArogyaPassagePick.parse("Passages: 3, 2", 4) == listOf(2, 1))
    check("pick-zero", ArogyaPassagePick.parse("0", 4) == null)
    check("pick-all-is-none", ArogyaPassagePick.parse("1,2,3", 3) == null)
    check("pick-out-of-range", ArogyaPassagePick.parse("7", 4) == null)
    check("pick-garbage", ArogyaPassagePick.parse("The passage about TB", 4) == null)
    check("pick-dupes-max3", ArogyaPassagePick.parse("2,2,3,1,4", 5) == listOf(1, 2, 0))
    check("pick-single-passage", ArogyaPassagePick.parse("1", 1) == null)
    check("pick-blank", ArogyaPassagePick.parse("", 3) == null && ArogyaPassagePick.parse(null, 3) == null)
    val p = ArogyaPassagePick.prompt("Child has fever and rash", listOf("Source ID: S1\nSource: S1 :: Guide p1\nMeasles: fever and rash with red eyes.", "Avoid heat " + "x".repeat(600)))
    check("prompt-numbers", "[1] Measles: fever and rash" in p && "[2] Avoid heat" in p, p)
    check("prompt-no-headers", "Source ID" !in p)
    check("prompt-snippet-cut", p.lines().first { it.startsWith("[2]") }.length < ArogyaPassagePick.SNIPPET_CHARS + 20)
    check("prompt-ends-format", p.trimEnd().endsWith("write: 0"))

    val phone = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.URGENT, "child has fever and rash")
    val cloud = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.URGENT, "child has fever and rash", cloud = true)
    check("phone-no-fixed-count", "eight to fourteen" !in phone && "never a single sentence" !in phone, phone)
    check("phone-why-optional", "only when the material says why" in phone)
    check("phone-no-other-passages", "never add a sentence from a passage about something else" in phone)
    check("phone-labels-kept", listOf("'Answer:'", "'What to do:'", "'When to tell the ANM:'").all { it in phone })
    check("cloud-unchanged", "eight to fourteen" in cloud)
    val hi = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.FACT, "बुखार में क्या करें")
    check("hindi-labels", "'उत्तर:'" in hi && "'क्या करें:'" in hi)
    check("flag-off", !ArogyaFlags.pickPassages)
    // AX: the search's best passage is always kept.
    check("best-kept", ArogyaPassagePick.withBest(listOf(2), 5) == listOf(0, 2))
    check("best-already-picked", ArogyaPassagePick.withBest(listOf(0, 3), 5) == listOf(0, 3))
    check("best-max3", ArogyaPassagePick.withBest(listOf(3, 2, 1), 5) == listOf(0, 3, 2))
    check("best-all-is-none", ArogyaPassagePick.withBest(listOf(1), 2) == null)
    check("best-null", ArogyaPassagePick.withBest(null, 4) == null)
    val phone2 = ArogyaQuestionType.lengthLine(ArogyaQuestionType.Kind.FACT, "pregnant woman pale")
    check("every-dose-kept", "every step, dose, number and referral rule" in phone2)
    println("AwTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
