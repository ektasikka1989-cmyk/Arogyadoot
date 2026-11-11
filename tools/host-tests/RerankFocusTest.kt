import com.example.arfas.*

// 9 Nov 2026 (AE): the re-ranker reads ~1,000 passage characters; long passages are scored on the part
// that answers the question (rarity weights from the guide, no word list).
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    GenericKbKeyFacts.corpusWeight = null
    val short = "Heading\nGive ORS after every loose stool."
    check("short-unchanged", GenericKbRerankFocus.focus("how to give ors", short) == short)

    // The real case: the danger-sign list sits after ~1,000 characters of anaemia text.
    val filler = (1..12).joinToString(" ") { "Anaemia is due to a reduced level of haemoglobin in the blood number $it." }
    val tail = "Pregnant women with the following conditions should be referred: vaginal bleeding; swelling of face and hands; high blood pressure, headache, dizziness or blurred vision; convulsions."
    val passage = "8.1 Care During Pregnancy/Ante Natal Care\n$filler $tail"
    check("long-passage", passage.length > GenericKbRerankFocus.WINDOW_CHARS)
    val f = GenericKbRerankFocus.focus("pregnant woman swelling of feet and headache what to do", passage)
    check("focus-finds-tail", "swelling of face" in f && "headache" in f, f.take(200))
    check("focus-keeps-heading", f.startsWith("8.1 Care During Pregnancy/Ante Natal Care\n"))
    check("focus-fits", f.length <= GenericKbRerankFocus.WINDOW_CHARS, "${f.length}")
    check("focus-whole-sentences", f.substringAfter('\n').first().isUpperCase())

    // The beginning answers the question: passage unchanged (scored exactly as before).
    val head = GenericKbRerankFocus.focus("what is anaemia haemoglobin", passage)
    check("head-best-unchanged", head == passage)
    // No question words at all: unchanged.
    check("no-words-unchanged", GenericKbRerankFocus.focus("??", passage) == passage)

    // Rare words count more than common ones (guide weights).
    GenericKbKeyFacts.corpusWeight = { s -> if (s.startsWith("convuls")) 5.0 else 0.1 }
    val g = GenericKbRerankFocus.focus("blood convulsions", passage)
    check("rare-word-wins", "convulsions" in g, g.take(120))
    GenericKbKeyFacts.corpusWeight = null

    println("RerankFocusTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
