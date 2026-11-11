import com.example.arfas.*

// 11 Nov 2026 (AJ): the streaming loop guard also stops loops of long sentences (phone 23:27).
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

private fun feedAll(text: String, piece: Int = 5): AndroGenerationLoopGuard {
    val g = AndroGenerationLoopGuard()
    var i = 0
    while (i < text.length && !g.isLooping) { g.feed(text.substring(i, minOf(text.length, i + piece))); i += piece }
    return g
}

fun main() {
    val s = "ASHAs should motivate every pregnant woman in their area to go to an appropriate health facility for delivery since labour complications may suddenly occur even if pregnancy was normal. "
    check("long-sentence-loop-stopped", feedAll("Answer: Institutional delivery is safest. " + s.repeat(5)).isLooping)
    check("long-loop-stopped-early", run { val g = AndroGenerationLoopGuard(); var n = 0; val t = s.repeat(10); var i = 0; while (i < t.length && !g.isLooping) { g.feed(t.substring(i, minOf(t.length, i + 5))); i += 5; n = i }; g.isLooping && n <= s.length * 4 })
    val normal = "Answer: Give ORS after every loose stool. Why it matters: it replaces lost water and salts. What to do: 1. Mix one packet of ORS in one litre of clean drinking water. 2. Give small sips often. 3. Continue breastfeeding. 4. Give zinc for 14 days. When to tell the ANM: if the child cannot drink, has blood in stool, or is very sleepy. Common mistakes to avoid: do not stop feeding; do not give plain water only. " +
        "The mother should keep giving the ORS solution after each loose stool, and she should bring the child back at once if the child becomes worse or does not drink."
    check("normal-answer-not-stopped", !feedAll(normal).isLooping)
    val list = (1..12).joinToString(" ") { "$it. Check the child for danger sign number $it and refer if present." }
    check("numbered-list-not-stopped", !feedAll(list).isLooping)
    check("short-phrase-loop-still-stopped", feedAll("Refer now. ".repeat(20)).isLooping)
    println("LoopGuardLongTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
