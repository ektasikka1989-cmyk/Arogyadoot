package com.example.arfas

/**
 * Dose safety (Batch 2): a dose answer must match the ASKED age group (Q11 gave a
 * younger child's IFA tablet for a school-going adolescent). The check is advisory,
 * never silent editing: every dose answer is logged machine-readably for the M1
 * human review list, and a mismatched answer carries a short ANM caution line.
 * Pure Kotlin, host-testable. Gated by ArogyaFlags.doseAgeMatch.
 */
object ArogyaDoseCheck {
    enum class Group { NEWBORN, INFANT, CHILD_UNDER5, CHILD_5_9, ADOLESCENT, ADULT, PREGNANT, UNKNOWN }
    enum class Verdict { MATCH, MISMATCH, UNCERTAIN, NOT_DOSE }

    private val DOSE_WORDS = listOf(
        "dose", "dosage", "khurak", "khuraq", "खुराक", "mg", "ml", "mcg", "tablet", "tablets",
        "goli", "गोली", "syrup", "sirap", "सिरप", "supplement", "ifa", "iron", "folic",
        "vitamin", "zinc", "jink", "जिंक", "ors", "capsule", "drops", "boond", "बूंद"
    )

    // Specificity order matters (checked top-down); each group keeps its own keywords.
    private val GROUP_WORDS: List<Pair<Group, List<String>>> = listOf(
        Group.PREGNANT to listOf("pregnant", "pregnancy", "garbhvati", "garbhavati", "गर्भवती", "गर्भावस्था", "expecting mother", "anc mother"),
        Group.NEWBORN to listOf("newborn", "new born", "neonat", "navjat", "navjaat", "नवजात", "0-28 days", "day old", "din ka"),
        Group.INFANT to listOf("infant", "shishu", "sishu", "शिशु", "month old", "mahine ka", "महीने", "under 1", "1 year", "chhota bachcha", "baby", "bachcha"),
        Group.CHILD_5_9 to listOf("5-9", "5 to 9", "5–9", "5 - 9", "5 se 9", "junior", "junier"),
        Group.ADOLESCENT to listOf("adolescent", "teenager", "kishor", "kishori", "किशोर", "किशोरी", "10-19", "10 to 19", "10–19", "school-going adolescent", "school going adolescent"),
        Group.CHILD_UNDER5 to listOf("under 5", "under five", "under-5", "below 5", "1-5", "1 to 5", "5 saal se kam", "5 वर्ष से कम", "preschool", "anganwadi"),
        Group.ADULT to listOf("adult", "vayask", "वयस्क", "grown"),
    )
    private const val SCHOOL_GOING = "school-going"

    fun isDoseQuestion(q: String): Boolean {
        val t = " ${q.lowercase(java.util.Locale.ROOT)} "
        return DOSE_WORDS.any { w -> " $w " in t || " $w." in t || " $w," in t } ||
            Regex("\\d+\\s*(mg|ml|mcg)\\b").containsMatchIn(t)
    }

    /** The asked age group, most specific first; bare "school-going" stays UNKNOWN (5–18 span). */
    fun askedGroup(q: String): Group {
        val t = q.lowercase(java.util.Locale.ROOT).replace('-', ' ').replace("school going", SCHOOL_GOING)
        for ((g, words) in GROUP_WORDS) if (words.any { it in t }) return g
        return Group.UNKNOWN
    }

    /**
     * Checks the answer's numbers against age context in the evidence: each answer number
     * is looked up in the evidence and the ±[window] characters around it are scanned for
     * age-group words. MATCH when the asked group is found at some number; MISMATCH when
     * only other specific groups are found; UNCERTAIN when no age context exists.
     */
    fun check(question: String, answer: String, evidence: String, window: Int = 150): Verdict {
        if (!isDoseQuestion(question)) return Verdict.NOT_DOSE
        val group = askedGroup(question)
        if (group == Group.UNKNOWN) return Verdict.UNCERTAIN
        val nums = ArogyaAiExtras.numbers(answer).filter { it.isNotBlank() }.distinct()
        if (nums.isEmpty()) return Verdict.NOT_DOSE
        val ev = ArogyaAiExtras.asciiDigits(evidence).lowercase(java.util.Locale.ROOT)
        var sawOther = false
        for (n in nums) {
            var from = 0
            while (true) {
                val at = ev.indexOf(n.lowercase(java.util.Locale.ROOT), from)
                if (at < 0) break
                val near = ev.substring((at - window).coerceAtLeast(0), (at + n.length + window).coerceAtMost(ev.length))
                if (groupWords(group).any { it in near }) return Verdict.MATCH
                if (GROUP_WORDS.any { (g, ws) -> g != group && g != Group.UNKNOWN && ws.any { it in near } }) sawOther = true
                from = at + n.length
            }
        }
        return if (sawOther) Verdict.MISMATCH else Verdict.UNCERTAIN
    }

    private fun groupWords(g: Group): List<String> = GROUP_WORDS.firstOrNull { it.first == g }?.second.orEmpty()

    /**
     * 9 Nov audit: the answer's mg amount is the TABLET STRENGTH in the evidence ("one tablet contains
     * 20 mg", "1/2 tab of 20 mg tablet") while the same evidence also gives a different mg amount nearby
     * (an age-banded dose such as "10 mg"). Returns that amount ("20") or null. Advisory only: the
     * answer is never edited or withheld, it gets [strengthCaution]. A proximity check like [check]
     * accepted "20 mg zinc for a 4-month-old" because 20 mg sits next to "infants" as the strength.
     */
    fun strengthRisk(answer: String, evidence: String): String? {
        fun norm(t: String) = ArogyaAiExtras.asciiDigits(t).lowercase(java.util.Locale.ROOT).replace(Regex("(\\d),(\\d{1,2})(?=\\s*(?:mg|ml|mcg))"), "$1.$2")
        val a = norm(answer)
        val ev = norm(evidence)
        val amounts = Regex("(\\d+(?:\\.\\d+)?)\\s*mg\\b").findAll(a).map { it.groupValues[1] }.toSet()
        for (v in amounts) {
            val q = Regex.escape(v)
            val strength = Regex("(?:contains?|containing|of|strength)\\s+(?:[a-z]+\\s+){0,2}$q\\s*mg\\b|(?<![\\d.])$q\\s*mg\\s+(?:tablet|tab|capsule)s?\\b")
            for (m in strength.findAll(ev)) {
                val near = ev.substring((m.range.first - 300).coerceAtLeast(0), (m.range.last + 300).coerceAtMost(ev.length - 1) + 1)
                val others = Regex("(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*mg\\b").findAll(near).map { it.groupValues[1] }.filter { it != v }.toSet()
                if (others.isNotEmpty()) return v
            }
        }
        return null
    }

    fun strengthCaution(mg: String, hindi: Boolean): String = if (hindi)
        "⚠ गाइड में $mg mg गोली की ताकत है और उम्र के अनुसार खुराक अलग है — इस उम्र की खुराक ANM से पक्की करें।"
    else "⚠ In the guide, $mg mg is the tablet strength and the dose differs by age — confirm the dose for this age with your ANM."

    /** Short caution appended to a mismatched dose answer (never edits the dose itself). */
    fun caution(group: Group, hindi: Boolean): String = if (hindi)
        "⚠ यह खुराक पूछे गए आयु वर्ग के लिए है या नहीं, ANM से ज़रूर पूछें।"
    else "⚠ Check with your ANM that this dose is for the asked age group (${group.name.lowercase().replace('_', ' ')})."
}
