import com.example.arfas.*

// 11 Nov 2026 (AQ): script penalty, Hindi word meanings for the rewrite, headed parts of a kept loop text.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    val en = "What should be done when a 3 year old child has cough for more than 2 weeks and weight loss? Child of 3 years has cough"
    val hiPassage = "बहुत पतला पतला एकदम ठीक तर छोटे आकार के खाद्य पदार्थ, जैसे-किशमिश, मूंगफली, मटर खिलाते समय सावधानी बरतें।"
    val enPassage = "Tuberculosis A child with fever and/or cough for more than 2 weeks, with loss of weight."
    check("en-q-hindi-passage", GenericKbScriptMatch.penalty(en, hiPassage) == GenericKbScriptMatch.PENALTY)
    check("en-q-en-passage", GenericKbScriptMatch.penalty(en, enPassage) == 0f)
    val mixed = "Source: S3 Journey (Hindi) page 4\n$hiPassage"
    check("mixed-header-still-hindi", GenericKbScriptMatch.penalty(en, mixed) > 0f)
    val hiq = "What should be done when a pregnant woman has swelling? गर्भवती महिला के पैरों में सूजन है"
    check("hindi-q-never", GenericKbScriptMatch.penalty(hiq, hiPassage) == 0f)
    val adj = GenericKbScriptMatch.adjust(en, listOf(hiPassage, enPassage), floatArrayOf(3.98f, 2.1f))!!
    check("adjust-order", adj[1] > adj[0], adj.joinToString())
    val same = floatArrayOf(1f, 2f)
    check("adjust-hindi-unchanged", GenericKbScriptMatch.adjust(hiq, listOf(hiPassage, enPassage), same) === same)
    check("adjust-null", GenericKbScriptMatch.adjust(en, listOf(hiPassage), null) == null)
    check("hinglish-latin-penalised", GenericKbScriptMatch.penalty("bacche ko khansi hai kya kare", hiPassage) > 0f)

    val rq = GenericKbScriptMatch.rerankQuestion(hiq)
    check("rerankq-english-part", rq == "What should be done when a pregnant woman has swelling?", rq)
    check("rerankq-short-english-kept", GenericKbScriptMatch.rerankQuestion("ORS गर्भवती महिला") == "ORS गर्भवती महिला")
    check("rerankq-english-unchanged", GenericKbScriptMatch.rerankQuestion(en) == en)
    check("rerankq-then-penalty", GenericKbScriptMatch.penalty(rq, hiPassage) > 0f)
    val g = ArogyaHindiTerms.glosses("गर्भवती महिला के पैरों में सूजन और सिरदर्द है")
    check("gloss-swelling", g.any { it.startsWith("सूजन = swelling") }, g.toString())
    check("gloss-headache", g.any { it == "सिरदर्द = headache" }, g.toString())
    check("gloss-feet", g.any { it.startsWith("पैर = feet") }, g.toString())
    check("gloss-no-bare-pain", g.none { it.startsWith("दर्द =") }, g.toString())
    check("gloss-no-question-words", ArogyaHindiTerms.glosses("बुखार में क्या करें कैसे दें").none { it.startsWith("कैसे") })
    check("gloss-english-empty", ArogyaHindiTerms.glosses("child has fever").isEmpty())
    val v = ArogyaHindiTerms.views("गर्भवती महिला के पैरों में सूजन और सिरदर्द है").joinToString(" ")
    check("views-swelling-headache", "swelling" in v && "headache" in v, v)
    val p = GenericKbQuestionRewrite.prompt("गर्भवती महिला के पैरों में सूजन और सिरदर्द है", emptyList())
    check("prompt-has-gloss", "सूजन = swelling" in p, p.takeLast(400))

    val keptHi = "उत्तर: गर्भवती महिला के पैरों में सूजन और सिरदर्द होने पर तत्काल स्वास्थ्य सुविधा में रेफर करना महत्वपूर्ण है। यह स्थिति गंभीर हो सकती है।"
    check("kept-hindi-one-part", ArogyaAnswerFormat.sections(keptHi).count { it.heading.isNotEmpty() } == 1)
    val enAns = "Answer: Danger signs include fast breathing [1]. Why it matters: These signs indicate serious illness [2]. What to do: Refer to the nearest health facility [1]."
    check("english-inline-three-parts", ArogyaAnswerFormat.sections(enAns).count { it.heading.isNotEmpty() } >= 2, ArogyaAnswerFormat.sections(enAns).toString())
    val hi2 = "उत्तर: रेफर करें।\nक्या करें: तुरंत अस्पताल भेजें।"
    check("hindi-two-parts", ArogyaAnswerFormat.sections(hi2).count { it.heading.isNotEmpty() } == 2)

    println("AqTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
