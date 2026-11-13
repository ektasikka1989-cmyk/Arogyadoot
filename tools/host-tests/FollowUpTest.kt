import com.example.arfas.*

// 9 Nov 2026: follow-up questions with the on-device model's rewrite (no word lists).
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

// A small "guide": word weights come from how many of its passages contain each word.
private val GUIDE = (listOf(
    "Give zinc for 14 days to a child with diarrhoea.",
    "Give ORS after every loose stool.",
    "Polio vaccine is given at birth.",
) + List(40) { i -> "What should the child be given? The child should be given care by the mother every day. Passage $i should be read." })

private fun loadWeights() {
    val tok = Regex("[\\p{L}\\p{N}]+")
    val df = HashMap<String, Int>()
    GUIDE.forEach { p -> tok.findAll(p.lowercase()).map { GenericKbKeyFacts.stem(it.value) }.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
    val n = GUIDE.size.toDouble()
    GenericKbKeyFacts.corpusWeight = { s -> df[s]?.let { kotlin.math.ln(1.0 + n / it) } ?: kotlin.math.ln(1.0 + n) }
}

fun main() {
    val prev = "What is the dose of zinc for a 4 month old baby with diarrhoea?"

    // The prompt carries the earlier question only for a follow-up; the first question's prompt is unchanged.
    val first = GenericKbQuestionRewrite.prompt("aur kitne din dena hai?", emptyList())
    val follow = GenericKbQuestionRewrite.prompt("aur kitne din dena hai?", emptyList(), prev)
    check("prompt-first-no-earlier", "Earlier question" !in first)
    check("prompt-follow-earlier", "Earlier question: $prev" in follow)
    check("prompt-follow-question-last", follow.trimEnd().endsWith("Write only the FOLLOWS, SEARCH, TOPIC and CALC lines for this last question.") && first.trimEnd().endsWith("Write only the SEARCH, TOPIC and CALC lines for this last question."))
    check("prompt-follow-order", follow.indexOf("Earlier question: $prev") < follow.indexOf("Question: aur kitne din dena hai?"))

    // A follow-up's rewrite naming the earlier subject is kept (not taken for a copied example).
    val out = "SEARCH: For how long should zinc be given to a child with diarrhoea?\nCALC: NONE"
    check("parse-follow-kept", GenericKbQuestionRewrite.parse(out, "how long?", prev)?.search?.contains("zinc") == true)
    check("parse-example-copy-dropped", GenericKbQuestionRewrite.parse("SEARCH: For how many days should zinc be given to a young child?", "ors kaise banaye", "") == null)

    // The model's own judgement: FOLLOWS: YES / NO (asked only when an earlier question is shown).
    check("prompt-follows-asked", "FOLLOWS: YES" in follow && "FOLLOWS:" !in first)
    val yes = GenericKbQuestionRewrite.parse("FOLLOWS: YES\nSEARCH: For how many days should zinc be given to a child?\nCALC: NONE", "aur kitne din?", prev)
    check("parse-follows-yes", yes?.followsEarlier == true, yes.toString())
    val no = GenericKbQuestionRewrite.parse("FOLLOWS: NO\nSEARCH: What should be done when a 2 year old child is not eating?\nCALC: NONE", "bachha 2 saal ka hai khana nahi kha raha", prev)
    check("parse-follows-no", no?.followsEarlier == false && no.search.contains("not eating"), no.toString())
    check("parse-follows-absent-first", GenericKbQuestionRewrite.parse("SEARCH: What is the zinc dose for a child?", "zinc dose", "")?.followsEarlier == null)
    check("parse-new-example-copy-dropped", GenericKbQuestionRewrite.parse("FOLLOWS: NO\nSEARCH: When should a newborn be given the first bath?", "what is ors", prev) == null)

    // Recognising a follow-up the model rewrote: a specific word of the earlier question the new one lacks.
    GenericKbKeyFacts.corpusWeight = null
    check("no-weights-not-followup", !GenericKbKeyFacts.carriesOver(listOf("For how many days should zinc be given?"), "aur kitne din?", prev))
    loadWeights()
    check("followup-zinc", GenericKbKeyFacts.carriesOver(listOf("For how many days should zinc be given to a young child?"), "aur kitne din dena hai?", prev))
    check("followup-short-word", GenericKbKeyFacts.carriesOver(listOf("How often should ORS be given?"), "how often should it be given?", "How is ORS prepared at home?"))
    check("new-topic-not-followup", !GenericKbKeyFacts.carriesOver(listOf("When is the polio vaccine given?"), "polio vaccine kab dete hai?", prev))
    check("common-word-not-followup", !GenericKbKeyFacts.carriesOver(listOf("What care should the mother give the child?"), "maa kya kare?", "What should the child be given every day?"))
    check("first-question-not-followup", !GenericKbKeyFacts.carriesOver(listOf("What is the zinc dose?"), "zinc dose?", ""))
    GenericKbKeyFacts.corpusWeight = null

    println("FollowUpTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
