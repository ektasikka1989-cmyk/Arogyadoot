import com.example.arfas.ArogyaMedScan
import com.example.arfas.ArogyaRegistryLogic
import java.time.LocalDate

var sf = 0; var sp = 0
fun s(c: Boolean, msg: String) { if (c) { sp++; println("PASS $msg") } else { sf++; println("FAIL $msg") } }

fun main() {
    val day = LocalDate.of(2026, 11, 6)
    // Expiry warning on receive
    s(ArogyaMedScan.expiryWarning("2026-10-31", day) == "expired", "past date → expired")
    s(ArogyaMedScan.expiryWarning("10/2026", day) == "expired", "MM/yyyy normalised → expired")
    s(ArogyaMedScan.expiryWarning("2026-12-31", day) == "soon", "within 90 days → soon")
    s(ArogyaMedScan.expiryWarning("AUG 2027", day) == null, "far expiry → no warning")
    s(ArogyaMedScan.expiryWarning("", day) == null && ArogyaMedScan.expiryWarning("next year", day) == null, "blank / unreadable → no warning")
    s(ArogyaMedScan.daysToExpiry("2026-11-16", day) == 10L, "days left counted")

    // Kit matching
    val kit = ArogyaRegistryLogic.DRUG_KIT
    fun m(name: String) = ArogyaMedScan.matchItem(name, kit)
    s(m("Crocin Syrup 60 ml") == "Paracetamol", "Crocin syrup is paracetamol, not IFA syrup: ${m("Crocin Syrup 60 ml")}")
    s(m("Ferrous Sulphate & Folic Acid Syrup") == "IFA syrup (child)", "iron syrup → IFA syrup")
    s(m("IRON & FOLIC ACID TABLETS") == "IFA tablets (adult)", "IFA tablets unchanged")
    s(m("Electral powder") == "ORS", "Electral → ORS")
    s(m("Zentel 400") == "Albendazole", "Zentel → Albendazole")
    s(m("Septran DS") == "Cotrimoxazole", "Septran → Cotrimoxazole")
    s(m("i-pill") == "Emergency contraceptive pill", "i-pill → ECP")
    s(m("पैरासिटामोल गोली") == "Paracetamol", "Hindi paracetamol")
    s(m("Nischay pregnancy kit") == "Pregnancy test kit", "Nischay → pregnancy test kit")
    s(m("Malaria RDT bivalent") == "Rapid diagnostic test (malaria)", "RDT")
    s(m("Gauze roll") == "Cotton / bandage", "gauze → cotton/bandage")
    s(m("Amoxicillin 250") == "", "unknown medicine → not in kit")
    println(if (sf == 0) "ALL $sp PASS" else "$sf FAILED, $sp passed")
    if (sf > 0) kotlin.system.exitProcess(1)
}
