import com.example.arfas.*
private var pass = 0; private var fail = 0
private fun check(n: String, ok: Boolean, d: String = "") { if (ok) { pass++; println("PASS $n") } else { fail++; println("FAIL $n $d") } }
fun main() {
    check("3n-ok", ArogyaSetupPlan.gemmaFileNameOk("gemma-3n-E2B-it-int4.litertlm"))
    check("gemma4-ok", ArogyaSetupPlan.gemmaFileNameOk("gemma-4-E2B-it.litertlm"))
    check("other-bundle-ok", ArogyaSetupPlan.gemmaFileNameOk("my-model.LITERTLM"))
    check("zip-rejected", !ArogyaSetupPlan.gemmaFileNameOk("Pramaan_Meta_Models.zip"))
    check("gguf-rejected", !ArogyaSetupPlan.gemmaFileNameOk("llama.gguf"))
    check("3n-min-2gb", ArogyaSetupPlan.bundleMinBytes("gemma-3n-E2B-it-int4.litertlm") == ArogyaSetupPlan.GEMMA_BYTES)
    check("other-min-500mb", ArogyaSetupPlan.bundleMinBytes("gemma-4-E2B-it.litertlm") == 500_000_000L)
    check("store-name-safe", ArogyaSetupPlan.bundleStoreName("gemma 4 (1).litertlm") == "gemma_4__1_.litertlm")
    check("classify-gemma4", ArogyaSetupMatch.classify("gemma-4-E2B-it.litertlm") == ArogyaSetupMatch.Kind.GEMMA)
    check("classify-3n", ArogyaSetupMatch.classify("gemma-3n-E2B-it-int4.litertlm") == ArogyaSetupMatch.Kind.GEMMA)
    println("BbTest: $pass passed, $fail failed"); if (fail > 0) kotlin.system.exitProcess(1)
}
