import com.example.arfas.*
import kotlinx.coroutines.runBlocking

class FakeCloud(private val reply: String) : PocketRagCloudResolver(), PocketRagCloudAnswerer {
    var calls = 0
    override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, localDraft: String?, reason: String, maxOutputTokens: Int): String? {
        calls++; didStream = true; lastDeltaMs = System.currentTimeMillis(); return reply
    }
}
class FakeLocal(private val reply: String) : PocketRagLocalAnswerer {
    override suspend fun generate(packet: PocketRagPacket, memory: List<PocketRagTurnMemory>, maxOutputTokens: Int, recoveryMode: Boolean): String? = reply
}

fun main() = runBlocking {
    val sel = PocketRagSelection("generic", "asha", "ASHA guide")
    val blocks = listOf(
        PocketRagEvidenceBlock("E1", "S1", "Danger signs in a newborn: difficulty in breathing or chest indrawing; fast breathing (more than 60 breaths per minute); convulsions; lethargic or unconscious; baby feels cold or has fever; yellow palms and/or soles; pus from eyes or umbilicus. Refer the baby immediately to the nearest health facility."),
        PocketRagEvidenceBlock("E2", "S2", "If a baby is sick, refer to the nearest health facility. If the baby is unable to suckle, give expressed breast milk by cup. Keep the baby warm and continue breastfeeding. Do not cover with a blanket when the baby has fever."),
        PocketRagEvidenceBlock("E3", "S3", "Inform parents that under Janani Shishu Suraksha Karyakram free treatment and transport is provided. Recognising danger signs early saves the life of the newborn."))
    val sources = listOf(PocketRagSource("S1", "S1 :: Home Based Newborn Care — page 62"), PocketRagSource("S2", "S2 :: ASHA Module 6 — page 18"), PocketRagSource("S3", "S3 :: Integrated ASHA Training Module Part B — page 55"))
    val packet = PocketRagPacket(sel, "What are the danger signs in a newborn?", blocks, sources, coverageComplete = true, bestRelevance = 6.5f)
    val localAnswer = "Answer: Danger signs include fast breathing and convulsions [S1]."
    val good = "Answer: Danger signs in a newborn include difficulty in breathing or chest indrawing, fast breathing (more than 60 breaths per minute), convulsions, being lethargic or unconscious, feeling cold or having fever, yellow palms and/or soles, and pus from the eyes or umbilicus [S1].\n" +
        "Why it matters: Recognising these signs early saves the life of the newborn [S3].\n" +
        "What to do:\n1. Refer the baby immediately to the nearest health facility [S1].\n2. If the baby cannot suckle, give expressed breast milk by cup [S2].\n3. Keep the baby warm and continue breastfeeding [S2].\n" +
        "When to tell the ANM: Tell the ANM at once if any one of these signs is present [S1].\n" +
        "Common mistakes to avoid: Do not cover the baby with a blanket when the baby has fever [S2]. Free treatment and transport are provided under JSSK [S3]."
    val badNumber = good.replace("more than 60 breaths", "more than 70 breaths")
    val flipped = good.replace("Keep the baby warm and continue breastfeeding [S2].", "Keep the baby warm, cover with a blanket and continue breastfeeding [S2].")

    suspend fun run(name: String, reply: String) {
        val cloud = FakeCloud(reply)
        val engine = PocketRagEngine(FakeLocal(localAnswer), cloud, PocketRagQualityGate(), PocketRagSessionStore(), canUseCloud = { true },
            contextWindowTokens = { 32_000 }, callerTimeoutEnabled = false)
        val r = try { engine.answer(packet, PocketRagEngine.CloudMode.VERIFY, localDraftToVerify = localAnswer) }
                catch (t: Throwable) { println("== $name: EXCEPTION ${t.javaClass.simpleName}: ${t.message}"); t.printStackTrace(System.out); return }
        val usedOnline = r.provider == AndroBaselineRuntime.Provider.CLOUD
        println("== $name: provider=${r.provider} online=${usedOnline} accepted=${r.accepted} reason='${r.escalationReason}' outcome='${PocketRagEngine.lastCloudOutcome}' chars=${r.text.length}")
        println("   text: " + r.text.replace("\n", " | ").take(420))
    }
    run("1 clean online answer", good)
    run("2 one wrong number (70)", badNumber)
    run("3 flipped do-not", flipped)
    ArogyaFlags.checkOnlineChecks = false
    run("4 clean, checks OFF (last night's behaviour)", good)
}
