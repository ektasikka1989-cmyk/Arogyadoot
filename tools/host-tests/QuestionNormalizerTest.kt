import com.example.arfas.ArogyaClinicalText
import com.example.arfas.ArogyaHindiTerms
import com.example.arfas.ArogyaQuestionNormalizer as N
import com.example.arfas.ArogyaQuestionType
var nf = 0; var np = 0
fun n(c: Boolean, m: String, d: () -> String = { "" }) { if (c) { np++; println("PASS $m") } else { nf++; println("FAIL $m ${d()}") } }
fun main() {
    val vocab = listOf("../../app/src/main/assets/guide/vocab_en.txt", "app/src/main/assets/guide/vocab_en.txt")
        .map { java.io.File(it) }.firstOrNull { it.exists() }
    n(vocab != null, "vocabulary file found")
    vocab?.useLines { N.loadVocabulary(it) }
    n(N.vocabulary.size > 10_000, "vocabulary loaded (${N.vocabulary.size} words)")

    // English typos, SMS spellings, digit slips (expected outputs checked against the guide vocabulary).
    val cases = listOf(
        "a baby is havong h9gh fever" to "a baby is having high fever",
        "wat shud i do if baby has fevr" to "what should i do if baby has fever",
        "bby not drinking milk wat to do" to "baby not drinking milk what to do",
        "newbron baby not feeding" to "newborn baby not feeding",
        "child hav diarrea since 3 dys" to "child have diarrhoea since 3 days",
        "breast feeding positon" to "breast feeding position",
        "what is the dose of paracetmol for 2 year child" to "what is the dose of paracetamol for 2 year child",
        "pregnent woman bp high" to "pregnant woman bp high",
        "mother has bleding after delivry" to "mother has bleeding after delivery",
        "baby has diahrea and vomting" to "baby has diarrhoea and vomiting",
        "cn i giv ors to 2 month baby" to "can i give ors to 2 month baby",
        "how to giv zink to child" to "how to give zinc to child",
        "kid vomitting after feeding" to "child vomiting after feeding",
        "pregnent lady bleding wat to do" to "pregnant lady bleeding what to do"
    )
    for ((q, want) in cases) { val got = N.normalize(q).text; n(got == want, "fix: '$q'") { "got '$got'" } }

    // Correct text is never changed (English, medical words, abbreviations, numbers, names).
    val untouched = listOf("The baby is having high fever, what should I do?", "danger signs in pregnancy",
        "signs of pneumonia in children", "baby has convulsions", "child with measles", "how much ifa tablet in pregnancy",
        "kangaroo mother care steps", "what is hbnc", "baby weight 2.5kg is it low", "dog bite what to do",
        "low birth weight baby care", "sam child management", "muac tape red", "tb dots duration", "covid vaccine",
        "my name is sunita", "ok thanks", "hi asha didi", "hb 8 hai kya karu", "zinc kitne din dena hai")
    for (q in untouched) { val r = N.normalize(q); n(!r.changed, "unchanged: '$q'") { "got '${r.text}' ${r.fixes}" } }

    // Hinglish: canonical spellings and English meanings.
    val hb = N.normalize("bacche ko bukhaar hai kya karein")
    n(hb.text == "bacche ko bukhar hai kya karein" && "fever" in hb.hinglishEnglish, "hinglish: bukhaar -> bukhar = fever") { "${hb.text} ${hb.hinglishEnglish}" }
    val hd = N.normalize("bachhe ko ulty aur dast")
    n(hd.text == "bacche ko ulti aur dast" && hd.hinglishEnglish.any { "vomiting" in it } && hd.hinglishEnglish.any { "diarrhoea" in it },
        "hinglish: bachhe/ulty/dast") { "${hd.text} ${hd.hinglishEnglish}" }
    val ht = N.normalize("khasi aur tez saans")
    n(ht.text == "khansi aur tez saans" && "fast breathing" in ht.hinglishEnglish && "cough" in ht.hinglishEnglish, "hinglish: khasi, tez saans") { "${ht.text} ${ht.hinglishEnglish}" }
    val hk = N.normalize("garbhvati mahila ko khoon ki kami")
    n("anaemia" in hk.hinglishEnglish && "pregnant" in hk.hinglishEnglish, "hinglish: khoon ki kami = anaemia") { hk.hinglishEnglish.toString() }
    val hdena = N.normalize("tez bukhar me kya dena chahiye")
    n("rash" !in hdena.hinglishEnglish && "high fever" in hdena.hinglishEnglish, "hinglish: 'dena' is not 'daane' (rash)") { hdena.hinglishEnglish.toString() }
    n(N.normalize("बच्चे को बुखार है").text == "बच्चे को बुखार है", "devanagari unchanged")

    // Every later step now reads the cleaned question.
    val K = ArogyaQuestionType.Kind.URGENT
    n(ArogyaQuestionType.kindOf("wat shud i do if baby has fevr") == K, "kind: 'wat shud i do' -> URGENT")
    n(ArogyaQuestionType.kindOf("bacche ko bukhaar hai kya karein") == K, "kind: Hinglish 'kya karein' -> URGENT")
    n(ArogyaQuestionType.kindOf("how to make ors at home") == ArogyaQuestionType.Kind.PROCEDURE, "kind: how-to unchanged")
    val av = ArogyaClinicalText.ageViews("bby has h9gh fevr")
    n(av.size == 1 && av[0].contains("young infant") && av[0].contains("fever"), "age view: 'bby has h9gh fevr'") { av.toString() }
    n(ArogyaHindiTerms.needsViews("bachhe ko ulty aur dast"), "hinglish views needed for 'bachhe ko ulty aur dast'")
    val v = ArogyaHindiTerms.views("bachhe ko ulty aur dast").joinToString(" ")
    n("vomiting" in v && "diarrhoea" in v, "hinglish views: vomiting + diarrhoea") { v }

    println(if (nf == 0) "ALL $np PASS" else "$nf FAILED, $np passed")
    if (nf > 0) kotlin.system.exitProcess(1)
}
