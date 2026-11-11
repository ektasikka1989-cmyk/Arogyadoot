import com.example.arfas.*
import kotlinx.coroutines.runBlocking

// 9 Nov 2026: evidence for every problem the on-device model named (ArogyaConcerns + focus words). No word lists:
// the "guide" here is a small set of passages; word weights come from how many of them contain each word.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

private val GUIDE = listOf(
    "What should be done when the child has diarrhoea? Give ORS after every loose stool and zinc for 14 days.",
    "If the child vomits, wait for 10 minutes and then give ORS more slowly.",
    "What should be done when a child has fever? Give paracetamol and sponge with tepid water.",
    "What should an ASHA do when a newborn is not feeding well? Refer to the facility.",
    "What should be done for a pregnant woman with swelling of feet and severe headache? Refer urgently.",
    "The child should be weighed every month. What should be done is explained by the ANM.",
    "Wash hands with soap before feeding the child.",
    "Keep the child warm. Continue breastfeeding the child.",
)

private fun loadWeights() {
    val tok = Regex("[\\p{L}\\p{N}]+")
    val df = HashMap<String, Int>()
    GUIDE.forEach { p -> tok.findAll(p.lowercase()).map { GenericKbKeyFacts.stem(it.value) }.toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
    val n = GUIDE.size.toDouble()
    GenericKbKeyFacts.corpusWeight = { s -> df[s]?.let { kotlin.math.ln(1.0 + n / it) } ?: kotlin.math.ln(1.0 + n) }
}

fun main() = runBlocking {
    val lines = listOf("What should be done when a child has diarrhoea?", "What should be done when a child is vomiting?")

    // Without guide weights nothing is forced (old behaviour).
    GenericKbKeyFacts.corpusWeight = null
    check("no-weights-no-focus", ArogyaConcerns.focus(lines[1], lines) == null)
    check("no-weights-nothing-uncovered", ArogyaConcerns.uncovered(lines, listOf("unrelated")).isEmpty())

    loadWeights()
    check("focus-vomit", ArogyaConcerns.focus(lines[1], lines) == "vomit", "${ArogyaConcerns.focus(lines[1], lines)}")
    check("focus-diarrhoea", ArogyaConcerns.focus(lines[0], lines) == "diarr", "${ArogyaConcerns.focus(lines[0], lines)}")
    // Shared wording ("what should be done when") is never the focus, even if the guide rarely uses "done".
    val two = listOf("What should be done when a child has fever?", "What should an ASHA do when a newborn is not feeding?")
    check("focus-not-filler", ArogyaConcerns.focus(two[0], two) !in setOf("what", "shoul", "done", "when"), "${ArogyaConcerns.focus(two[0], two)}")
    check("covers-inflection", ArogyaConcerns.covers(lines[1], "If the child vomits, wait 10 minutes", lines))
    check("not-covers", !ArogyaConcerns.covers(lines[1], GUIDE[0], lines))
    check("uncovered", ArogyaConcerns.uncovered(lines, listOf(GUIDE[0])) == listOf(lines[1]))
    check("all-covered", ArogyaConcerns.uncovered(lines, listOf(GUIDE[0], GUIDE[1])).isEmpty())

    // Picks: only hits naming the focus word, not already kept, at most PER_CONCERN, only the first PICK_DEPTH.
    val hits = listOf("a" to GUIDE[2], "b" to GUIDE[1], "c" to GUIDE[0], "d" to "child vomits again", "e" to "vomiting child")
    val picked = ArogyaConcerns.pick(lines[1], lines, hits, { it.second }, { it.first }, setOf("d"))
    check("pick", picked.map { it.first } == listOf("b"), picked.toString())
    check("fromMain", ArogyaConcerns.fromMain(lines[1], lines, hits) { it.second }.map { it.first } == listOf("b", "d"))

    // Facts of one part must contain its own problem word.
    val f = GenericKbKeyFacts.select(lines[1], GUIDE.take(3), null, 600, 3, requireFocus = true, siblings = listOf(lines[0]))
    check("focus-fact", f.isNotEmpty() && f.all { "vomit" in it.text.lowercase() }, f.map { it.text }.toString())
    val none = GenericKbKeyFacts.select("What should be done when a child has jaundice?", GUIDE.take(3), null, 600, 3, requireFocus = true)
    check("focus-fact-none", none.isEmpty(), none.map { it.text }.toString())

    // merge keeps the main head exactly; extra passages follow it; duplicates of the head are dropped.
    val main = listOf("a", "b", "c", "d", "e", "f", "g")
    val merged = ArogyaConcerns.merge(main, listOf(listOf("x", "b"), listOf("y")), { it }, 4)
    check("merge-head-identical", merged.take(4) == listOf("a", "b", "c", "d"), merged.toString())
    check("merge-extras-next", merged.subList(4, 6) == listOf("x", "y"), merged.toString())
    check("merge-rest-kept", merged.drop(6) == listOf("e", "f", "g"), merged.toString())
    check("merge-nothing", ArogyaConcerns.merge(main, listOf(emptyList()), { it }, 4) == main)
    check("prioritise", ArogyaConcerns.prioritise(listOf("v"), listOf("a", "v", "b")) { it } == listOf("v", "a", "b"))
    check("prioritise-empty", ArogyaConcerns.prioritise(emptyList(), listOf("a", "b")) { it } == listOf("a", "b"))
    val ilv = ArogyaConcerns.interleave(listOf(listOf("a1", "a2"), listOf("b1"), listOf("a1", "c1"))) { it }
    check("interleave", ilv == listOf("a1", "b1", "a2", "c1"), ilv.toString())

    GenericKbKeyFacts.corpusWeight = null
    println("ConcernsTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
