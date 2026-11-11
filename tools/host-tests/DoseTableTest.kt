import com.example.arfas.*

// 9 Nov 2026: guide dose table (ArogyaDoseTable). Answers are written the way the answer model writes them.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}
private fun f(q: String, a: String) = ArogyaDoseTable.check(q, a)

fun main() {
    // Amount reading
    check("amt-mg", ArogyaDoseTable.amountsIn("give zinc 20 mg daily") == setOf("mg:20"))
    check("amt-half-tab", "tab:1/2" in ArogyaDoseTable.amountsIn("give half a zinc tablet"))
    check("amt-quarter", "tab:1/4" in ArogyaDoseTable.amountsIn("¼ tablet of paracetamol"))
    check("amt-one-tab", "tab:1" in ArogyaDoseTable.amountsIn("one zinc tablet daily") && "tab:1/2" !in ArogyaDoseTable.amountsIn("one zinc tablet daily"))
    check("amt-half-of-one-not-one", "tab:1" !in ArogyaDoseTable.amountsIn("half of one tablet"), ArogyaDoseTable.amountsIn("half of one tablet").toString())
    check("amt-tsp", "ml:2.5" in ArogyaDoseTable.amountsIn("½ teaspoon of syrup"))
    check("amt-lakh", "iu:200000" in ArogyaDoseTable.amountsIn("2 lakh IU vitamin A"))
    check("amt-hindi", ArogyaDoseTable.amountsIn("२० मि.ग्रा. जिंक की एक गोली").containsAll(setOf("mg:20", "tab:1")), ArogyaDoseTable.amountsIn("२० मि.ग्रा. जिंक की एक गोली").toString())
    check("amt-hindi-half", "tab:1/2" in ArogyaDoseTable.amountsIn("जिंक की आधी गोली"))
    check("amt-comma-decimal", "ml:2.5" in ArogyaDoseTable.amountsIn("2,5 ml syrup"))

    // Ages
    check("age-months", ArogyaDoseTable.patient("he is 12 months old").first == 360L)
    check("age-years", ArogyaDoseTable.patient("Child is 2 years old with cough").first == 720L)
    check("age-hindi", ArogyaDoseTable.patient("4 महीने के बच्चे को दस्त").first == 120L)
    check("age-pregnant", ArogyaDoseTable.patient("pregnant woman IFA").second == "pregnant")
    check("age-visit", ArogyaDoseTable.patient("how much zinc?", "7 days old").first == 7L)

    val q12 = "Baby having loose motion and high fever, he is 12 months old, what should I do?"
    val q4 = "What is the dose of zinc for a 4 month old baby with diarrhoea?"
    // Zinc
    check("zinc-12m-correct", f(q12, "What to do:\n1. Give ORS after every loose stool.\n2. Give one zinc tablet (20 mg) daily for 14 days.") == null)
    check("zinc-12m-wrong-10mg", f(q12, "2. Give zinc 10 mg daily for 14 days.")?.row?.doseEn?.startsWith("20 mg") == true)
    check("zinc-4m-wrong-20mg", f(q4, "Answer: Give one full zinc tablet of 20 mg daily for 14 days.")?.reason == "amount")
    check("zinc-4m-correct-half", f(q4, "Give half a zinc tablet (10 mg) daily for 14 days, dissolved in breast milk.") == null)
    check("zinc-4m-hindi-wrong", f("4 महीने के बच्चे को जिंक की खुराक कितनी दें?", "रोज़ २० mg जिंक की एक गोली 14 दिन तक दें।") != null)
    check("zinc-6m-boundary", f("6 month old baby has diarrhoea", "Give zinc 20 mg daily for 14 days.") == null)
    check("zinc-no-age-no-check", f("What is the zinc dose for diarrhoea?", "Give zinc 20 mg daily.") == null)
    check("zinc-under-2m-not-in-table", f("1 month old baby has diarrhoea", "Give zinc 10 mg.") == null)

    // Paracetamol
    check("pcm-tab-2y-correct", f("Child is 2 years old with fever", "Give ¼ tablet of paracetamol 500 mg, up to 4 times a day.") == null)
    check("pcm-tab-2y-wrong-full", f("Child is 2 years old with fever", "Give one paracetamol tablet (500 mg) every 6 hours.") != null)
    check("pcm-syrup-8m-correct", f("8 month old baby has fever", "Give paracetamol syrup 2.5 ml (½ teaspoon).") == null)
    check("pcm-syrup-8m-wrong", f("8 month old baby has fever", "Give paracetamol syrup 5 ml.") != null)
    val nb = f("7 days old newborn has fever", "Give paracetamol syrup 2.5 ml.")
    check("pcm-syrup-newborn-conflict-noted", nb != null && "1.25" in ArogyaDoseTable.caution(nb, false), nb?.let { ArogyaDoseTable.caution(it, false) } ?: "null")
    check("pcm-syrup-newborn-either-guide-ok", f("7 days old newborn has fever", "Give paracetamol syrup 1.25 ml.") == null)

    // Albendazole
    check("alb-under-1y-not-given", f("10 month old baby, can I give albendazole?", "Give albendazole 200 mg once.")?.reason == "not_given")
    check("alb-under-1y-negated-ok", f("10 month old baby, can I give albendazole?", "Do not give albendazole to a baby under 1 year.") == null)
    check("alb-18m-correct", f("18 month old child deworming", "Give albendazole 200 mg (half tablet) once.") == null)
    check("alb-18m-wrong", f("18 month old child deworming", "Give albendazole 400 mg (one tablet).") != null)
    check("alb-3y-correct", f("Child is 3 years old, deworming dose?", "Give one albendazole tablet of 400 mg.") == null)

    // Vitamin A
    check("vita-9m-correct", f("9 month old baby vitamin A dose", "Give vitamin A 1 ml (1 lakh IU).") == null)
    check("vita-2y-wrong", f("Child is 2 years old, vitamin A dose?", "Give vitamin A 1 lakh IU.") != null)

    // IFA
    check("ifa-pregnant-correct", f("How many IFA tablets should a pregnant woman take?", "Give one red IFA tablet (60 mg iron) daily for 180 days.") == null)
    check("ifa-pregnant-old-100mg", f("How many IFA tablets should a pregnant woman take?", "Give IFA 100 mg daily.") != null)
    check("ifa-7y-wrong-60", f("Child is 7 years old, IFA dose?", "Give one IFA tablet of 60 mg weekly.") != null)
    check("ifa-7y-correct-45", f("Child is 7 years old, IFA dose?", "Give one pink IFA tablet (45 mg iron) once a week.") == null)

    // Two medicines in one line: amounts go to the medicine they follow.
    check("two-meds-one-line", f(q12, "Give ORS after each stool and zinc 20 mg daily; give paracetamol syrup 5 ml if fever is high.") == null)
    check("two-meds-wrong-second", f(q12, "Give zinc 20 mg daily and paracetamol syrup 2.5 ml.") != null)

    // Caution text
    val c = f(q4, "Give 20 mg zinc daily.")!!
    val en = ArogyaDoseTable.caution(c, false)
    check("caution-en", "10 mg" in en && "p23" in en && "ANM" in en && "20 mg" in en, en)
    check("caution-hi", "ANM" in ArogyaDoseTable.caution(c, true))
    // Nothing to check
    check("no-medicine", f(q12, "Keep the baby warm and continue breastfeeding.") == null)
    check("definition-no-age", f("What is ORS?", "ORS is oral rehydration solution.") == null)

    println("DoseTableTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
