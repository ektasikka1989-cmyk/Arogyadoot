import com.example.arfas.*
var fails = 0; var n = 0
fun check(name: String, ok: Boolean) { n++; if (!ok) { fails++; println("FAIL $name") } }
fun main() {
    // Part B2: procedures/lists get room for every step/item (800); facts 600. Hindi x2.5 via ArogyaBudget.
    check("r11-proc", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.PROCEDURE) == 1600)
    check("r11-list", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.LIST) == 1600)
    check("r11-fact", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.FACT) == 1200)
    check("r11-urgent", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.Kind.URGENT) == 1600)
    check("r11-kind", ArogyaQuestionType.ceilingPerPart(ArogyaQuestionType.kindOf("How should a mother prepare ORS at home?")) == 1600)
    // R10/R11 switches default ON.
    check("flags3", ArogyaFlags.r10WideCloud && ArogyaFlags.r11LowReasoning)
    println("batch3: $n checks, $fails failures")
    if (fails > 0) kotlin.system.exitProcess(1)
}
