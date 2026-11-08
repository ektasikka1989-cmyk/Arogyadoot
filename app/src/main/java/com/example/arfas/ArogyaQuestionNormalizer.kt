package com.example.arfas

/**
 * Question understanding (8 Nov 2026): one cleaned form of the ASHA's question, used by every
 * later step (question type, age-aware search, search views, the answer prompt).
 *
 * ASHAs type fast on small phones and mix Hindi and English. Before this, only one extra search
 * lane saw a corrected spelling (and only for words of 4+ letters without digits); question type,
 * age search and the prompt all used the raw text ("a baby is havong h9gh fever").
 *
 * Steps, per Latin-script word (Devanagari is never changed):
 *  1. numbers and doses ("38.5", "500mg") are kept;
 *  2. SMS spellings → English ("wat shud i do" → "what should i do");
 *  3. Hinglish words keep their meaning and get one canonical spelling ("bukhaar" → "bukhar");
 *  4. a word the guide itself uses is never changed;
 *  5. a digit inside a word is a slipped key ("h9gh" → "high", via the keys around the digit);
 *  6. other unknown English words → the closest guide word (1 edit; 2 for long words), most
 *     common first; short words only to an equal-or-longer word ("bby" → "baby", never "by").
 * The original question is always kept; the answer prompt shows both.
 * Pure Kotlin, host-tested (tools/host-tests/QuestionNormalizerTest.kt).
 */
object ArogyaQuestionNormalizer {

    /** Guide words → number of passages using them. Loaded at app start from assets/guide/vocab_en.txt. */
    @Volatile var vocabulary: Map<String, Int> = emptyMap()

    /** Loads "word<TAB>count" lines (# comments ignored). */
    fun loadVocabulary(lines: Sequence<String>) {
        val m = HashMap<String, Int>(16_384)
        for (line in lines) {
            if (line.startsWith("#")) continue
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            m[line.substring(0, tab)] = line.substring(tab + 1).trim().toIntOrNull() ?: continue
        }
        vocabulary = m
        byLength = m.keys.groupBy { it.length }
    }
    @Volatile private var byLength: Map<Int, List<String>> = emptyMap()

    data class Result(
        val original: String,
        /** The question with typos fixed and Hinglish spellings made canonical (lower case where changed). */
        val text: String,
        /** (typed, used) for every word that changed. */
        val fixes: List<Pair<String, String>>,
        /** English meanings of the Hinglish words found ("bukhar" → "fever"). */
        val hinglishEnglish: List<String>
    ) {
        val changed: Boolean get() = fixes.isNotEmpty()
    }

    fun normalize(question: String): Result {
        val fixes = ArrayList<Pair<String, String>>()
        val english = LinkedHashSet<String>()
        val tokens = TOKEN.findAll(question).map { it.value.lowercase() }.toList()
        // "to", "do", "me" are English too: only Hindi-only words make a question Hinglish.
        val hinglishCount = tokens.count { (it in HINGLISH_FUNCTION && it !in vocabulary && it !in COMMON) || canonicalHinglish(it) != null }
        val hinglishQuestion = hinglishCount >= 2
        val out = TOKEN.replace(question) { m ->
            val typed = m.value
            val t = typed.lowercase()
            val used = fixWord(t, hinglishQuestion)
            if (used !in HINGLISH_FUNCTION) canonicalHinglish(used)?.let { key -> HINGLISH[key]?.let { english += it } }
            if (used != t) { fixes += typed to used; used } else typed
        }
        // Two-word Hinglish phrases ("tez bukhar", "khoon ki kami") after the words are canonical.
        val lower = " " + out.lowercase() + " "
        for ((phrase, en) in HINGLISH_PHRASES) if (" $phrase " in lower) english += en
        return Result(question, out, fixes, english.toList())
    }

    /** The cleaned question in lower case, for matching rules (question type, age group). */
    fun forMatching(question: String): String = normalize(question).text.lowercase()

    private fun fixWord(t: String, hinglishQuestion: Boolean): String {
        if (t.isEmpty() || NUMBERISH.matches(t)) return t
        SMS[t]?.let { return it }
        if (t in HINGLISH_FUNCTION) return t
        canonicalHinglish(t)?.let { return it }
        val vocab = vocabulary
        if (t in vocab || t in COMMON) return t
        if (vocab.isEmpty()) return t
        if (t.any { it.isDigit() } && t.any { it.isLetter() }) return fixDigitSlip(t) ?: t
        if (!t.all { it.isLetter() } || t.length < 3) return t
        // In a Hinglish question a short unknown word is most likely Hindi: leave it.
        if (hinglishQuestion && t.length <= 4) return t
        return closestGuideWord(t) ?: t
    }

    /** "h9gh" → "high": a digit typed instead of a nearby letter (keys around it, or look-alike). */
    private fun fixDigitSlip(t: String): String? {
        val digits = t.count { it.isDigit() }
        if (digits != 1 || t.length < 3) return null
        val i = t.indexOfFirst { it.isDigit() }
        val candidates = (KEY_NEIGHBOURS[t[i]].orEmpty() + LOOK_ALIKE[t[i]].orEmpty()).distinct()
        return candidates.map { c -> t.substring(0, i) + c + t.substring(i + 1) }
            .filter { it in vocabulary || it in COMMON }
            .maxByOrNull { vocabulary[it] ?: 1 }
    }

    private fun closestGuideWord(t: String): String? {
        val maxEdits = if (t.length >= 8) 2 else 1
        var best: String? = null
        var bestDf = 0
        for (len in (t.length - maxEdits)..(t.length + maxEdits)) {
            if (t.length <= 4 && len < t.length) continue        // "bby" → "baby", never "by"
            for (w in byLength[len].orEmpty()) {
                if (t.length <= 5 && w[0] != t[0]) continue
                val df = vocabulary[w] ?: 0
                if (df < MIN_TARGET_DF || df <= bestDf) continue
                if (withinEdits(t, w, maxEdits)) { best = w; bestDf = df }
            }
        }
        return best
    }

    /** Optimal-string-alignment distance (insert, delete, substitute, swap neighbours) <= [limit]. */
    internal fun withinEdits(a: String, b: String, limit: Int): Boolean {
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        val prev2 = IntArray(b.length + 1); var prev = IntArray(b.length + 1) { it }; var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, prev2[j - 2] + 1)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return false
            System.arraycopy(prev, 0, prev2, 0, prev.size)
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length] <= limit
    }

    /** Canonical Hinglish spelling for [t] ("bukhaar"/"bukar" → "bukhar"), or null. */
    fun canonicalHinglish(t: String): String? {
        if (t in HINGLISH) return t
        VARIANTS[t]?.let { return it }
        if (t.length < 4 || !t.all { it in 'a'..'z' }) return null
        if (t in vocabulary || t in COMMON) return null              // an English word ("baker") stays English
        val sk = skeleton(t)
        if (sk.length < 2) return null
        return HINGLISH_BY_SKELETON[sk]?.takeIf { it.length >= 4 && it[0] == t[0] }
    }

    /** First letter + consonants without h, repeated letters collapsed: bukhar/bukhaar/bukar → "bkr". */
    private fun skeleton(w: String): String {
        val sb = StringBuilder().append(w[0])
        for (c in w.drop(1)) {
            if (c in "aeiouyh") continue
            if (sb.last() != c) sb.append(c)
        }
        return sb.toString()
    }

    // ── Data ────────────────────────────────────────────────────────────────────────────────

    /** A word, kept whole with an apostrophe ("don't", "baby's"): "don" alone would be "corrected". */
    private val TOKEN = Regex("[A-Za-z0-9]+(?:['’][A-Za-z]+)?")
    private val NUMBERISH = Regex("\\d+(?:\\.\\d+)?(?:mg|ml|kg|gm|g|cm|mm|c|f|st|nd|rd|th|am|pm|x)?")
    /** A correction must land on a word the guide uses often (rare words: names, OCR noise). */
    private const val MIN_TARGET_DF = 10

    private val SMS = mapOf(
        "wat" to "what", "wht" to "what", "whta" to "what", "wats" to "what is", "whats" to "what is",
        "shud" to "should", "shld" to "should", "shoud" to "should", "sould" to "should", "shoul" to "should",
        "hw" to "how", "hwo" to "how", "wen" to "when", "whn" to "when", "y" to "why", "u" to "you",
        "ur" to "your", "r" to "are", "pls" to "please", "plz" to "please", "abt" to "about",
        "bcoz" to "because", "coz" to "because", "cuz" to "because", "nd" to "and", "n" to "and",
        "giv" to "give", "hv" to "have", "hav" to "have", "dnt" to "don't", "dont" to "don't",
        "cnt" to "can't", "cant" to "can't", "wil" to "will", "tmrw" to "tomorrow", "yr" to "year", "yrs" to "years",
        "mnth" to "month", "mnths" to "months", "dys" to "days", "wk" to "week", "wks" to "weeks",
        "bby" to "baby", "babi" to "baby", "chld" to "child", "kid" to "child", "kids" to "children",
        "preg" to "pregnant", "pregnent" to "pregnant", "pregant" to "pregnant", "temp" to "temperature",
        "fevr" to "fever", "feaver" to "fever", "fiver" to "fever", "fver" to "fever",
        "diarrea" to "diarrhoea", "diarhea" to "diarrhoea", "diarrhea" to "diarrhoea", "loosemotion" to "loose motion",
        "vomitting" to "vomiting", "vomting" to "vomiting", "coff" to "cough", "cof" to "cough",
        "breastfeed" to "breastfeeding", "bf" to "breastfeeding", "newbron" to "newborn", "new-born" to "newborn",
        "vaccin" to "vaccine", "vacine" to "vaccine", "injecton" to "injection", "medicin" to "medicine",
        "diahrea" to "diarrhoea", "diarhoea" to "diarrhoea", "dairrhea" to "diarrhoea", "diarrohea" to "diarrhoea",
        "diarrhoe" to "diarrhoea", "loosemotions" to "loose motions", "cn" to "can", "wt" to "weight",
        "jaundis" to "jaundice", "jondis" to "jaundice", "pnemonia" to "pneumonia", "neumonia" to "pneumonia",
        "convulsion" to "convulsions", "fitz" to "fits",
        "paracetmol" to "paracetamol", "paracitamol" to "paracetamol", "pcm" to "paracetamol", "crocin" to "paracetamol"
    )

    /** Words that must never be "corrected" even if the guide lacks them. */
    private val COMMON = setOf(
        "i", "a", "my", "me", "we", "she", "he", "it", "is", "am", "are", "was", "do", "does", "did", "what", "how", "why",
        "when", "where", "who", "which", "should", "can", "could", "would", "will", "must", "please", "tell", "help",
        "having", "has", "have", "had", "baby", "babies", "child", "children", "kid", "mother", "mom", "ok", "okay",
        "asha", "anm", "phc", "chc", "ors", "ifa", "bp", "tb", "hiv", "hbnc", "hbyc", "jsy", "jssk", "mcp"
    )

    /** Hindi function words written in English letters. */
    private val HINGLISH_FUNCTION = setOf(
        "ko", "ho", "hai", "hain", "he", "toh", "to", "kya", "kaise", "kaisa", "kaisi", "karna", "karein", "karen", "kare",
        "karu", "karoon", "kre", "krna", "chahiye", "chaiye", "mein", "me", "ka", "ki", "ke", "nahi", "nahin", "nhi",
        "bhi", "aur", "kab", "kitna", "kitni", "kitne", "kyun", "kyon", "raha", "rahi", "rahe", "gaya", "gayi", "hota",
        "hoti", "dena", "dein", "de", "do", "lena", "wala", "wali", "agar", "par", "se", "tak", "abhi", "aaj", "mujhe",
        "unko", "isko", "uska", "uski", "hua", "hui", "huye", "din", "saal", "mahine", "hafte", "baar", "bahut", "jyada",
        "zyada", "kam", "tez", "kuch", "koi", "sab", "ye", "yeh", "woh", "wo"
    )

    /** Canonical Hinglish health words → English terms the guide uses. */
    val HINGLISH: Map<String, String> = linkedMapOf(
        "bukhar" to "fever", "dast" to "diarrhoea loose stools", "ulti" to "vomiting", "khansi" to "cough",
        "sardi" to "cold", "zukam" to "cold", "nimonia" to "pneumonia", "piliya" to "jaundice",
        "kamzori" to "weakness", "chakkar" to "dizziness", "behosh" to "unconscious", "jhatke" to "convulsions fits",
        "dard" to "pain", "pet" to "abdomen stomach", "sujan" to "swelling", "khujli" to "itching", "daane" to "rash",
        "khoon" to "blood bleeding", "khun" to "blood bleeding", "saans" to "breathing", "doodh" to "milk breastfeeding",
        "stanpan" to "breastfeeding", "bachcha" to "child baby", "bacche" to "child baby", "bachche" to "child baby",
        "baccha" to "child baby", "shishu" to "infant baby", "navjat" to "newborn", "navjaat" to "newborn",
        "garbhvati" to "pregnant", "garbhavati" to "pregnant", "prasav" to "delivery", "janch" to "checkup examination",
        "jaanch" to "checkup examination", "tika" to "vaccine immunization", "teeka" to "vaccine immunization",
        "teekakaran" to "immunization", "dawai" to "medicine", "dawa" to "medicine", "goli" to "tablet",
        "sirap" to "syrup", "vajan" to "weight", "wajan" to "weight", "khana" to "food feeding", "paani" to "water",
        "pani" to "water", "namak" to "salt", "cheeni" to "sugar", "ghol" to "solution", "safai" to "hygiene cleanliness",
        "mahavari" to "menstruation", "nasbandi" to "sterilization", "mirgi" to "epilepsy fits", "machhar" to "mosquito",
        "chot" to "injury", "haddi" to "bone", "aankh" to "eye", "kaan" to "ear", "daant" to "teeth",
        "peshab" to "urine", "aspatal" to "hospital", "maa" to "mother", "mata" to "mother", "lakshan" to "signs symptoms",
        "khatra" to "danger", "khatre" to "danger", "ilaj" to "treatment", "upchar" to "treatment", "khurak" to "dose",
        "khuraq" to "dose", "kamar" to "back", "sir" to "head", "gala" to "throat", "nabhi" to "umbilical cord",
        "naal" to "cord", "thand" to "cold hypothermia", "garmi" to "heat", "bimari" to "illness disease",
        "mahila" to "woman", "aurat" to "woman", "garbh" to "pregnancy", "kupshoshan" to "malnutrition",
        "dhadkan" to "heartbeat", "harkat" to "movement"
    )
    private val VARIANTS = mapOf(
        "bukhaar" to "bukhar", "bukar" to "bukhar", "bhukhar" to "bukhar", "bukhr" to "bukhar",
        "ulty" to "ulti", "ultee" to "ulti", "khasi" to "khansi", "khaansi" to "khansi", "nimoniya" to "nimonia",
        "pilia" to "piliya", "peeliya" to "piliya", "bacha" to "bachcha", "bachha" to "bachcha", "bachhe" to "bacche",
        "bache" to "bacche", "bachon" to "bacche", "bacho" to "bacche", "sisu" to "shishu", "dudh" to "doodh",
        "davai" to "dawai", "dwai" to "dawai", "teka" to "tika", "jhatka" to "jhatke", "sans" to "saans", "saas" to "saans"
    )
    private val HINGLISH_PHRASES = listOf(
        "tez bukhar" to "high fever", "khoon ki kami" to "anaemia", "kam vajan" to "low birth weight",
        "tez saans" to "fast breathing", "pet dard" to "abdominal pain", "doodh nahi" to "not feeding",
        "hath dhona" to "handwashing", "parivar niyojan" to "family planning", "kutta kata" to "dog bite"
    )
    private val HINGLISH_BY_SKELETON: Map<String, String> =
        HINGLISH.keys.filter { it.length >= 4 }.groupBy { skeleton(it) }.filterValues { it.size == 1 }.mapValues { it.value[0] }

    /** Letters around each digit on a phone keyboard (the row below the digits). */
    private val KEY_NEIGHBOURS = mapOf(
        '1' to listOf('q'), '2' to listOf('w', 'q'), '3' to listOf('e', 'w'), '4' to listOf('r', 'e'),
        '5' to listOf('t', 'r'), '6' to listOf('y', 't'), '7' to listOf('u', 'y'), '8' to listOf('i', 'u'),
        '9' to listOf('o', 'i'), '0' to listOf('p', 'o')
    )
    private val LOOK_ALIKE = mapOf(
        '0' to listOf('o'), '1' to listOf('i', 'l'), '3' to listOf('e'), '4' to listOf('a'), '5' to listOf('s'),
        '7' to listOf('t'), '8' to listOf('b'), '9' to listOf('g')
    )
}
