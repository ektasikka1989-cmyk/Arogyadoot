import com.example.arfas.*

// 9 Nov 2026 (AF): a problem stated without question words gets the action-first shape from the model's restatement.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    // The phone loads the guide's English vocabulary at start (MainActivity); without it "done" is respelled.
    listOf("../../app/src/main/assets/guide/vocab_en.txt", System.getProperty("vocab") ?: "").map { java.io.File(it) }
        .firstOrNull { it.isFile }?.useLines { ArogyaQuestionNormalizer.loadVocabulary(it) } ?: println("NOTE vocab not found")
    val K = ArogyaQuestionType.Kind.URGENT
    val urine = "child has not passed urine for 8 hours and is very weak"
    check("statement-alone-fact", ArogyaQuestionType.kindOf(urine) == ArogyaQuestionType.Kind.FACT)
    check("statement-with-rewrite-urgent", ArogyaQuestionType.kindFor(urine, "What should be done if a child hasn't passed urine for 8 hours and is weak?") == K)
    check("hindi-statement-urgent", ArogyaQuestionType.kindFor("किशोरी को माहवारी में बहुत दर्द होता है", "What should be done when an adolescent girl has severe menstrual pain?") == K)
    check("procedure-from-rewrite", ArogyaQuestionType.kindFor("ors", "How is ORS prepared at home?") == ArogyaQuestionType.Kind.PROCEDURE)
    // The question's own type wins when it has one.
    check("procedure-kept", ArogyaQuestionType.kindFor("ORS kaise banaye", "What is ORS?") == ArogyaQuestionType.Kind.PROCEDURE)
    check("list-kept", ArogyaQuestionType.kindFor("what are the danger signs in a newborn", "What should be done when a newborn is sick?") == ArogyaQuestionType.Kind.LIST)
    // Dose and "what is" questions stay short facts.
    check("dose-stays-fact", ArogyaQuestionType.kindFor("zinc dose for 4 month baby", "What is the zinc dose for a young infant?") == ArogyaQuestionType.Kind.FACT)
    check("whatis-stays-fact", ArogyaQuestionType.kindFor("what is colostrum", "What is colostrum?") == ArogyaQuestionType.Kind.FACT)
    check("no-rewrite-unchanged", ArogyaQuestionType.kindFor(urine, "") == ArogyaQuestionType.Kind.FACT)
    check("benefits-not-urgent", ArogyaQuestionType.kindOf("What are the benefits of using sanitary pads") != K)
    check("fits-still-urgent", ArogyaQuestionType.kindOf("child has fits with fever") == K)
    check("asha-do-rewrite-urgent", ArogyaQuestionType.kindFor("mother has fever and foul smelling discharge after delivery", "What should an ASHA do when a woman has high fever and foul smelling discharge after delivery?") == K)
    check("dose-rewrite-not-urgent", ArogyaQuestionType.kindFor("zinc for 4 month baby", "What is the zinc dose for a young infant?") == ArogyaQuestionType.Kind.FACT)
    val P = ArogyaQuestionType.Kind.PROCEDURE
    check("recipe-and-amount-procedure", ArogyaQuestionType.kindFor("How do I make ORS at home and how much to give a 1 year old after each stool", "") == P)
    check("prepare-and-times-procedure", ArogyaQuestionType.kindFor("How to prepare energy dense food for a malnourished 1 year old and how many times to feed", "") == P)
    check("hinglish-kitna-kaise-procedure", ArogyaQuestionType.kindFor("ORS kitne paani me kaise banaye", "") == P)
    check("amount-only-stays-fact", ArogyaQuestionType.kindFor("how much ORS to give after each loose stool", "") == ArogyaQuestionType.Kind.FACT)
    check("dose-days-stays-fact", ArogyaQuestionType.kindFor("zinc and ORS dose for 8 month old with diarrhoea for how many days", "") == ArogyaQuestionType.Kind.FACT)
    println("QuestionKindTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
