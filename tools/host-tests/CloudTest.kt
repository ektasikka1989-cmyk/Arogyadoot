import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    check("endpoints", ArogyaModelEndpoints.all.map { it.model } == listOf("qwen/qwen3.8-27b", "muse-spark-1.3", "meta-models/Muse-Glimmer-30B") && ArogyaModelEndpoints.byId("together_glimmer")!!.keyFor == "together")
    check("groq preset", ArogyaModelEndpoints.byId("groq")!!.baseUrl == "https://api.groq.com/openai/v1" && ArogyaModelEndpoints.byId("groq")!!.keyFor == "groq" && !ArogyaModelEndpoints.byId("groq")!!.metaModel)
    check("vision request", org.json.JSONObject(OpenAiCompatibleClient(ArogyaModelEndpoints.byId("groq")!!, { "k" }).requestBody("s", "u", 10, listOf("data:image/jpeg;base64,xx"))).getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url") == "data:image/jpeg;base64,xx")
    check("spark base", ArogyaModelEndpoints.byId("meta")!!.baseUrl == "https://api.meta.ai/v1" && ArogyaModelEndpoints.byId("meta")!!.keyFor == "meta")
    check("glimmer base", ArogyaModelEndpoints.byId("together_glimmer")!!.baseUrl == "https://api.together.xyz/v1")
    check("glimmer request", org.json.JSONObject(OpenAiCompatibleClient(ArogyaModelEndpoints.byId("together_glimmer")!!, { "k" }).requestBody("s", "u", 10)).getString("model") == "meta-models/Muse-Glimmer-30B")
    check("sam count", ArogyaSamCount.count(listOf(0.93f, 0.31f, null)) == 2)
    check("kit phrase", ArogyaKitPhrases.phrase("ORS") == "ORS sachet" && ArogyaKitPhrases.perItem("Zinc tablets") == 10 && ArogyaKitPhrases.perItem("ORS") == 1)
    check("route cloud first", ArogyaRouting.order(true, true) == listOf("cloud", "phone"))
    check("route local first", ArogyaRouting.order(false, true) == listOf("phone"))
    check("route no cloud", ArogyaRouting.order(true, false) == listOf("phone"))
    check("mode offline", ArogyaModeStatus.line(false, true, true, false) == ("Offline · everything runs on this phone" to true))
    check("mode online ok", ArogyaModeStatus.line(true, true, true, false) == ("Online · Groq key ✓ · internet ✓" to true))
    check("mode no internet", ArogyaModeStatus.line(true, false, true, false).first.contains("no internet") && !ArogyaModeStatus.line(true, false, true, false).second)
    check("mode no key", ArogyaModeStatus.line(true, true, false, false).first.contains("tap to add") && !ArogyaModeStatus.line(true, true, false, false).second)
    check("mode hindi", ArogyaModeStatus.line(false, false, false, true).first.contains("ऑफ़लाइन"))
    println("Cloud: ${n - fails}/$n passed")
    if (fails > 0) kotlin.system.exitProcess(1)
}
