package com.example.arfas

/**
 * Question type for length instructions (R2). Pure Kotlin, host-testable.
 * Decides the shape of the answer the model is asked for: every step for a
 * procedure, every item for a list, a short conditional fact otherwise, and
 * action-first for urgent questions. English, Hindi and Hinglish keywords.
 */
object ArogyaQuestionType {
    enum class Kind { PROCEDURE, LIST, FACT, URGENT }

    private val URGENT = listOf(
        "emergency", "urgent", "danger", "severe", "khatra", "khatre", "turant", "anm ko turant",
        "तुरंत", "खतरा", "खतरे", "आपात", "गंभीर", "दौरा", "झटके", "नहीं रो रहा", "सांस न",
        "saans nahi", "nahi ro raha", "bleeding heavily", "bahut khoon", "बहुत खून",
        "no movement", "koi harkat", "हरकत न", "unconscious", "behosh", "बेहोश",
        "jhatke", "jhatka",
        // 9 Nov 2026 (AH): " fits" with its leading space: "benefits" contains "fits" and made
        // "What are the benefits of using sanitary pads" an URGENT question.
        "convulsion", " fits", "daura"
    )
    private val PROCEDURE = listOf(
        "how ", "how to", "kaise", "कैसे", "step", "method", "tarika", "तरीका",
        "vidhi", "विधि", "procedure", "process", "prepare", "banana", "banaye",
        "बनाएं", "बनायें", "count", "ginti", "गिनती", "ginna", "गिनना", "weigh ", "weighing",
        "taul", "तौल", "vajan", "वजन कैसे", "wash", "dhona", "धोना", "saaf",
        "साफ कैसे", "dilana", "दिलाना", "karna chahiye", "karen", "check karein",
        "जांच", "demonstrat", "samjhayen", "समझाएं"
    )
    private val LIST = listOf(
        " which ", " kaun ", "कौन", "methods", "vidhiyan", "विधियाँ",
        "type of", "types of", "list", "suchi", "सूची", "kaun kaun",
        "कौन कौन", "names", "naam bata", "नाम बत", "symptoms", "lakshan", "लक्षण",
        "signs of", "causes", "karan", "कारण", "schedule", "anusoochi", "अनुसूची",
        "kab kab", "कब कब", "visits", "khorak", "phases", "stages", "charan",
        "चरण बत", "kinds of", "kismein"
    )
    /** "What are the danger signs" asks FOR the signs: a complete list, not action-first. */
    private val DANGER_SIGNS = listOf(
        "danger sign", "danger signs", "खतरे के लक्षण", "खतरे के संकेत",
        "khatre ke lakshan", "khatre ke sanket"
    )
    /**
     * "What should I do" asks for ACTION: the URGENT shape (most useful action first, then steps
     * and referral conditions). Without this, "The baby is having high fever, what should I do?"
     * fell through to FACT ("what it is, its role") and got the shortest answer (8 Nov phone run).
     */
    private val ACTION_ASK = listOf(
        "what should i do", "what do i do", "what to do", "what should we do", "what can i do",
        "what must i do", "what should the asha do", "what should asha do", "what should be done",
        "kya karein", "kya karen", "kya kare ", "kya karna chahiye", "kya karu", "kya karoon",
        "क्या करें", "क्या करे ", "क्या करना चाहिए", "क्या करूँ", "क्या करूं"
    )

    /** "How many/much" asks for one number, never steps ("how to" still means steps). */
    private val HOW_MANY = listOf(
        "how many", "how much", "kitna", "kitni", "kitne", "कितना", "कितनी", "कितने"
    )

    fun kindOf(query: String): Kind {
        // The cleaned question (typos fixed, SMS spellings, canonical Hinglish): "wat shud i do" asks for action.
        val q = " ${runCatching { ArogyaQuestionNormalizer.forMatching(query) }.getOrDefault(query).lowercase(java.util.Locale.ROOT)} "
        // Review order. Three constraints force this exact sequence (dose > list >
        // procedure > dose would be a cycle): "which methods" is a LIST even though
        // "methods" contains a procedure word, while "ORS kaise banaye" is steps even
        // though ORS is a dose word. So list-shaped wins over procedure words, and
        // procedure verbs win over the generic dose test.
        if (DANGER_SIGNS.any { it in q }) return Kind.LIST
        if (URGENT.any { it in q }) return Kind.URGENT
        if (HOW_MANY.any { it in q }) return Kind.FACT
        if (" which " in q && " for " in q && ArogyaDoseCheck.askedGroup(query) != ArogyaDoseCheck.Group.UNKNOWN) return Kind.FACT
        if (LIST.any { it in q }) return Kind.LIST
        if (PROCEDURE.any { it in q }) return Kind.PROCEDURE
        if (ACTION_ASK.any { it in q }) return Kind.URGENT
        if (ArogyaDoseCheck.isDoseQuestion(query)) return Kind.FACT
        return Kind.FACT
    }

    /**
     * 9 Nov 2026 (AF): a statement of a problem with no question words ("child has not passed urine for 8
     * hours and is very weak", "किशोरी को माहवारी में बहुत दर्द होता है") fell through to FACT, the "what it
     * is, its role" shape, and got a short answer without what to do or when to refer. The on-device
     * model restates every question ("What should be done when a child …?"); when the question itself
     * gives FACT, the restatement decides.
     */
    private val ACTION_REWRITE = Regex("(?i)\\bwhat (?:should|must|can) [^?.]{0,30}?\\b(?:do|be done)\\b")
    /** Procedure words without the bare "how " (which "how much" also contains), plus "make". */
    private val STEPS_ASKED = PROCEDURE.filter { it != "how " } + listOf(" make ", " made ")
    fun kindFor(query: String, rewrite: String): Kind {
        val k = kindOf(query)
        // 11 Nov 2026 (gap test): "How do I make ORS at home and how much to give…" asks for steps AND an amount;
        // "how much" made it FACT (short shape, small ceiling) and the recipe could be cut. Steps asked → procedure.
        if (k == Kind.FACT) {
            val q = " ${runCatching { ArogyaQuestionNormalizer.forMatching(query) }.getOrDefault(query).lowercase(java.util.Locale.ROOT)} "
            if (HOW_MANY.any { it in q } && STEPS_ASKED.any { it in q }) return Kind.PROCEDURE
        }
        if (k != Kind.FACT || rewrite.isBlank()) return k
        val r = runCatching { kindOf(rewrite) }.getOrDefault(k)
        // Gemma writes "What should an ASHA do when …" (log 14): an action question in any wording.
        if (r == Kind.FACT && ACTION_REWRITE.containsMatchIn(rewrite)) return Kind.URGENT
        return r
    }

    /**
     * Part B2 output ceiling per question part, routed through ArogyaBudget:
     * phone FACT 600, PROCEDURE/LIST/URGENT 800 (English); cloud 1200/1600;
     * Hindi x2.5, Hinglish x1.3. Procedures and lists must fit every step/item.
     */
    fun ceilingPerPart(kind: Kind): Int = ArogyaBudget.askSop(kind, "", cloud = false)

    /** Same, with the query's language and the engine that will write the answer. */
    fun ceilingPerPart(kind: Kind, query: String, cloud: Boolean, promptTokens: Int = 0,
                       contextWindow: Int = if (cloud) ArogyaBudget.CLOUD_CONTEXT else ArogyaBudget.PHONE_CONTEXT): Int =
        ArogyaBudget.askSop(kind, query, cloud, promptTokens, contextWindow)

    /**
     * The last line the model reads (replaces COMPLETE_ANSWER when r2LengthPrompt is on).
     * Matches the question type so it no longer contradicts the LENGTH tiers.
     * [query] adds the dose rule: a dose asked for a named age group must not list others (Q11).
     * Part B3: five labelled parts (Answer / Why it matters / What to do / When to tell the
     * ANM / Common mistakes to avoid), Hindi labels when the question is in Devanagari, and
     * per-engine sentence totals (six to ten on the phone, eight to fourteen from the cloud).
     */
    fun lengthLine(kind: Kind, query: String = "", cloud: Boolean = false): String {
        val head = "Answer completely in your own words: cover every point the material gives on this question "
        val doseForGroup = query.isNotBlank() && ArogyaDoseCheck.isDoseQuestion(query) &&
            ArogyaDoseCheck.askedGroup(query) != ArogyaDoseCheck.Group.UNKNOWN
        val core = when (kind) {
            Kind.PROCEDURE -> "(this asks HOW to do something: give every numbered step in order, each step " +
                "as its own numbered line with what to watch for while doing it; do not merge steps, do not add steps, do not stop early). "
            Kind.LIST -> "(this asks for ITEMS: give one line per item for every item the material gives, " +
                "each with a one-line explanation; do not stop after the first items). "
            Kind.FACT -> if (doseForGroup) "(this asks a DOSE for one age group: give only the dose for the asked age group; " +
                "do not list other age groups. Keep it short: the dose, how to give it, and for how long). "
            else "(for a \"what is\" question: what it is, its role or purpose, and its key details; " +
                "for a comparison or a \"difference between\" question: first what each one is, then how they " +
                "differ). "
            // 11 Nov 2026 (audit): "URGENT" only when the question itself says so (danger words); an action question
            // recognised from the model's restatement ("what should be done when …") gets the same order without
            // being called urgent, so ordinary problems are not pushed towards "refer now".
            Kind.URGENT -> if (runCatching { kindOf(query) }.getOrNull() == Kind.URGENT)
                "(this is URGENT: start with the most useful action first, then the steps and the referral conditions). "
            else "(start with the most useful action first, then the steps and when to refer). "
        }
        // Part B3 shape: five labelled parts; the card renders each label as a small heading.
        // A Hindi question gets the Hindi labels so the sections still parse.
        val hindi = ArogyaBudget.langOf(query) == "hi"
        val l = if (hindi) listOf("'उत्तर:'", "'क्यों ज़रूरी:'", "'क्या करें:'", "'ANM को कब बताएँ:'", "'आम गलतियाँ:'")
        else listOf("'Answer:'", "'Why it matters:'", "'What to do:'", "'When to tell the ANM:'", "'Common mistakes to avoid:'")
        // 8 Nov 2026: the phone asks for the same full answer as the cloud (quality over speed);
        // "six to ten" on the phone made Gemma stop early even with a large output budget.
        val total = if (cloud) "eight to fourteen" else "eight to fourteen"
        // 12 Nov 2026 (AW, phone answer-quality run): "eight to fourteen sentences" and a required "Why it matters" made
        // the phone model pad its answer with sentences from passages about other problems (delayed cord clamping for
        // a newborn with pus spots, walking barefoot for diarrhoea, "bleeding or infections" as the reason for headache
        // with blurred vision). On the phone the length now follows the material on this question; the cloud keeps
        // its full shape.
        if (!cloud) return head.replace("cover every point the material gives on this question ", "cover every point the material gives on THIS question ") + core +
            "Shape the answer in labelled parts, each part on its own lines starting with its label and a colon: " +
            "${l[0]} what it is and the most important action, in two to three sentences; " +
            // 12 Nov 2026 (AX): "a short complete answer" alone lost the IFA dose and the Hb recheck (pale breathless
            // pregnant woman): every step, dose and referral rule of the material on this question is kept.
            "give every step, dose, number and referral rule the material gives on this question; " +
            "${l[1]} one sentence, only when the material says why (otherwise skip this part); " +
            "${l[2]} the numbered steps, or one line per item, only from the material (skip when it gives none); " +
            "${l[3]} the referral conditions, only when the material gives them (otherwise skip this part); " +
            "${l[4]} only the mistakes the material warns about (skip when it gives none). " +
            "Use only the passages about this problem and this person; never add a sentence from a passage about something else. " +
            "Stop when the material on this question is covered: a short complete answer is better than a long one. " +
            "The app already shows one quoted line above your answer, so do not just repeat that line."
        val shape = "Shape the answer in labelled parts, each part on its own lines starting with its label and a colon: " +
            "${l[0]} with the direct answer in two to three sentences; " +
            "${l[1]} with one to two sentences on why it matters; " +
            "${l[2]} with the numbered steps, or one line per item, only from the material " +
            "(skip this part when the material gives no steps or items); " +
            "${l[3]} with the referral conditions, only when the material gives them " +
            "(otherwise skip this part); " +
            "${l[4]} with the mistakes the material warns about (skip when it gives none). " +
            "Write $total sentences in total when the material supports it; " +
            "never a single sentence when the material has more. "
        return head + core + shape + "The app already shows one quoted line above your answer, so do not just repeat that line."
    }

    /**
     * Item 4 "More detail": a fuller answer from the SAME passages (no new search), up to
     * the model ceiling. PROCEDURE-style: every step, item, condition and limit, each on its
     * own line, in the same labelled parts the card renders (Part B3 adds the mistakes part).
     */
    fun detailLine(query: String = ""): String {
        // 8 Nov 2026: only the labels of the question's language. Listing both made Gemma copy the
        // Hindi labels ("उततर:", misspelled) into an English answer, which then did not parse.
        val hindi = query.isNotBlank() && ArogyaBudget.langOf(query) == "hi"
        val l = if (hindi) listOf("'उत्तर:'", "'क्यों ज़रूरी:'", "'क्या करें:'", "'ANM को कब बताएँ:'", "'आम गलतियाँ:'")
        else listOf("'Answer:'", "'Why it matters:'", "'What to do:'", "'When to tell the ANM:'", "'Common mistakes to avoid:'")
        return "Give a fuller answer from the same material: every step, item, condition and limit it gives " +
            "on this question, each on its own numbered or bulleted line; do not merge steps and do not stop early. " +
            "Shape it in labelled parts, each part on its own lines starting with its label and a colon: " +
            "${l[0]} with the full direct answer (five or more sentences when the material supports it); " +
            "${l[1]} with why it matters; " +
            "${l[2]} with the numbered steps, or one line per item, only from the material; " +
            "${l[3]} with one line, only when the material gives referral conditions; " +
            "${l[4]} with the mistakes the material warns about (skip when it gives none). " +
            "The app already shows one quoted line above your answer, so do not just repeat that line."
    }
}
