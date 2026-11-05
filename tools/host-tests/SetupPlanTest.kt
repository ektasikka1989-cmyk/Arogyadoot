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
    t(ArogyaSetupPlan.gemmaDownloadComplete(ArogyaSetupPlan.GEMMA_BYTES), "gemma floor accepted")
    t(!ArogyaSetupPlan.gemmaDownloadComplete(ArogyaSetupPlan.GEMMA_BYTES - 1), "gemma truncated rejected")
    t(!ArogyaSetupPlan.gemmaDownloadComplete(0L), "gemma empty rejected")
    t(ArogyaSetupPlan.gemmaFileNameOk("gemma-3n-E2B-it-int4.litertlm"), "gemma exact name accepted")
    t(ArogyaSetupPlan.gemmaFileNameOk("GEMMA-3N-E2B-IT-INT4.LITERTLM"), "gemma name case-insensitive")
    t(!ArogyaSetupPlan.gemmaFileNameOk("gemma-3n-E2B-it-int4-Web.litertlm"), "gemma web variant rejected")
    t(!ArogyaSetupPlan.gemmaFileNameOk("gemma-3n-E2B-it-int4.mediatek.mt6993.litertlm"), "gemma mediatek variant rejected")
    t(!ArogyaSetupPlan.gemmaFileNameOk("gemma-3n-E2B-it-int4.task"), "renamed task file rejected")
    t(!ArogyaSetupPlan.gemmaFileNameOk(""), "gemma blank name rejected")
    t(!ArogyaSetupPlan.gemmaNeedsToken(ArogyaSetupPlan.GEMMA_URL_PRIMARY), "gemma primary needs no token")
    t(ArogyaSetupPlan.gemmaNeedsToken(ArogyaSetupPlan.GEMMA_HF_URL), "gemma official fallback needs token")
    t(ArogyaSetupPlan.GEMMA_HF_URL.endsWith("/" + ArogyaSetupPlan.GEMMA_FILE), "gemma official url serves the exact bundle")
    println(if (tf == 0) "ALL $tp PASS" else "$tf FAILED, $tp passed")
    if (tf > 0) kotlin.system.exitProcess(1)
}
