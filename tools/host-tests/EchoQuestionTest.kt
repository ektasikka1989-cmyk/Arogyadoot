import com.example.arfas.*

// 11 Nov 2026 (AO): the question written again as a title before "Answer:" is dropped; nothing else changes.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    val q = "किशोरी को माहवारी में बहुत दर्द होता है"
    val hi = "किशिश को माहवारी में दर्द होने पर मदद कैसे करें\n\nउत्तर: गर्म पानी की बोतल पेट पर रखें।\nक्यों ज़रूरी: दर्द कम होता है।"
    val out = ArogyaAnswerFormat.dropEchoedQuestion(hi, q)
    check("hindi-echo-dropped", out.startsWith("उत्तर:"), out.take(40))
    val en = "How do I make ORS at home?\nAnswer: Mix one packet in one litre of clean water."
    check("english-echo-dropped", ArogyaAnswerFormat.dropEchoedQuestion(en, "how do i make ors at home").startsWith("Answer:"))
    // Kept: the answer itself without a label, followed by another section.
    val noLabel = "Give ORS after every loose stool and continue feeding\nWhy it matters: it prevents dehydration."
    check("unlabelled-answer-kept", ArogyaAnswerFormat.dropEchoedQuestion(noLabel, "give ors after loose stool") == noLabel)
    // Kept: a first line that is a sentence (full stop).
    val sentence = "ORS should be given to the child.\nAnswer: Mix one packet in one litre."
    check("sentence-kept", ArogyaAnswerFormat.dropEchoedQuestion(sentence, "ors child") == sentence)
    // Kept: an unrelated title-like line.
    val other = "Danger sign\nAnswer: Refer now."
    check("unrelated-kept", ArogyaAnswerFormat.dropEchoedQuestion(other, "how to make ors at home") == other)
    // Kept: normal labelled answer, empty text.
    val normal = "Answer: Mix one packet.\nWhat to do: 1. Wash hands."
    check("normal-kept", ArogyaAnswerFormat.dropEchoedQuestion(normal, "how to make ors") == normal)
    check("empty-kept", ArogyaAnswerFormat.dropEchoedQuestion("", "x") == "")
    println("EchoQuestionTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
