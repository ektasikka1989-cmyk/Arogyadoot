import com.example.arfas.*
import java.io.File
import kotlin.random.Random

// 9 Nov 2026: Ask SOP example questions rotate (tested questions from assets/sop/example_questions.txt).
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    val parsed = ArogyaSopExamples.parse("# comment\n\nHow do I make ORS?\nhow do i make ors?\nघर पर ओआरएस कैसे बनाएं?\n" + "x".repeat(200) + "\nzinc dose\n")
    check("parse-skips-comments-dupes-long", parsed == listOf("How do I make ORS?", "घर पर ओआरएस कैसे बनाएं?", "zinc dose"), parsed.toString())

    val pool = listOf("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "क1", "क2")
    val p1 = ArogyaSopExamples.pick(pool, random = Random(1))
    check("four-distinct", p1.size == 4 && p1.toSet().size == 4, p1.toString())
    check("both-scripts", p1.any { it.startsWith("क") } && p1.any { it.startsWith("a") }, p1.toString())
    val p2 = ArogyaSopExamples.pick(pool, avoid = p1, random = Random(2))
    check("next-avoids-last", p2.none { it in p1 }, "$p1 $p2")
    val seen = (1..30).flatMap { ArogyaSopExamples.pick(pool, random = Random(it)) }.toSet()
    check("rotates-through-pool", seen.size >= 9, seen.toString())
    check("small-pool-still-four", ArogyaSopExamples.pick(listOf("a", "b", "c", "d", "e"), avoid = listOf("a", "b", "c")).size == 4)
    check("empty-pool", ArogyaSopExamples.pick(emptyList()).isEmpty())

    // The shipped pool: tested questions, Hindi and Latin script, every line fits a card.
    val asset = File("../../app/src/main/assets/sop/example_questions.txt")
    if (asset.exists()) {
        val text = asset.readText()
        val shipped = ArogyaSopExamples.parse(text)
        check("asset-size", shipped.size >= 100, "${shipped.size}")
        check("asset-nothing-dropped", shipped.size == text.lines().count { it.isNotBlank() }, "${shipped.size}")
        check("asset-has-hindi", shipped.count { q -> q.any { it in 'ऀ'..'ॿ' } } >= 10)
    } else println("SKIP asset checks (run from tools/host-tests)")

    println("SopExamplesTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
