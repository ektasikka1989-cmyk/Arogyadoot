import com.example.arfas.*

// 11 Nov 2026 (AS): the rewrite's TOPIC line (the name the documents use for what the question describes).
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    val tb = "Child of 3 years has cough for more than 2 weeks and weight loss"
    val r = GenericKbQuestionRewrite.parse("SEARCH: What should be done when a 3 year old child has cough for more than 2 weeks and weight loss?\nTOPIC: tuberculosis\nCALC: NONE", tb)
    check("topic-parsed", r?.topic == "tuberculosis", "${r?.topic}")
    check("parts-unchanged", r?.parts?.size == 1)
    val none = GenericKbQuestionRewrite.parse("SEARCH: How is ORS prepared at home?\nTOPIC: NONE\nCALC: NONE", "ors kaise banaye")
    check("topic-none", none != null && none.topic == null)
    val noLine = GenericKbQuestionRewrite.parse("SEARCH: How is ORS prepared at home?\nCALC: NONE", "ors kaise banaye")
    check("no-topic-line-ok", noLine != null && noLine.topic == null)
    // Adds nothing: already in the question.
    val same = GenericKbQuestionRewrite.parse("SEARCH: How should a dog bite be treated?\nTOPIC: dog bite\nCALC: NONE", "dog bite what to do")
    check("topic-repeat-dropped", same != null && same.topic == null, "${same?.topic}")
    // Copied example: tuberculosis for a fever question.
    val copy = GenericKbQuestionRewrite.parse("SEARCH: What should be done when a child has convulsions?\nTOPIC: tuberculosis\nCALC: NONE", "bacche ko jhatke aa rahe hai kya kare")
    check("copied-example-dropped", copy != null && copy.topic == null, "${copy?.topic}")
    val copy2 = GenericKbQuestionRewrite.parse("SEARCH: What should an ASHA do when a pregnant woman has fever?\nTOPIC: bleeding in pregnancy\nCALC: NONE", "pregnant lady fever since 2 days")
    check("copied-example2-dropped", copy2 != null && copy2.topic == null, "${copy2?.topic}")
    val hing = GenericKbQuestionRewrite.parse("SEARCH: What should be done when a man has cough for three weeks and weight loss?\nTOPIC: tuberculosis\nCALC: NONE", "aadmi ko 3 hafte se khansi hai aur vajan kam ho raha hai kya kare")
    check("example-topic-kept-when-signs", hing?.topic == "tuberculosis", "${hing?.topic}")
    val short = GenericKbQuestionRewrite.parse("SEARCH: What should be done when a woman bleeds heavily after delivery?\nTOPIC: PPH\nCALC: NONE", "delivery ke baad bahut khoon")
    check("short-name-kept", short?.topic == "PPH", "${short?.topic}")
    val long = GenericKbQuestionRewrite.parse("SEARCH: What should be done for a child with fever and rash?\nTOPIC: this could be measles or another viral illness with a rash\nCALC: NONE", "child has fever and rash")
    check("long-topic-dropped", long != null && long.topic == null)
    val proc = GenericKbQuestionRewrite.parse("SEARCH: How much security must a supplier deposit for a contract?\nTOPIC: performance security\nCALC: NONE", "supplier ko kitna paisa jama karna hoga contract ke liye")
    check("other-kb-topic", proc?.topic == "performance security", "${proc?.topic}")
    val p = GenericKbQuestionRewrite.prompt(tb, emptyList())
    check("prompt-asks-topic", "TOPIC:" in p && p.trimEnd().endsWith("Write only the SEARCH, TOPIC and CALC lines for this last question."))
    check("flag-on", ArogyaFlags.rewriteTopic)
    // 12 Nov phone log: the search line contains the question itself; it was taken for an echo and the rewrite lost.
    val q5 = "pregnant woman looks pale and gets breathless while working"
    val e = GenericKbQuestionRewrite.parse("SEARCH: What should an ASHA do when a pregnant woman looks pale and gets breathless while working?\nTOPIC: anaemia in pregnancy\nCALC: NONE", q5)
    check("search-containing-question-kept", e?.search?.startsWith("What should an ASHA do when a pregnant woman looks pale") == true, "$e")
    check("its-topic-kept", e?.topic == "anaemia in pregnancy", "${e?.topic}")
    val echoed = GenericKbQuestionRewrite.parse("Question: $q5\nSEARCH: What should be done for a pale breathless pregnant woman?\nCALC: NONE", q5)
    check("real-echo-still-skipped", echoed?.search == "What should be done for a pale breathless pregnant woman?", "$echoed")
    val tb1 = GenericKbQuestionRewrite.parse("SEARCH: What should an ASHA do when a man has cough for three weeks and blood in sputum?\nTOPIC: tuberculosis\nCALC: NONE", "Man has cough for 3 weeks and blood in sputum")
    check("tb-one-sign-enough", tb1?.topic == "tuberculosis", "${tb1?.topic}")
    // 12 Nov (AZ): correct rewrites of Hindi/Hinglish child-fever questions were rejected as copied examples.
    val hz = GenericKbQuestionRewrite.parse("SEARCH: What should an ASHA do when a child has diarrhoea and fever?\nTOPIC: NONE\nCALC: NONE", "bacche ko dast aur bukhar hai kya karu")
    check("hinglish-fever-rewrite-kept", hz?.search == "What should an ASHA do when a child has diarrhoea and fever?", "$hz")
    val hz2 = GenericKbQuestionRewrite.parse("SEARCH: What should an ASHA do when a child has high fever and convulsions?\nCALC: NONE", "बच्चे को तेज बुखार है और झटके आ रहे हैं")
    check("hindi-fever-fits-kept", hz2?.search?.contains("convulsions") == true, "$hz2")
    val copy3 = GenericKbQuestionRewrite.parse("SEARCH: What advance payment is required for a booking?\nCALC: NONE", "bacche ko zinc kitne din dena hai")
    check("real-copy-still-rejected", copy3 == null, "$copy3")
    println("AsTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
