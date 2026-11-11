import com.example.arfas.ArogyaClinicalText
import com.example.arfas.KbReplyText
var af = 0; var ap = 0
fun a(c: Boolean, m: String, d: () -> String = { "" }) { if (c) { ap++; println("PASS $m") } else { af++; println("FAIL $m ${d()}") } }
fun main() {
    // 1. Loop: keep the answer written before the loop (8 Nov phone log: "[S4]. Never spong with cold water" x8).
    val good = "Give the first dose of paracetamol syrup and refer the baby to the PHC today [S1]. " +
        "Keep the baby warm and continue breastfeeding often [S2]. Watch for danger signs such as fits or not feeding [S3]. "
    val looped = good + "Sponge with tepid water [S4]. Never spong with cold water" + " [S4]. Never spong with cold water".repeat(8)
    val kept = KbReplyText.keepBeforeLoop(looped)
    a(kept != null && kept.startsWith("Give the first dose") && kept.contains("danger signs"), "loop: the answer before the loop is kept") { kept.toString() }
    a(kept != null && Regex("Never spong").findAll(kept).count() <= 1, "loop: the repeated phrase is not kept more than once") { kept.toString() }
    a(kept != null && kept.trimEnd().last() in ".!?।", "loop: ends on a whole sentence") { kept.toString() }
    a(KbReplyText.keepBeforeLoop(" [S4]. Never spong with cold water".repeat(10)) == null, "loop: only-loop text is rejected (regenerate as before)")
    a(KbReplyText.keepBeforeLoop("Refer now [S1]." + " refer now".repeat(9)) == null, "loop: too little left is rejected")

    // 2. Sentence splitting no longer cuts at "e. g."
    val eg = "Several serious illnesses may be connected with fever e. g. malaria, typhoid and pneumonia. Refer early."
    a(KbReplyText.removeRepeatedSentences(eg) == eg, "split: 'e. g.' stays inside its sentence") { KbReplyText.removeRepeatedSentences(eg) }
    val ie = "Give ORS i. e. oral rehydration salts after every loose stool. Continue feeding."
    a(KbReplyText.removeRepeatedSentences(ie) == ie, "split: 'i. e.' stays inside its sentence") { KbReplyText.removeRepeatedSentences(ie) }
    // Old guarantees of the trimmer (KbPhase14RegressionTest cases) still hold.
    val sellerLoop = "The Seller Type tab displays breakup of sellers based on orders received. Other tabs show bids. " +
        "Seller Type tab displays breakup of sellers based on orders received. Seller Type displays breakup breakup sellers based on orders received. " +
        "Seller Type breakup breakup sellers based on orders received."
    a(KbReplyText.removeRepeatedSentences(sellerLoop) == "The Seller Type tab displays breakup of sellers based on orders received. Other tabs show bids.",
        "split: near-duplicate loop sentences still removed") { KbReplyText.removeRepeatedSentences(sellerLoop) }
    a(KbReplyText.removeRepeatedSentences("GeM is the portal portal for buyers. यह पोर्टल है है।") == "GeM is the portal for buyers. यह पोर्टल है।", "split: stutter collapse unchanged")
    val steps = "1. Login to GeM.\n2. Click Create Bid.\n3. Select the category.\n4. Publish the bid."
    a(KbReplyText.removeRepeatedSentences(steps) == steps, "split: numbered steps unchanged")
    val parallel = "1. Click on the Submit button to proceed further.\n2. Click on the Cancel button to proceed further."
    a(KbReplyText.removeRepeatedSentences(parallel) == parallel, "split: parallel steps unchanged")
    val distinct = "Open tender is advertised to all bidders. Limited tender is sent to selected bidders only. Single tender is sent to one bidder."
    a(KbReplyText.removeRepeatedSentences(distinct) == distinct, "split: distinct sentences unchanged")

    // 3. Generic asks are never "not covered".
    for (q in listOf("what should I do", "What should I do?", "what to do", "how should we manage", "what should the ASHA do now", "kya karein", "क्या करें", "अब क्या करना चाहिए"))
        a(KbReplyText.isGenericAsk(q), "generic ask: '$q'")
    for (q in listOf("how to make ORS at home", "what is the dose of paracetamol", "baby is having high fever", "बुखार में क्या दें"))
        a(!KbReplyText.isGenericAsk(q), "real topic kept: '$q'")

    // 4. Age-group search view.
    val baby = ArogyaClinicalText.ageViews("The baby is having high fever, what should I do?")
    a(baby.size == 1 && baby[0].contains("young infant") && baby[0].contains("fever"), "age: baby question searches young infant + fever") { baby.toString() }
    val nb = ArogyaClinicalText.ageViews("7 day old newborn has fever")
    a(nb.size == 1 && nb[0].contains("newborn") && nb[0].contains("young infant"), "age: newborn question") { nb.toString() }
    val hi = ArogyaClinicalText.ageViews("बच्चे को बुखार है क्या करें")
    a(hi.size == 1 && hi[0].contains("child"), "age: Hindi child question searches child") { hi.toString() }
    a(ArogyaClinicalText.ageViews("How do I make ORS at home?").isEmpty(), "age: no child named -> no extra search")
    a(ArogyaClinicalText.ageViews("pregnant woman has high BP").isEmpty(), "age: adult question -> no extra search")

    // 5. A structured answer that restates its key action is not a loop (8 Nov: 2375 -> 1623 chars).
    val structured = "Give the first dose of paracetamol and refer the baby to the hospital immediately [S1].\n" +
        "Why it matters: high fever in a baby can be a sign of serious illness [S1].\n" +
        "What to do: give the first dose of paracetamol and refer the baby to the hospital immediately [S1].\n" +
        "Summary: give the first dose of paracetamol and refer the baby to hospital immediately."
    a(KbReplyText.removeRepeatedSentences(structured).contains("Why it matters") &&
        KbReplyText.removeRepeatedSentences(structured).length >= structured.length * 0.6, "loop: a restated key action is not treated as a loop") { KbReplyText.removeRepeatedSentences(structured) }

    // 6. Flipped prohibition ("Do not cover with a blanket" -> "cover with a blanket").
    val ev = "Sponge the whole body with tepid water. Do not use cold water as it causes shivers. Do not cover with a blanket. " +
        "Keep windows open and give enough water and fluids to drink."
    val bad = "Give the first dose of paracetamol and refer [S1]. To manage the fever, sponge the whole body with tepid water to avoid shivering and cover with a blanket [S1]. Give enough water and fluids [S1]."
    val (fixed, n) = KbReplyText.dropFlippedProhibitions(bad, ev)
    a(n == 1 && !fixed.contains("cover with a blanket") && fixed.contains("Give enough water") && fixed.contains("paracetamol"), "flip: 'cover with a blanket' removed, rest kept") { "$n $fixed" }
    val okAns = "Sponge with tepid water [S1]. Do not use cold water [S1]. Do not cover the baby with a blanket [S1]."
    a(KbReplyText.dropFlippedProhibitions(okAns, ev).second == 0, "flip: correct 'do not' sentences are kept")
    a(KbReplyText.dropFlippedProhibitions("Use cold water to sponge [S1].", ev).second == 1, "flip: 'use cold water' removed")
    val warm = "Keep the baby warm: cover the baby with a blanket and a cap. Do not cover with a blanket when the baby has high fever."
    a(KbReplyText.dropFlippedProhibitions("Cover the baby with a blanket [S1].", warm).second == 0, "flip: action also stated positively in the guide is not used")
    a(KbReplyText.dropFlippedProhibitions("Refer the baby today.", "Refer early.").second == 0, "flip: no prohibitions -> unchanged")

    // 7. Sections: labels written mid-line by Gemma still become headings.
    val gemma = "Answer: Give the first dose of paracetamol and refer [S1]. Why it matters: high fever can mean serious illness [S1]. " +
        "What to do: sponge with tepid water [S1]. When to tell the ANM: if the baby has fits [S2]. Common mistakes to avoid: do not use cold water [S1]."
    val heads = com.example.arfas.ArogyaAnswerFormat.sections(gemma).map { it.heading }
    a(heads == listOf("Answer", "Why it matters", "What to do", "When to tell the ANM", "Common mistakes to avoid"), "sections: mid-line labels become headings") { heads.toString() }
    val llama = "Answer:\nDanger signs include fast breathing [S1].\nWhy it matters:\nTimely care saves lives.\nWhat to do:\n1. Refer now."
    a(com.example.arfas.ArogyaAnswerFormat.sections(llama).map { it.heading } == listOf("Answer", "Why it matters", "What to do"), "sections: Llama's own-line labels unchanged")
    val prose = "If the baby does not feed, ask what to do next with the ANM. Keep the baby warm."
    a(com.example.arfas.ArogyaAnswerFormat.sections(prose).size == 1, "sections: 'what to do' without a colon is not a heading")
    val hiAnm = com.example.arfas.ArogyaAnswerFormat.sections("उत्तर: बच्चे को तुरंत रेफर करें। ANM को कब बताएँ: झटके आने पर।").map { it.heading }
    a(hiAnm == listOf("उत्तर", "ANM को कब बताएं"), "sections: Hindi 'ANM को कब बताएँ' (the prompt's spelling) is a heading") { hiAnm.toString() }

    // 8. Step numbers stay with their text; a bare number is never left behind ("What to do: 1.").
    val stepsInline = "What to do: 1. Sponge the whole body with tepid water [S1]. 2. Give enough fluids to drink [S1]."
    a(KbReplyText.removeRepeatedSentences(stepsInline) == stepsInline, "steps: inline numbered steps unchanged") { KbReplyText.removeRepeatedSentences(stepsInline) }
    val loopWithStep = ("Sponge the whole body with tepid water and refer the baby today [S1]. ").repeat(4) + "\nWhat to do: 1. Sponge the whole body with tepid water and refer the baby today [S1]."
    val cleanedSteps = KbReplyText.removeRepeatedSentences(loopWithStep)
    a(!Regex("(?:^|\\s)\\d[.)]\\s*$", RegexOption.MULTILINE).containsMatchIn(cleanedSteps), "steps: no orphan step number left") { cleanedSteps }

    // 9. Streaming loop guard: a sentence-length unit (65+ chars) is caught after 4 copies (8 Nov: ran to 37).
    val unit = "[S4]. Checking baby's fever regularly helps you know when to refer the baby. "
    val g = com.example.arfas.AndroGenerationLoopGuard()
    var fedChars = 0; var tripped = false
    val stream = "High fever in a baby needs care. Sponge with tepid water [S1]. " + unit.repeat(12)
    for (piece in stream.chunked(5)) { fedChars += piece.length; if (g.feed(piece)) { tripped = true; break } }
    a(tripped && fedChars < 70 + unit.length * 6, "guard: long repeated sentence stopped within ~5 copies") { "tripped=$tripped at $fedChars chars" }
    val g2 = com.example.arfas.AndroGenerationLoopGuard()
    val fivePart = "Answer: Give the first dose of paracetamol syrup and refer the baby today [S1].\nWhy it matters: fever in a young baby can be a sign of serious infection [S2].\n" +
        "What to do:\n1. Sponge the whole body with tepid water [S1].\n2. Keep the baby breastfeeding often [S2].\n3. Do not cover the baby with a blanket [S1].\n" +
        "When to tell the ANM: if the baby has fits, cannot feed, or the fever lasts more than seven days [S3].\nCommon mistakes to avoid: do not use cold water [S1]."
    a(fivePart.chunked(4).none { g2.feed(it) }, "guard: a normal five-part answer never trips it")
    val g3 = com.example.arfas.AndroGenerationLoopGuard()
    a((1..60).map { "ha " }.joinToString("").chunked(3).any { g3.feed(it) }, "guard: short-unit loops still caught")

    // 10. Loop that stops in the middle of a copy is cut cleanly after the first copy.
    val midCopy = "Give the first dose of paracetamol syrup and refer the baby to the PHC today [S1]. Keep the baby warm and breastfeed often [S2]. " +
        "Sponge the whole body with tepid water and keep the windows open [S1]. " +
        unit.repeat(9) + "[S4]. Checking baby's fev"
    val keptMid = KbReplyText.keepBeforeLoop(midCopy)
    a(keptMid != null && keptMid.startsWith("Give the first dose") && Regex("Checking baby").findAll(keptMid).count() <= 1 && !keptMid.endsWith("fev") && !keptMid.endsWith("[S4]."),
        "loop: a loop ending mid-copy is cut after the first copy") { keptMid.toString() }

    // 11. "What should I do" gets the action shape, not "what is".
    val QT = com.example.arfas.ArogyaQuestionType
    a(QT.kindOf("The baby is having high fever, what should I do?") == com.example.arfas.ArogyaQuestionType.Kind.URGENT, "kind: 'what should I do' -> URGENT")
    a(QT.kindOf("बच्चे को बुखार है क्या करें") == com.example.arfas.ArogyaQuestionType.Kind.URGENT, "kind: Hindi 'क्या करें' -> URGENT")
    a(QT.kindOf("How should a mother prepare ORS at home?") == com.example.arfas.ArogyaQuestionType.Kind.PROCEDURE, "kind: how-to stays PROCEDURE")
    a(QT.kindOf("What is ORS?") == com.example.arfas.ArogyaQuestionType.Kind.FACT, "kind: 'what is' stays FACT")
    a(QT.kindOf("What are the danger signs in a newborn?") == com.example.arfas.ArogyaQuestionType.Kind.LIST, "kind: danger signs stays LIST")

    // 12. Audit fixes: a sentence ending in a number keeps it; the "do not" guard leaves the mistakes list alone.
    val endsInNumber = "Fast breathing in a young infant means a breathing rate of 60 or more per minute. Count for one full minute and the rate should be less than 60."
    a(KbReplyText.removeRepeatedSentences(endsInNumber) == endsInNumber, "audit: a sentence ending in '60.' keeps its number") { KbReplyText.removeRepeatedSentences(endsInNumber) }
    val withMistakes = "Answer: Sponge the baby with tepid water and refer [S1].\nCommon mistakes to avoid:\n- Cover the baby with a blanket [S1].\n- Use cold water [S1]."
    val (keptMistakes, nm) = KbReplyText.dropFlippedProhibitions(withMistakes, ev)
    a(nm == 0 && keptMistakes == withMistakes, "audit: lines under 'Common mistakes to avoid' are never removed") { "$nm $keptMistakes" }
    val outsideMistakes = "Answer: Sponge the baby and cover with a blanket [S1].\nCommon mistakes to avoid:\n- Use cold water [S1]."
    a(KbReplyText.dropFlippedProhibitions(outsideMistakes, ev).second == 1, "audit: a flipped rule outside the mistakes list is still removed")

    // 13. Inline numbered steps become their own lines inside "What to do".
    val stepsSec = com.example.arfas.ArogyaAnswerFormat.sections("Answer: Refer today [S1]. What to do: 1. Sponge with tepid water [S1]. 2. Give enough fluids [S2]. 3. Keep breastfeeding [S2].")
    val wtd = stepsSec.firstOrNull { it.heading == "What to do" }?.body.orEmpty()
    a(wtd.lines().size == 3 && wtd.lines()[1].startsWith("2.") && wtd.lines()[2].startsWith("3."), "format: inline steps on their own lines") { wtd }
    val tempText = com.example.arfas.ArogyaAnswerFormat.sections("Answer: Fever above 39.5 C is high fever. Sponge the baby.")
    a(tempText.size == 1 && tempText[0].body.lines().size == 1, "format: decimals and sentences are not split as steps") { tempText.toString() }

    // 14. Organise notes: a reply cut mid-JSON keeps its complete items; every sentence is still kept.
    val notesText = "Baby is warm to touch. Mother says the baby is feeding well. I advised to keep the baby wrapped."
    val cutReply = "{\"items\":[{\"id\":0,\"category\":\"Observation\"},{\"id\":1,\"category\":\"Caregiver report\"},{\"id\":2,\"cat"
    val capture = com.example.arfas.ArogyaCapture.parse(cutReply, notesText)
    a(capture != null && capture.size == 3 && capture[0].category == "Observation" && capture[1].category == "Caregiver report" &&
        capture[2].category == "Unsorted observation", "organise notes: cut reply keeps complete items") { capture.toString() }
    a(com.example.arfas.ArogyaCapture.parse("{\"items\":[{\"id\":0,\"category\":\"Observation\"}]}", notesText)?.get(0)?.category == "Observation", "organise notes: normal reply unchanged")

    // 15. The visit's age group joins the search for child questions, never for questions about the mother.
    val va = ArogyaClinicalText.ageViewsWithVisit("Fever since yesterday, what should I do?", "newborn, 7 days old")
    a(va.size == 1 && va[0].contains("newborn") && va[0].contains("young infant") && va[0].contains("fever"), "visit age: newborn visit adds the newborn view") { va.toString() }
    a(ArogyaClinicalText.ageViewsWithVisit("Mother has bleeding after delivery", "newborn, 7 days old").isEmpty(), "visit age: a question about the mother gets no child view")
    a(ArogyaClinicalText.ageViewsWithVisit("How do I make ORS at home?", "").isEmpty(), "visit age: no visit, no change")
    val vc = ArogyaClinicalText.ageViewsWithVisit("Not eating well", "child, 14 months old")
    a(vc.size == 1 && vc[0].contains("child"), "visit age: child visit adds the child view") { vc.toString() }

    // 16. Family explanation: no medicine the guide answer does not name; no reversed "do not".
    val guideAns = "Give ORS after every loose stool and continue breastfeeding. Refer to the doctors at the PHC if there is blood in the stool."
    val fam = "Give your baby ORS after every loose stool. Also give amoxicillin syrup twice a day. Keep breastfeeding often."
    val famSafe = com.example.arfas.ArogyaAiExtras.dropUngroundedMedicines(fam, guideAns)
    a(!famSafe.contains("amoxicillin") && famSafe.contains("ORS") && famSafe.contains("breastfeeding"), "family: a medicine not in the guide answer is dropped") { famSafe }
    a(com.example.arfas.ArogyaAiExtras.dropUngroundedMedicines("Give zinc for 14 days.", "Refer to the doctors.").isEmpty(), "family: 'doctors' does not count as ORS/zinc support")
    a(com.example.arfas.ArogyaAiExtras.dropUngroundedMedicines("The temperature drops at night. Keep the baby warm.", "Keep the baby warm.").contains("drops"), "family: ordinary words are not medicines")
    a(KbReplyText.dropFlippedProhibitions("Cover the baby with a blanket.", ev).second == 1, "family: reversed 'do not' removed")

    // 17. More detail: only the question's language labels; misspelled Hindi label still a heading; "[S1S1]" cleaned.
    val QT2 = com.example.arfas.ArogyaQuestionType
    val dEn = QT2.detailLine("What are the danger signs in a newborn?")
    a("'Answer:'" in dEn && "उत्तर" !in dEn, "detail line: English question gets English labels only") { dEn }
    val dHi = QT2.detailLine("नवजात शिशु के खतरे के लक्षण क्या हैं?")
    a("'उत्तर:'" in dHi && "'Answer:'" !in dHi, "detail line: Hindi question gets Hindi labels only") { dHi }
    val mis = com.example.arfas.ArogyaAnswerFormat.sections("उततर: Danger signs in a newborn include fast breathing.\nWhy it matters: early care saves lives.")
    a(mis.size == 2 && mis[0].heading == "उत्तर" && mis[0].body.startsWith("Danger signs"), "format: misspelled 'उततर:' is still a heading") { mis.toString() }
    val cites = com.example.arfas.ArogyaAnswerFormat.stripCitationMarkers("Convulsions [S1S1]. Fever [S1 S2]. Cold [S1][S2]. In [2024] it changed.")
    a(!cites.contains("[S") && cites.contains("[2024]"), "citations: run-together ids removed, a bracketed year kept") { cites }

    println(if (af == 0) "ALL $ap PASS" else "$af FAILED, $ap passed")
    if (af > 0) kotlin.system.exitProcess(1)
}


