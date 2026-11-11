import com.example.arfas.*

// 9 Nov 2026: Ask SOP history (last 10 questions kept on the phone).
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    fun item(n: Int, q: String = "question $n") = ArogyaSopHistory.Item(n.toLong(), q, q, "answer $n", "Gemma 3n", 1000L * n, n % 2 == 0,
        quote = "guide sentence $n", quoteSource = "Module 6, p.$n", tier = 1,
        sources = listOf(ArogyaSource("s$n", "ASHA Module 6", "page $n", "passage text $n", "https://nhm.gov.in/m6.pdf")))
    var h = emptyList<ArogyaSopHistory.Item>()
    for (n in 1..12) h = ArogyaSopHistory.add(h, item(n))
    check("keeps-last-10", h.size == 10 && h.first().id == 3L && h.last().id == 12L, h.map { it.id }.toString())
    h = ArogyaSopHistory.add(h, item(13, "Question 5 "))
    h = ArogyaSopHistory.add(h, item(14, "question 7"))
    check("same-question-replaced", h.count { it.question.trim().equals("question 7", true) } == 1 && h.last().id == 14L)
    val back = ArogyaSopHistory.decode(ArogyaSopHistory.encode(h))
    check("roundtrip", back == h, "${back.size} vs ${h.size}")
    check("roundtrip-sources", back.last().sources.first().url == "https://nhm.gov.in/m6.pdf" && back.last().quoteSource == "Module 6, p.14")
    check("hindi-roundtrip", ArogyaSopHistory.decode(ArogyaSopHistory.encode(listOf(item(1, "बच्चे को दस्त है")))).first().question == "बच्चे को दस्त है")
    check("bad-json-empty", ArogyaSopHistory.decode("{not json").isEmpty())
    check("null-empty", ArogyaSopHistory.decode(null).isEmpty())
    check("long-excerpt-trimmed", ArogyaSopHistory.decode(ArogyaSopHistory.encode(listOf(item(1).copy(sources = listOf(ArogyaSource("a", "t", "l", "x".repeat(5000), "u")))))).first().sources.first().excerpt.length == 1500)
    println("SopHistoryTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
