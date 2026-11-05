import com.example.arfas.ArogyaSetupPlan
var tf = 0; var tp = 0
fun t(c: Boolean, m: String) { if (c) { tp++; println("PASS $m") } else { tf++; println("FAIL $m") } }
fun main() {
    t(ArogyaSetupPlan.downloadComplete(ArogyaSetupPlan.MODELS_BYTES), "exact published size accepted")
    t(ArogyaSetupPlan.downloadComplete(3_263_805_025L), "real Hugging Face file size accepted")
    t(ArogyaSetupPlan.downloadComplete(ArogyaSetupPlan.MODELS_BYTES + 1_000_000L), "larger future file accepted")
    t(!ArogyaSetupPlan.downloadComplete(0L), "empty file rejected")
    t(!ArogyaSetupPlan.downloadComplete(ArogyaSetupPlan.MODELS_BYTES - 1), "truncated download rejected")
    t(!ArogyaSetupPlan.downloadComplete(-1L), "unknown size rejected")
    println(if (tf == 0) "ALL $tp PASS" else "$tf FAILED, $tp passed")
    if (tf > 0) kotlin.system.exitProcess(1)
}
