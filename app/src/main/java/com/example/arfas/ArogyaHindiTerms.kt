package com.example.arfas

/**
 * Deterministic Hindi → English search terms for retrieval (Batch 2). The model's
 * English rewrite exists but costs a call and can be skipped or poor for short
 * Hindi questions (M1 Q14/Q17 misses); these dictionary terms are added as extra
 * search views so Devanagari and Hinglish questions always search English words
 * too. The question itself is untouched, so the answer stays in Hindi.
 * Pure Kotlin, host-testable. Gated by ArogyaFlags.hindiRewrite.
 */
object ArogyaHindiTerms {
    /** Devanagari or Hinglish key → English document terms. Order: longest match wins. */
    private val TERMS: List<Pair<String, String>> = listOf(
        "नवजात शिशु" to "newborn",
        "navjaat shishu" to "newborn",
        "navjat shishu" to "newborn",
        "नवजात" to "newborn",
        "navjat" to "newborn",
        "navjaat" to "newborn",
        "गर्भवती महिला" to "pregnant woman",
        "pregnant woman" to "pregnant woman",
        "गर्भवती" to "pregnant",
        "garbhvati" to "pregnant",
        "garbhavati" to "pregnant",
        "गर्भावस्था" to "pregnancy",
        "स्तनपान" to "breastfeeding",
        "stanpan" to "breastfeeding",
        "doodh pilana" to "breastfeeding",
        "दूध पिलाना" to "breastfeeding",
        "खतरे के लक्षण" to "danger signs referral",
        "khatre ke lakshan" to "danger signs referral",
        "खतरा" to "danger",
        "khatra" to "danger",
        "लक्षण" to "signs symptoms",
        "lakshan" to "signs symptoms",
        "सांस" to "breathing",
        "saans" to "breathing",
        "दस्त" to "diarrhoea",
        "dast" to "diarrhoea",
        "loose motion" to "diarrhoea",
        "बुखार" to "fever temperature",
        "bukhar" to "fever temperature",
        "खुराक" to "dose",
        "khurak" to "dose",
        "khuraq" to "dose",
        "जिंक" to "zinc",
        "jink" to "zinc",
        "zinc" to "zinc",
        "टीका" to "immunization vaccine",
        "tika" to "immunization vaccine",
        "teeka" to "immunization vaccine",
        "teekakaran" to "immunization",
        "जांच" to "checkup examination anc",
        "jaanch" to "checkup examination anc",
        "जाँच" to "checkup examination anc",
        "कितनी बार" to "how many schedule",
        "kitni baar" to "how many schedule",
        "कब कब" to "schedule when",
        "kab kab" to "schedule when",
        "कैसे बनाएं" to "how to prepare method",
        "kaise banaye" to "how to prepare method",
        "कैसे बनायें" to "how to prepare method",
        "कैसे दें" to "how to give",
        "kaise dena" to "how to give",
        "कैसे" to "how",
        "kaise" to "how",
        "क्या है" to "what is",
        "kya hai" to "what is",
        "क्या हैं" to "what are",
        "कौन कौन" to "which names list",
        "kaun kaun" to "which names list",
        "कितने" to "how many",
        "kitne" to "how many",
        "कितना" to "how much",
        "kitna" to "how much",
        "बच्चे को" to "child baby",
        "bachche ko" to "child baby",
        "bacche ko" to "child baby",
        "बच्चा" to "child",
        "bachcha" to "child",
        "शिशु" to "infant",
        "shishu" to "infant",
        "माँ" to "mother",
        "maa" to "mother",
        "रक्त" to "blood",
        "rakt" to "blood",
        "खून" to "blood",
        "khoon" to "blood",
        "एनीमिया" to "anaemia",
        "anemia" to "anaemia",
        "आयरन" to "iron ifa",
        "iron" to "iron ifa",
        "गोली" to "tablet",
        "goli" to "tablet",
        "सिरप" to "syrup",
        "sirap" to "syrup",
        "अस्पताल" to "hospital facility referral",
        "aspatal" to "hospital facility referral",
        "रेफर" to "referral",
        "refer" to "referral",
        "वजन" to "weight",
        "vajan" to "weight",
        "वज़न" to "weight",
        "टीकाकरण" to "immunization",
        "मलेरिया" to "malaria",
        "malaria" to "malaria",
        "तपेदिक" to "tuberculosis",
        "दाई" to "midwife dai",
        "आशा" to "asha",
        "एएनएम" to "anm",
        "सफाई" to "clean hygiene",
        "safai" to "clean hygiene",
        "पानी" to "water",
        "paani" to "water",
        "ओआरएस" to "ors",
        "ors" to "ors",
        // 11 Nov 2026 (AQ): symptom and body words. Without them the phone's own rewrite turned "पैरों में सूजन और
        // सिरदर्द" (swelling of the feet and headache) into "bleeding and nausea" and searched the wrong pages.
        "सूजन" to "swelling oedema",
        "sujan" to "swelling oedema",
        "soojan" to "swelling oedema",
        "सिरदर्द" to "headache",
        "सिर दर्द" to "headache",
        "sirdard" to "headache",
        "sir dard" to "headache",
        "पेट दर्द" to "abdominal pain",
        "pet dard" to "abdominal pain",
        "दर्द" to "pain",
        "पैर" to "feet legs",
        "पांव" to "feet",
        "हाथ" to "hands",
        "चेहरे" to "face",
        "चेहरा" to "face",
        "आंख" to "eyes",
        "आँख" to "eyes",
        "धुंधला" to "blurred vision",
        "उल्टी" to "vomiting",
        "ulti" to "vomiting",
        "खांसी" to "cough",
        "खाँसी" to "cough",
        "khansi" to "cough",
        "चक्कर" to "dizziness",
        "chakkar" to "dizziness",
        "कमजोरी" to "weakness",
        "कमज़ोरी" to "weakness",
        "kamzori" to "weakness",
        "थकान" to "tiredness",
        "झटके" to "convulsions fits",
        "jhatke" to "convulsions fits",
        "दौरे" to "convulsions fits",
        "बेहोश" to "unconscious",
        "behosh" to "unconscious",
        "पीलिया" to "jaundice",
        "peeliya" to "jaundice",
        "पेशाब" to "urine",
        "peshab" to "urine",
        "जलन" to "burning",
        "खुजली" to "itching",
        "कब्ज" to "constipation",
        "टीबी" to "tuberculosis",
        "ब्लड प्रेशर" to "blood pressure",
        "रक्तस्राव" to "bleeding",
        "खून आना" to "bleeding",
        "खून बहना" to "bleeding",
    ).sortedByDescending { it.first.length }

    /** Question words, not meanings: never offered to the rewrite as a term. */
    private val QUESTION_WORDS = setOf("how", "what is", "what are", "how many", "how much", "which names list",
        "how many schedule", "schedule when", "how to prepare method", "how to give")

    /**
     * English lay words the guides never use, mapped to the guides' own words (11 Nov audit:
     * "loose motion" appears 0 times in the ASHA pack, "diarrhoea" 215; the English question
     * retrieved developmental-milestone pages instead of ORS/zinc). A general lay → guide
     * vocabulary bridge, like [TERMS] for Hindi: it only adds a search view, never an answer.
     */
    private val ENGLISH_LAY: List<Pair<Regex, String>> = listOf(
        Regex("\\bloose\\s*motions?\\b", RegexOption.IGNORE_CASE) to "diarrhoea",
        Regex("\\bmotions\\b", RegexOption.IGNORE_CASE) to "diarrhoea",
        Regex("\\b(?:stomach|tummy|belly)\\s*(?:pain|ache)\\b", RegexOption.IGNORE_CASE) to "abdominal pain",
        Regex("\\bthrowing\\s+up\\b", RegexOption.IGNORE_CASE) to "vomiting",
        Regex("\\b(?:high|low)?\\s*bp\\b", RegexOption.IGNORE_CASE) to "blood pressure",
    )

    /**
     * One extra search view for an English question that uses a lay word: the question with
     * each lay word replaced by the guide's word. Empty when no lay word is present or the
     * guide's word is already in the question.
     */
    fun englishViews(question: String): List<String> {
        var view = question
        for ((rx, guideWord) in ENGLISH_LAY) {
            if (guideWord in view.lowercase(java.util.Locale.ROOT)) continue
            view = rx.replace(view) { m ->
                val lead = m.value.takeWhile { it.isWhitespace() }
                val prefix = Regex("^\\s*(high|low)\\s+", RegexOption.IGNORE_CASE).find(m.value)?.groupValues?.get(1)
                lead + (if (prefix != null) "$prefix " else "") + guideWord
            }
        }
        return if (view == question) emptyList() else listOf(view.replace(Regex("\\s{2,}"), " ").trim())
    }

    /**
     * 11 Nov 2026 (AQ): the Hindi words of [question] with their English meaning ("सूजन = swelling"), given to the
     * phone's question rewrite as hints so it translates the symptom and body part exactly. Devanagari words only
     * (Hinglish spellings vary too much to quote back); at most [max]. Empty when none is known.
     */
    fun glosses(question: String, max: Int = 8): List<String> {
        val q = " ${question.lowercase(java.util.Locale.ROOT)} "
        val out = ArrayList<String>()
        for ((key, en) in TERMS) {
            if (key.none { it.code in 0x0900..0x097F } || en in QUESTION_WORDS || key !in q) continue
            // A longer key already covers this one ("सिरदर्द" before "दर्द").
            if (out.any { it.substringBefore(" = ").contains(key) }) continue
            out += "$key = $en"
            if (out.size >= max) break
        }
        return out
    }

    /** True when the question has Devanagari or reads as Hinglish. */
    fun needsViews(question: String): Boolean =
        question.any { it.code in 0x0900..0x097F } || CoreHealthFacts.isHinglish(question) ||
            runCatching { ArogyaQuestionNormalizer.normalize(question).hinglishEnglish.isNotEmpty() }.getOrDefault(false)

    /**
     * English search views for a Hindi/Hinglish question (at most [maxWords] terms
     * in one view). Empty when nothing matched — the normal path is unchanged.
     */
    fun views(question: String, maxWords: Int = 12): List<String> {
        // Canonical Hinglish spellings first ("bukhaar"/"bukar" → "bukhar"), so the keys below match.
        val norm = runCatching { ArogyaQuestionNormalizer.normalize(question) }.getOrNull()
        val q = " ${(norm?.text ?: question).lowercase(java.util.Locale.ROOT)} "
        val found = LinkedHashSet<String>()
        norm?.hinglishEnglish?.forEach { en -> en.split(' ').forEach { found += it } }
        for ((key, en) in TERMS) {
            if (key in q) en.split(' ').forEach { found += it }
            if (found.size >= maxWords) break
        }
        return if (found.isEmpty()) emptyList() else listOf(found.take(maxWords).joinToString(" "))
    }
}
