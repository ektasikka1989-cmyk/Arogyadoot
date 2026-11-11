import com.example.arfas.*

// 11 Nov 2026 (AN): a photo of something else gives "nothing found", never a stuck screen or invented details.
private var pass = 0
private var fail = 0
private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) { pass++; println("PASS $name") } else { fail++; println("FAIL $name $detail") }
}

fun main() {
    // Every way the reader says "nothing here".
    for (r in listOf("NO_TEXT", "NO_TEXT.", "no_text", "NO TEXT – this is a photo of a tree.", "No person details are visible in this photo.",
            "There is no text in this image.", "This photo does not show a registration slip.", "  NO_TEXT\n"))
        check("nothing:" + r.trim().take(24), ArogyaPhotoText.clean(r) == null, "${ArogyaPhotoText.clean(r)}")
    // Real transcripts are kept.
    val slip = "Name: Sarita Yadav\nHusband: Ramesh\nAge: 24\nMobile: 9876543210\nVillage: Rampur"
    check("slip-kept", ArogyaPhotoText.clean(slip) == slip)
    check("slip-person", ArogyaPhotoText.people(slip).size == 1)
    val noRow = "No. 12 | BCG | 31/10/2026\nOPV-0 | 31/10/2026"
    check("card-row-starting-No-kept", ArogyaPhotoText.clean(noRow) != null)
    check("mixed-kept", ArogyaPhotoText.clean("No text in the header.\nName: Sita Devi\nAge: 30") != null)
    // Unreadable values only → nothing.
    check("all-unreadable", ArogyaPhotoText.people(ArogyaPhotoText.clean("Name: [unreadable]\nAge: [unreadable]").orEmpty()).isEmpty())
    // A description of an unrelated photo has no person in it.
    check("tree-no-person", ArogyaPhotoText.people("A green tree in a garden with a blue sky.").isEmpty())
    // Medicine: an unrelated photo gives an empty scan (the screen then says "no medicine name, batch or expiry").
    val m = ArogyaMedScan.parse("A green tree in a garden with a blue sky.")
    val catalog = listOf("ORS sachet", "Zinc tablets", "IFA tablets (adult)", "Paracetamol tablets")
    fun notMedicine(s: MedScan) = s.batch.isBlank() && s.expiry.isBlank() && s.qty == 0 && ArogyaMedScan.matchItem(s.name, catalog).isBlank()
    check("tree-no-medicine", notMedicine(m), m.toString())
    val real = ArogyaMedScan.parse("ZINC SULPHATE DISPERSIBLE TABLETS IP 20 mg\nB.No. ZN2401\nEXP. 08/2027\n10 Tablets")
    check("real-label-is-medicine", !notMedicine(real), real.toString())
    check("name-only-known-medicine", !notMedicine(ArogyaMedScan.parse("Paracetamol Tablets IP 500 mg")))
    // A looping transcript is detected as broken.
    check("loop-detected", KbReplyText.degenerateRepetition("Name: Name: Name: Name: ".repeat(30)) != null)
    println("PhotoNothingTest: $pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
