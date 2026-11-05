package com.example.arfas

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Restoration checks: no registry writes; settings restored even after failure. */
@RunWith(AndroidJUnit4::class)
class RestorationTest {
    @Test fun diagnoseRegisterReply(): Unit = runBlocking {
        val c = ArogyaSelfTest.ctx()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a -> a.setContentView(android.widget.TextView(a).apply { text = "ArogyaDoot: local photo diagnosis" }) }
            val graph = FixSightRuntimeGraph.get(c)
            assertTrue(graph.ensureLocalModelReady())
            val prompt = java.io.File(c.getExternalFilesDir(null), "register_test_ocr.txt").readText()
            val raw = graph.runtime.localModelManager!!.generateDetached(prompt, 2200, "photo_diagnosis")
            java.io.File(c.getExternalFilesDir(null), "register_test_raw.txt").writeText(raw.orEmpty())
        }
    }
    @Test fun offlineRegisterInterpretation(): Unit = runBlocking {
        val c = ArogyaSelfTest.ctx()
        val previous = ArogyaSelfTest.policy(c)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { a -> a.setContentView(android.widget.TextView(a).apply { text = "ArogyaDoot: offline register test" }) }
            ArogyaSelfTest.setPolicy(c, AnswerPolicy.LOCAL_FIRST)
            val image = java.io.File(c.getExternalFilesDir(null), "register_test.jpg")
            val bmp = android.graphics.BitmapFactory.decodeFile(image.absolutePath)
            assertNotNull("Test register must exist", bmp)
            val processor = MLKitProcessor()
            val lines = try {
                processor.recognizeText(bmp) // Same OCR call order as the photo import UI.
                val original = processor.recognizeLines(bmp)
                val enlarged = android.graphics.Bitmap.createScaledBitmap(bmp, bmp.width * 2, bmp.height * 2, true)
                try {
                    java.io.File(c.getExternalFilesDir(null), "register_test_ocr_scaled.txt").writeText(
                        ArogyaPersonPhoto.prompt(processor.recognizeLines(enlarged)))
                } finally { enlarged.recycle() }
                original
            } finally { processor.close(); bmp.recycle() }
            val ai = ArogyaSelfTest.newAi(c)
            java.io.File(c.getExternalFilesDir(null), "register_test_ocr.txt").writeText(ArogyaPersonPhoto.prompt(lines))
            val (people, label, ms) = ai.interpretPersonPhoto(lines)
            java.io.File(c.getExternalFilesDir(null), "register_test_result.json").writeText(
                org.json.JSONObject().put("people", org.json.JSONArray(people.map { org.json.JSONObject(it) }))
                    .put("model", label).put("ms", ms).put("ocr", ArogyaPersonPhoto.prompt(lines)).toString(2))
            assertEquals("All eight people must be retained", 8, people.size)
            assertTrue("Local model must interpret the page", label.startsWith("On-phone"))
            assertEquals("First relative separated from name", "Sanjay Kumar", people[0]["relative"])
            assertEquals("First age", "22", people[0]["age"])
            assertEquals("First phone", "9000000302", people[0]["phone"])
            assertTrue("Child's blank phone must stay blank", people[1]["phone"].isNullOrBlank())
            val expected = listOf(
                listOf("Pooja Kumari", "Sanjay Kumar", "22", "F", "Eligible couple", "H-052", "9000000302"),
                listOf("Aarav Kumar", "Pooja Kumari", "2", "M", "Child", "H-052", ""),
                listOf("Meena Singh", "Raju Singh", "31", "F", "Mother", "H-053", "9000000304"),
                listOf("Anjali Verma", "Deepak Verma", "26", "F", "Pregnant", "H-054", "9000000305"),
                listOf("Sushila Devi", "Ram Prasad", "58", "F", "Other", "H-055", "9000000306"),
                listOf("Geeta Rani", "Ram Prasad", "16", "F", "Other", "H-055", ""),
                listOf("Lata Kumari", "Vijay Pal", "29", "F", "Eligible couple", "H-056", "9000000308"),
                listOf("Rahul Pal", "Lata Kumari", "4", "M", "Child", "H-056", ""))
            val keys = listOf("name", "relative", "age", "sex", "category", "household", "phone")
            expected.forEachIndexed { i, row ->
                keys.forEachIndexed { j, key -> assertEquals("Person ${i + 1}: $key", row[j], people[i][key].orEmpty()) }
                listOf("dob", "lmp", "anc", "deliverydate", "weight").forEach { key ->
                    assertTrue("Absent $key must stay blank", people[i][key].isNullOrBlank())
                }
            }
            ArogyaSelfTest.mark("REGISTER_INTERPRETATION_OK people=${people.size} ms=$ms")
        } finally { ArogyaSelfTest.setPolicy(c, previous); scenario.close() }
    }

    @Test fun importReviewVillageDropdownComposes() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(ComposeView(activity).apply {
                    setContent {
                        MaterialTheme {
                            PatientFormFields(false, Beneficiary(name = "Fictional Test", village = "Test village"), "",
                                {}, {}, listOf("Test village")) { }
                        }
                    }
                })
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    @Test fun registerPhotoOcrKeepsColumns(): Unit = runBlocking {
        val bitmap = android.graphics.Bitmap.createBitmap(1600, 400, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK; textSize = 40f
        }
        listOf("Name" to 100f, "Age" to 650f, "Phone" to 900f).forEach { (text, x) ->
            canvas.drawText(text, x, 100f, paint)
        }
        listOf("Meera Devi" to 100f, "23" to 650f, "9000000301" to 900f).forEach { (text, x) ->
            canvas.drawText(text, x, 200f, paint)
        }
        val processor = MLKitProcessor()
        try {
            val rows = ArogyaTableOcr.people(processor.recognizeLines(bitmap))
            assertEquals("One fictional person on the photo", 1, rows.size)
            assertEquals("Meera Devi", rows[0]["name"])
            assertEquals("23", rows[0]["age"])
            assertEquals("9000000301", rows[0]["phone"])
            ArogyaSelfTest.mark("RESTORE_REGISTER_OCR_OK")
        } finally { processor.close(); bitmap.recycle() }
    }

    @Test fun localLlamaAndVisitRouting(): Unit = runBlocking {
        val context = ArogyaSelfTest.ctx()
        val graph = FixSightRuntimeGraph.get(context)
        val manager = graph.runtime.modelFileManager
        val oldMode = manager.getForceModelMode()
        val oldPolicy = ArogyaSelfTest.policy(context)
        try {
            manager.setForceModelMode(ForceModelMode.LLAMA_ONLY)
            ArogyaSelfTest.setPolicy(context, AnswerPolicy.LOCAL_FIRST)
            assertTrue("Selected Llama must load", graph.ensureLocalModelReady())
            val model = graph.runtime.localModelManager!!
            assertTrue("Loaded model must be GGUF", model.loadedModelFileName.orEmpty().endsWith(".gguf", true))
            val reply = model.generateDetached("Reply with the single word ArogyaDoot.", 24, "restoration_test")
            assertFalse("Llama must generate text", reply.isNullOrBlank())
            ArogyaSelfTest.mark("RESTORE_LLAMA_OK chars=${reply.orEmpty().length}")
            assertFalse("Local First must disable legacy online voice", AnswerPolicyStore(context).onlineVoice())
            assertFalse("Local First must disable legacy online backup", AnswerPolicyStore(context).onlineBackup())
            assertFalse("Local First must not upload incomplete OCR", ArogyaPhotoVision.wanted(context, false))
            val ai = ArogyaSelfTest.newAi(context)
            val (values, result) = ai.fillForm(Beneficiary(name = "Fictional Test", category = BenCategory.CHILD),
                ArogyaOfficialForms.ANNEX9, 2, "Baby weight 2.9 kg. Temperature 36.8 C. Baby feeds well.")
            assertTrue("Grounded weight must be proposed", values["weight"]?.value == "2.9")
            assertFalse("Visit must remain offline", result.metaModel)
            ArogyaSelfTest.mark("RESTORE_VISIT_OK fields=${values.size} model=${result.modelLabel}")
            val answer = ai.run(ArogyaAiRequest(ArogyaAiTask.EXPLAIN,
                ArogyaVisit(name = "Fictional Test", kind = ArogyaKind.FOLLOW_UP),
                question = "How should a mother prepare ORS at home for a child with diarrhoea?", afterSafetyPrompt = true))
            assertFalse("Ask SOP must remain offline", answer.online)
            assertTrue("Ask SOP must retain citations", answer.sources.isNotEmpty())
            ArogyaSelfTest.mark("RESTORE_SOP_OK sources=${answer.sources.size} provider=${answer.provider}")
        } finally {
            manager.setForceModelMode(oldMode)
            ArogyaSelfTest.setPolicy(context, oldPolicy)
        }
    }
}

