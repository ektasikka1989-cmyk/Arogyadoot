package com.example.arfas

/**
 * 9 Nov 2026: age-specific doses of the ASHA drug-kit medicines, copied from the installed guides
 * (ASHA_Health_Guide_v2.db) with document and page, and a check of the dose an answer states against
 * the row for the asked age. Advisory only: a mismatch adds a caution line quoting the guide's dose
 * and page; the answer is never edited or withheld.
 *
 * STATUS: rows transcribed by the developer assistant from the guide text; awaiting ANM/clinician
 * sign-off (docs/DOSE_TABLE_REVIEW.md). Where two guides differ, the row says so and both values are
 * accepted. Weight bands are given only as text — the check uses age. ORS volumes are not checked.
 * Pure Kotlin, host-tested. Gated by ArogyaFlags.doseTable.
 */
object ArogyaDoseTable {
    data class Row(
        val medicine: String,
        /** "tablet", "syrup" or "" (any form). */
        val form: String,
        val minDays: Int,
        val maxDays: Int,
        /** "pregnant" / "postpartum" rows apply to the mother, not by age. */
        val group: String = "",
        val doseEn: String,
        val doseHi: String,
        /** Accepted amounts, normalised: "mg:20", "ml:2.5", "tab:1/2", "iu:100000". */
        val amounts: Set<String>,
        val source: String,
        val notGiven: Boolean = false,
        /** Another guide gives a different amount for the same band (both are accepted). */
        val conflict: String = ""
    )

    /** Ages are in days with 30-day months (as [ArogyaDangerSigns.textAgeDays] counts them): 6 months = 180, 12 months = 1 year = 360. */
    private const val Y = 360

    val ROWS: List<Row> = listOf(
        // Zinc (diarrhoea), 14 days.
        Row("zinc", "", 60, 180 - 1, doseEn = "10 mg (½ tablet of 20 mg) once a day for 14 days", doseHi = "10 mg (20 mg की आधी गोली) दिन में एक बार, 14 दिन",
            amounts = setOf("mg:10", "tab:1/2"), source = "Integrated ASHA Module Part C p23; Part B p133; HBYC Handbook p72"),
        Row("zinc", "", 180, 5 * Y, doseEn = "20 mg (1 tablet) once a day for 14 days", doseHi = "20 mg (1 गोली) दिन में एक बार, 14 दिन",
            amounts = setOf("mg:20", "tab:1"), source = "Integrated ASHA Module Part C p23; Part B p133; HBYC Handbook p72"),
        // Paracetamol tablet 500 mg (max 4 times a day, 3 days).
        Row("paracetamol", "tablet", 60, 3 * Y - 1, doseEn = "¼ tablet of 500 mg (2 months–3 years, 4–14 kg), at most 4 times a day", doseHi = "500 mg की ¼ गोली (2 महीने–3 साल, 4–14 किलो), दिन में अधिकतम 4 बार",
            amounts = setOf("tab:1/4", "mg:125"), source = "HBYC Handbook p92; Induction Module p138"),
        Row("paracetamol", "tablet", 3 * Y, 8 * Y - 1, doseEn = "½ tablet of 500 mg (3–8 years), 3–4 times a day", doseHi = "500 mg की ½ गोली (3–8 साल), दिन में 3–4 बार",
            amounts = setOf("tab:1/2", "mg:250"), source = "HBYC Handbook p92; Induction Module p138"),
        Row("paracetamol", "tablet", 8 * Y, 12 * Y - 1, doseEn = "1 tablet of 500 mg (8–12 years), 3–4 times a day", doseHi = "500 mg की 1 गोली (8–12 साल), दिन में 3–4 बार",
            amounts = setOf("tab:1", "mg:500"), source = "Induction Module p138"),
        Row("paracetamol", "tablet", 12 * Y, 120 * Y, doseEn = "1–2 tablets of 500 mg (over 12 years / adults), 3–4 times a day", doseHi = "500 mg की 1–2 गोली (12 साल से ऊपर), दिन में 3–4 बार",
            amounts = setOf("tab:1", "tab:2", "mg:500", "mg:1000"), source = "Induction Module p137; Integrated ASHA Module Part C p17"),
        // Paracetamol syrup 125 mg / 5 ml.
        Row("paracetamol", "syrup", 0, 28, doseEn = "1 ml (newborn under 3 kg)", doseHi = "1 ml (3 किलो से कम नवजात)",
            amounts = setOf("ml:1", "ml:1.25"), source = "HBYC Handbook p92",
            conflict = "Induction Module p138 gives 1.25 ml (¼ teaspoon)"),
        Row("paracetamol", "syrup", 29, Y - 1, doseEn = "2.5 ml (½ teaspoon) (under 1 year, 3–8 kg)", doseHi = "2.5 ml (½ चम्मच) (1 साल से कम, 3–8 किलो)",
            amounts = setOf("ml:2.5"), source = "HBYC Handbook p92; Induction Module p138"),
        Row("paracetamol", "syrup", Y, 3 * Y - 1, doseEn = "5 ml (1 teaspoon) (1–3 years, 8–14 kg)", doseHi = "5 ml (1 चम्मच) (1–3 साल, 8–14 किलो)",
            amounts = setOf("ml:5"), source = "HBYC Handbook p92; Induction Module p138"),
        Row("paracetamol", "syrup", 3 * Y, 5 * Y, doseEn = "7.5 ml (1½ teaspoons) (over 3 years, over 14 kg)", doseHi = "7.5 ml (1½ चम्मच) (3 साल से ऊपर, 14 किलो से ज़्यादा)",
            amounts = setOf("ml:7.5"), source = "Induction Module p138"),
        // Albendazole.
        Row("albendazole", "", 0, Y - 1, doseEn = "not to be given under 1 year", doseHi = "1 साल से कम उम्र में नहीं देनी है",
            amounts = emptySet(), source = "ASHA Module 7 p81", notGiven = true),
        Row("albendazole", "", Y, 2 * Y - 1, doseEn = "200 mg (½ tablet of 400 mg), once", doseHi = "200 mg (400 mg की आधी गोली), एक बार",
            amounts = setOf("mg:200", "tab:1/2"), source = "Anemia Mukt Bharat Guidelines p77; ASHA Module 7 p81"),
        Row("albendazole", "", 2 * Y, 120 * Y, doseEn = "400 mg (1 tablet), once", doseHi = "400 mg (1 गोली), एक बार",
            amounts = setOf("mg:400", "tab:1"), source = "Anemia Mukt Bharat Guidelines p77; ASHA Module 7 p81"),
        Row("albendazole", "", 0, 120 * Y, group = "pregnant", doseEn = "400 mg (1 tablet) once, after the first trimester", doseHi = "400 mg (1 गोली) एक बार, पहली तिमाही के बाद",
            amounts = setOf("mg:400", "tab:1"), source = "Anemia Mukt Bharat Guidelines p77"),
        // Vitamin A.
        Row("vitamin_a", "", 270, 480 - 1, doseEn = "1 ml (1 lakh IU) at 9 months", doseHi = "9 महीने पर 1 ml (1 लाख IU)",
            amounts = setOf("ml:1", "iu:100000"), source = "Immunization Handbook p34"),
        Row("vitamin_a", "", 480, 5 * Y, doseEn = "2 ml (2 lakh IU) every 6 months from 16 months to 5 years", doseHi = "16 महीने से 5 साल तक हर 6 महीने 2 ml (2 लाख IU)",
            amounts = setOf("ml:2", "iu:200000"), source = "Immunization Handbook p34"),
        // IFA (Anemia Mukt Bharat). Prophylaxis and treatment amounts are both accepted.
        Row("ifa", "", 180, 5 * Y - 1, doseEn = "IFA syrup 1 ml twice a week (prophylaxis); for anaemia 1–2 ml daily by weight (6–12 months 1 ml, 1–3 years 1.5 ml, 3–5 years 2 ml)",
            doseHi = "IFA सिरप 1 ml हफ्ते में दो बार (बचाव); खून की कमी में वज़न अनुसार रोज़ 1–2 ml",
            amounts = setOf("ml:1", "ml:1.5", "ml:2", "mg:20"), source = "Anemia Mukt Bharat Guidelines p47, p72"),
        Row("ifa", "", 5 * Y, 10 * Y - 1, doseEn = "1 pink IFA tablet (45 mg iron) once a week; for anaemia once a day for 3 months",
            doseHi = "1 गुलाबी IFA गोली (45 mg आयरन) हफ्ते में एक बार; खून की कमी में रोज़, 3 महीने",
            amounts = setOf("mg:45", "tab:1"), source = "Anemia Mukt Bharat Guidelines p48, p70"),
        Row("ifa", "", 10 * Y, 20 * Y - 1, doseEn = "1 blue IFA tablet (60 mg iron) once a week", doseHi = "1 नीली IFA गोली (60 mg आयरन) हफ्ते में एक बार",
            amounts = setOf("mg:60", "tab:1"), source = "Anemia Mukt Bharat Guidelines p48"),
        Row("ifa", "", 0, 120 * Y, group = "pregnant", doseEn = "1 red IFA tablet (60 mg iron + 500 mcg folic acid) daily for 180 days from the second trimester",
            doseHi = "1 लाल IFA गोली (60 mg आयरन + 500 mcg फोलिक एसिड) रोज़, दूसरी तिमाही से 180 दिन",
            amounts = setOf("mg:60", "tab:1"), source = "Anemia Mukt Bharat Guidelines p50"),
        Row("ifa", "", 0, 120 * Y, group = "postpartum", doseEn = "1 red IFA tablet (60 mg iron) daily for 180 days after delivery", doseHi = "1 लाल IFA गोली (60 mg आयरन) प्रसव के बाद रोज़ 180 दिन",
            amounts = setOf("mg:60", "tab:1"), source = "Anemia Mukt Bharat Guidelines p50"),
    )

    private val MEDICINE: Map<String, Regex> = linkedMapOf(
        "zinc" to Regex("\\bzinc\\b|\\bjink\\b|जिंक|ज़िंक"),
        "paracetamol" to Regex("paracetamol|\\bpcm\\b|पैरासिटामोल|पेरासिटामोल|पैरासीटामोल"),
        "albendazole" to Regex("albendazole|अल्बेंडाजोल|एल्बेंडाजोल|एल्बेंडाज़ोल"),
        "vitamin_a" to Regex("vitamin[ -]?a\\b|विटामिन[ -]?ए"),
        "ifa" to Regex("\\bifa\\b|iron[ -]?folic|iron tablet|\\biron syrup|आयरन|आईएफए|फोलिक"),
    )

    /** Tablet strengths in the drug kit (a fraction of the tablet is the dose, not these). */
    private val STRENGTH = mapOf("paracetamol" to setOf("mg:500"), "zinc" to setOf("mg:20"), "albendazole" to setOf("mg:400"))

    data class Stated(val medicine: String, val form: String, val amounts: Set<String>)
    data class Finding(val medicine: String, val row: Row, val stated: Set<String>, val reason: String)

    private fun norm(t: String): String =
        ArogyaAiExtras.asciiDigits(t).lowercase(java.util.Locale.ROOT).replace(Regex("(\\d),(\\d{1,2})(?=\\s*(?:mg|ml))"), "$1.$2")

    private fun num(v: String): String = v.toDoubleOrNull()?.let { d -> if (d == Math.floor(d)) d.toLong().toString() else d.toString() } ?: v

    /** Amounts written in one piece of text, normalised like [Row.amounts]. */
    fun amountsIn(piece: String): Set<String> {
        val t = norm(piece).replace(Regex("\\d+(?:\\.\\d+)?\\s*mg\\s*/\\s*\\d*(?:\\.\\d+)?\\s*ml"), " ")
        val out = LinkedHashSet<String>()
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:mg|मि\\.?\\s?ग्रा)").findAll(t).forEach { out += "mg:" + num(it.groupValues[1]) }
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:ml|मि\\.?\\s?ली)").findAll(t).forEach { out += "ml:" + num(it.groupValues[1]) }
        Regex("(\\d+(?:\\.\\d+)?)\\s*lakh|(\\d{1,3}(?:,\\d{2,3})+|\\d{5,6})\\s*iu|(\\d+)\\s*लाख").findAll(t).forEach { m ->
            val lakh = m.groupValues[1].ifBlank { m.groupValues[3] }
            out += if (lakh.isNotBlank()) "iu:" + num((lakh.toDouble() * 100000).toString()) else "iu:" + m.groupValues[2].replace(",", "")
        }
        // Fractions of a tablet (English and Hindi words, symbols).
        val tablet = "(?:tab(?:let)?s?|गोली|गोलियां|गोलियाँ)"
        if (Regex("(?:¼|1/4|one[- ]fourth|quarter|चौथाई)\\s*(?:of\\s*)?(?:[\\p{L}]+\\s*){0,2}$tablet").containsMatchIn(t)) out += "tab:1/4"
        if (Regex("(?:½|1/2|half|आधी|आधा)\\s*(?:of\\s*)?(?:[\\p{L}]+\\s*){0,2}$tablet|$tablet\\s*(?:का|की)?\\s*(?:आधा|आधी)").containsMatchIn(t)) out += "tab:1/2"
        if (Regex("(?<![/\\d¼½.])(?:\\b1\\b|\\bone\\b|full|whole|एक|पूरी|1)\\s*(?:full\\s*)?(?:[\\p{L}]+\\s*){0,2}$tablet").containsMatchIn(t) &&
            !Regex("(?:¼|½|1/4|1/2|half|quarter|आधी|आधा|चौथाई)\\s*(?:of\\s*)?(?:[\\p{L}]+\\s*){0,2}$tablet").containsMatchIn(t)) out += "tab:1"
        if (Regex("(?:\\b2\\b|\\btwo\\b|दो)\\s*(?:[\\p{L}]+\\s*){0,2}$tablet").containsMatchIn(t)) out += "tab:2"
        // Teaspoons for syrups.
        Regex("(¼|½|1½|1\\s*1/2|1/4|1/2|\\d+(?:\\.\\d+)?)\\s*(?:tsp|teaspoons?|tea spoons?|चम्मच)").findAll(t).forEach { m ->
            val v = when (m.groupValues[1].replace(" ", "")) { "¼", "1/4" -> 1.25; "½", "1/2" -> 2.5; "1½", "11/2" -> 7.5; else -> (m.groupValues[1].toDoubleOrNull() ?: 0.0) * 5 }
            if (v > 0) out += "ml:" + num(v.toString())
        }
        return out
    }

    /**
     * For each medicine the answer names, the amounts written after it (up to the next medicine)
     * in the same sentence or list line.
     */
    fun stated(answer: String): List<Stated> {
        val out = mutableListOf<Stated>()
        for (sentence in ArogyaOfficialAnswer.splitSentences(answer)) {
            val t = norm(sentence)
            val hits = MEDICINE.flatMap { (id, rx) -> rx.findAll(t).map { it.range.first to id }.toList() }.sortedBy { it.first }
            hits.forEachIndexed { i, (start, id) ->
                // The medicine's part of the line: from the previous medicine's name to the next one's,
                // so "20 mg zinc" and "zinc 20 mg" both count, and a second medicine's amount does not.
                val from = if (i == 0) 0 else hits[i - 1].second.let { prev -> (MEDICINE[prev]!!.find(t, hits[i - 1].first)?.range?.last ?: hits[i - 1].first) + 1 }
                val end = hits.getOrNull(i + 1)?.first ?: t.length
                val piece = t.substring(from.coerceAtMost(start), end)
                val form = when {
                    Regex("syrup|सिरप|drops|बूंद").containsMatchIn(piece) -> "syrup"
                    Regex("tab(?:let)?|गोली").containsMatchIn(piece) -> "tablet"
                    else -> ""
                }
                var amounts = amountsIn(piece)
                // "¼ tablet of paracetamol 500 mg": with a fraction of a tablet, the tablet's own strength is not the dose.
                if (amounts.any { it == "tab:1/4" || it == "tab:1/2" }) amounts = amounts.filterNot { it in STRENGTH[id].orEmpty() }.toSet()
                val negated = Regex("\\b(?:not|never|don't|do not|avoid)\\b|नहीं|मत").containsMatchIn(t.substring(maxOf(0, start - 40), end))
                if (amounts.isNotEmpty() || !negated) out += Stated(id, form, amounts)
            }
        }
        return out
    }

    /** Age in days from the question (or the visit's age), and whether it is about a pregnant / delivered woman. */
    fun patient(question: String, visitAge: String = ""): Pair<Long?, String> {
        val q = ArogyaClinicalText.normalise(question)
        val group = when {
            Regex("pregnan|antenatal|garbh|गर्भ").containsMatchIn(q) -> "pregnant"
            Regex("after delivery|post-?partum|postnatal|lactating|प्रसव के बाद|प्रसूता").containsMatchIn(q) -> "postpartum"
            else -> ""
        }
        if (group.isNotEmpty()) return null to group
        val age = ageDays(q) ?: ageDays(ArogyaClinicalText.normalise(visitAge))
        return age to ""
    }

    private val YEARS = Regex("(\\d{1,2}(?:\\.5)?)\\s*[- ]?\\s*(?:years?|yrs?|saal|sal|वर्ष|साल)(?:[- ]?old|\\s+(?:ka|ki|ke|का|की|के|child|baby|boy|girl|bachch?[aeo]|बच्च))")

    /** Days/weeks/months (as the danger-sign reader counts them), else years. */
    fun ageDays(t: String): Long? {
        runCatching { ArogyaDangerSigns.textAgeDays(t) }.getOrNull()?.let { return it }
        return YEARS.find(t)?.groupValues?.get(1)?.toDoubleOrNull()?.let { (it * Y).toLong() }
    }

    /** Guide rows for a medicine and patient (form "" = any form). */
    fun rowsFor(medicine: String, form: String, ageDays: Long?, group: String): List<Row> = ROWS.filter { r ->
        r.medicine == medicine && (form.isEmpty() || r.form.isEmpty() || r.form == form) &&
            (if (group.isNotEmpty()) r.group == group else r.group.isEmpty() && ageDays != null && ageDays in r.minDays..r.maxDays)
    }

    /**
     * The first medicine whose stated amount matches none of the guide's rows for this patient, or a
     * medicine the guide says is not given at this age. Null when everything matches or nothing can be
     * checked (no age, no amount, or a medicine/age the table does not cover).
     */
    fun check(question: String, answer: String, visitAge: String = ""): Finding? {
        val (age, group) = patient(question, visitAge)
        if (age == null && group.isEmpty()) return null
        for (s in stated(answer)) {
            val rows = rowsFor(s.medicine, s.form, age, group)
            if (rows.isEmpty()) continue
            rows.firstOrNull { it.notGiven }?.let { return Finding(s.medicine, it, s.amounts, "not_given") }
            if (s.amounts.isEmpty()) continue
            val kinds = rows.flatMap { r -> r.amounts.map { it.substringBefore(':') } }.toSet()
            val comparable = s.amounts.filter { it.substringBefore(':') in kinds }.toSet()
            if (comparable.isEmpty()) continue
            // A row fits when, for every kind of amount both give (mg, ml, tablets, IU), the answer's value is one
            // of the row's: "one IFA tablet of 60 mg" for a 7-year-old fails on mg even though "1 tablet" fits.
            fun fits(r: Row): Boolean = comparable.groupBy { it.substringBefore(':') }.all { (kind, vals) ->
                val allowed = r.amounts.filter { it.startsWith("$kind:") }
                allowed.isEmpty() || vals.any { it in allowed }
            }
            if (rows.none(::fits)) return Finding(s.medicine, rows.first(), comparable, "amount")
        }
        return null
    }

    private val NAME_EN = mapOf("zinc" to "Zinc", "paracetamol" to "Paracetamol", "albendazole" to "Albendazole", "vitamin_a" to "Vitamin A", "ifa" to "IFA")
    private val NAME_HI = mapOf("zinc" to "जिंक", "paracetamol" to "पैरासिटामोल", "albendazole" to "एल्बेंडाजोल", "vitamin_a" to "विटामिन ए", "ifa" to "IFA")

    private fun show(a: String): String {
        val (k, v) = a.split(':', limit = 2)
        return when (k) { "tab" -> "$v tablet"; "iu" -> "$v IU"; else -> "$v $k" }
    }

    /** One caution line: the guide's dose for this age with its page, and what the answer said. */
    fun caution(f: Finding, hindi: Boolean): String {
        val conflictEn = if (f.row.conflict.isNotBlank()) " Note: ${f.row.conflict}." else ""
        val conflictHi = if (f.row.conflict.isNotBlank()) " ध्यान दें: गाइडों में अंतर है (${f.row.conflict})." else ""
        val said = f.stated.joinToString(", ") { show(it) }
        return if (hindi) when (f.reason) {
            "not_given" -> "⚠ गाइड के अनुसार ${NAME_HI[f.medicine]}: ${f.row.doseHi} (${f.row.source})। ANM से पक्का करें।"
            else -> "⚠ गाइड में इस उम्र के लिए ${NAME_HI[f.medicine]} की खुराक: ${f.row.doseHi} (${f.row.source})। उत्तर में $said लिखा है — ANM से पक्का करें।$conflictHi"
        } else when (f.reason) {
            "not_given" -> "⚠ Guide: ${NAME_EN[f.medicine]} is ${f.row.doseEn} (${f.row.source}). Confirm with your ANM."
            else -> "⚠ Guide dose of ${NAME_EN[f.medicine]} for this age: ${f.row.doseEn} (${f.row.source}). The answer says $said — confirm with your ANM.$conflictEn"
        }
    }
}
