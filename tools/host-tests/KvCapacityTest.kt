import com.example.arfas.*

// 9 Nov 2026 (AD): blank offline answer when the prompt was longer than the phone's real KV cache.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    // The exact error from the phone log (ors_log4, 21:58).
    val err = "LiteRtLmJniException:Status Code: 9. Message: Prefill input length exceeds available state entries (remaining capacity: 4096)."
    check("is-capacity-error", ArogyaKvCapacity.isCapacityError(err))
    check("reported-4096", ArogyaKvCapacity.reported(err) == 4096, "${ArogyaKvCapacity.reported(err)}")
    check("other-error-not-capacity", !ArogyaKvCapacity.isCapacityError("LiteRtLmJniException: out of memory") && !ArogyaKvCapacity.isCapacityError(null))
    check("no-number-null", ArogyaKvCapacity.reported("Prefill input length exceeds available state entries") == null)

    // Budget for 4096 tokens: the assembler's own formula ((4096 - 1050 - 768) x 1.55).
    val b = ArogyaKvCapacity.evidenceChars(4096, 3000)
    check("budget-4096", b == 3530, "$b")
    check("budget-floor", ArogyaKvCapacity.evidenceChars(1024, 768) == 1800)
    // The failing turn: 6 passages, 7699 chars in total, did not fit 4096 tokens.
    val lens = listOf(1333, 982, 1500, 1400, 1250, 1234)
    val k = ArogyaKvCapacity.keepCount(lens, b)
    check("keep-fits", k == 2 && lens.take(k).sum() + 24 * k <= b, "$k")
    check("keep-at-least-one", ArogyaKvCapacity.keepCount(listOf(5000, 100), 1800) == 1)
    check("keep-empty", ArogyaKvCapacity.keepCount(emptyList(), 1800) == 0)
    check("keep-all-when-small", ArogyaKvCapacity.keepCount(listOf(500, 500, 500), 3530) == 3)

    // Token estimate: Devanagari is ~2x denser than Latin text.
    val en = ArogyaKvCapacity.estimateTokens("a".repeat(3000))
    val hi = ArogyaKvCapacity.estimateTokens("क".repeat(1400))
    check("estimate-latin", en in 990..1010, "$en")
    check("estimate-devanagari", hi in 990..1010, "$hi")
    // 6 passages of ~1300 chars English (~433 tokens each) + 1800 fixed tokens in a 4096 cache: 5 fit.
    val toks = List(6) { 433 }
    check("keepWithin-4096", ArogyaKvCapacity.keepWithin(toks, 1800, 4096) == 5, "${ArogyaKvCapacity.keepWithin(toks, 1800, 4096)}")
    check("keepWithin-all", ArogyaKvCapacity.keepWithin(toks, 1000, 8192) == 6)
    check("keepWithin-one", ArogyaKvCapacity.keepWithin(listOf(5000, 100), 1000, 4096) == 1)
    // A Devanagari-heavy packet keeps fewer passages than the same length of English.
    val deva = List(6) { ArogyaKvCapacity.estimateTokens("क".repeat(1300)) }
    check("keepWithin-hindi-fewer", ArogyaKvCapacity.keepWithin(deva, 1800, 4096) < 5)

    println("KvCapacityTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
