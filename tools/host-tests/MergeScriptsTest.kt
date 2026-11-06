import com.example.arfas.ArogyaTableOcr
import com.example.arfas.ArogyaTableOcr.Line

var mf = 0; var mp = 0
fun m(c: Boolean, msg: String) { if (c) { mp++; println("PASS $msg") } else { mf++; println("FAIL $msg") } }

fun main() {
    val latin = listOf(Line("BCG", 10, 10, 60, 30), Line("12/01/2026", 200, 10, 320, 30))
    // A Hindi name the Latin reader missed (no overlap) + the Devanagari reader's copy of "BCG" (overlaps).
    val hindi = listOf(Line("सीता देवी", 10, 60, 120, 80), Line("बीसीजी", 12, 11, 58, 29), Line("ा", 400, 10, 405, 30))
    val merged = ArogyaTableOcr.mergeScripts(latin, hindi)
    m(merged.any { it.text == "सीता देवी" }, "missed Hindi line is added")
    m(merged.none { it.text == "बीसीजी" }, "Hindi copy of an English line is not duplicated")
    m(merged.none { it.text == "ा" }, "one-letter Devanagari mark is ignored")
    m(merged.take(2) == latin, "English lines kept first and unchanged")
    m(ArogyaTableOcr.mergeScripts(latin, emptyList()) == latin, "no Hindi lines -> unchanged")
    m(ArogyaTableOcr.rowText(merged).contains("सीता देवी"), "rowText keeps the Hindi line")
    println(if (mf == 0) "ALL $mp PASS" else "$mf FAILED, $mp passed")
    if (mf > 0) kotlin.system.exitProcess(1)
}
