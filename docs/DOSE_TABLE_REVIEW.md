# Guide dose table — for ANM / clinician review

**Status: NOT YET REVIEWED.** These rows were transcribed on 9 Nov 2026 from the guides installed in the app
(`assets/guide/ASHA_Health_Guide_v2.db`), with document and page. The app uses them only to add a caution line
when an answer's dose differs from the row for the child's age (or for a pregnant / delivered woman). It never
edits or hides an answer. Please tick each row (☐ → ☑) or write the correction next to it.

Code: `app/src/main/java/com/example/arfas/ArogyaDoseTable.kt` (switch: `ArogyaFlags.doseTable`).
Tests: `tools/host-tests/DoseTableTest.kt`.

## How the check works
- Medicines checked: zinc, paracetamol (tablet / syrup), albendazole, vitamin A, IFA. ORS volumes are **not** checked.
- Age comes from the question ("12 months old", "2 years old", "4 महीने के") or, if the question has none, from the visit record. Months count as 30 days, so 12 months = 1 year.
- Amounts read from the answer: mg, ml, teaspoons (¼ = 1.25 ml, ½ = 2.5 ml, 1 = 5 ml), fractions of a tablet (¼, ½, 1, 2 — English and Hindi words), IU / lakh. Devanagari digits are read.
- "¼ tablet of paracetamol 500 mg": with a fraction, 500 mg is read as the tablet strength, not the dose.
- Weight bands are shown in the caution text but the check uses age only.
- No age in the question or the visit → no check.

## Rows
| # | Medicine | Form | Age / group | Guide dose (shown in the caution) | Source | Guides differ | ANM ✓ |
|---|---|---|---|---|---|---|---|
| 1 | zinc | any | 2 mo – <6 mo | 10 mg (½ tablet of 20 mg) once a day for 14 days | Integrated ASHA Module Part C p23; Part B p133; HBYC Handbook p72 |  | ☐ |
| 2 | zinc | any | 6 mo – 5 y | 20 mg (1 tablet) once a day for 14 days | Integrated ASHA Module Part C p23; Part B p133; HBYC Handbook p72 |  | ☐ |
| 3 | paracetamol | tablet | 2 mo – <3 y | ¼ tablet of 500 mg (2 months–3 years, 4–14 kg), at most 4 times a day | HBYC Handbook p92; Induction Module p138 |  | ☐ |
| 4 | paracetamol | tablet | 3 y – <8 y | ½ tablet of 500 mg (3–8 years), 3–4 times a day | HBYC Handbook p92; Induction Module p138 |  | ☐ |
| 5 | paracetamol | tablet | 8 y – <12 y | 1 tablet of 500 mg (8–12 years), 3–4 times a day | Induction Module p138 |  | ☐ |
| 6 | paracetamol | tablet | 12 y – ∞ | 1–2 tablets of 500 mg (over 12 years / adults), 3–4 times a day | Induction Module p137; Integrated ASHA Module Part C p17 |  | ☐ |
| 7 | paracetamol | syrup | 0 mo – 28 d | 1 ml (newborn under 3 kg) | HBYC Handbook p92 | Induction Module p138 gives 1.25 ml (¼ teaspoon) | ☐ |
| 8 | paracetamol | syrup | <1 mo – <1 y | 2.5 ml (½ teaspoon) (under 1 year, 3–8 kg) | HBYC Handbook p92; Induction Module p138 |  | ☐ |
| 9 | paracetamol | syrup | 1 y – <3 y | 5 ml (1 teaspoon) (1–3 years, 8–14 kg) | HBYC Handbook p92; Induction Module p138 |  | ☐ |
| 10 | paracetamol | syrup | 3 y – 5 y | 7.5 ml (1½ teaspoons) (over 3 years, over 14 kg) | Induction Module p138 |  | ☐ |
| 11 | albendazole | any | 0 mo – <1 y | not to be given under 1 year | ASHA Module 7 p81 |  | ☐ |
| 12 | albendazole | any | 1 y – <2 y | 200 mg (½ tablet of 400 mg), once | Anemia Mukt Bharat Guidelines p77; ASHA Module 7 p81 |  | ☐ |
| 13 | albendazole | any | 2 y – ∞ | 400 mg (1 tablet), once | Anemia Mukt Bharat Guidelines p77; ASHA Module 7 p81 |  | ☐ |
| 14 | albendazole | any | pregnant | 400 mg (1 tablet) once, after the first trimester | Anemia Mukt Bharat Guidelines p77 |  | ☐ |
| 15 | vitamin_a | any | 9 mo – <16 mo | 1 ml (1 lakh IU) at 9 months | Immunization Handbook p34 |  | ☐ |
| 16 | vitamin_a | any | 16 mo – 5 y | 2 ml (2 lakh IU) every 6 months from 16 months to 5 years | Immunization Handbook p34 |  | ☐ |
| 17 | ifa | any | 6 mo – <5 y | IFA syrup 1 ml twice a week (prophylaxis); for anaemia 1–2 ml daily by weight (6–12 months 1 ml, 1–3 years 1.5 ml, 3–5 years 2 ml) | Anemia Mukt Bharat Guidelines p47, p72 |  | ☐ |
| 18 | ifa | any | 5 y – <10 y | 1 pink IFA tablet (45 mg iron) once a week; for anaemia once a day for 3 months | Anemia Mukt Bharat Guidelines p48, p70 |  | ☐ |
| 19 | ifa | any | 10 y – <20 y | 1 blue IFA tablet (60 mg iron) once a week | Anemia Mukt Bharat Guidelines p48 |  | ☐ |
| 20 | ifa | any | pregnant | 1 red IFA tablet (60 mg iron + 500 mcg folic acid) daily for 180 days from the second trimester | Anemia Mukt Bharat Guidelines p50 |  | ☐ |
| 21 | ifa | any | postpartum | 1 red IFA tablet (60 mg iron) daily for 180 days after delivery | Anemia Mukt Bharat Guidelines p50 |  | ☐ |

## Points the reviewer should decide
1. **Row 7 – paracetamol syrup, newborn under 3 kg:** HBYC Handbook p92 says **1 ml**; Induction Module p138 says **1.25 ml (¼ tsp)**. Both are accepted now and the caution names both. Other guides treat fever in a newborn as a danger sign for referral — should this row exist at all?
2. **Row 8 – 3–8 kg band:** HBYC p92 writes "<1 year (>3kg–8kg)"; Induction p138 writes ">1 year (>3kg–8kg)" (likely a typo). The row follows HBYC.
3. **Row 12 – albendazole 1–2 years:** the Anemia Mukt Bharat table on p77 is flattened in the database (rows run together); the 200 mg value is read together with ASHA Module 7 p81 ("1–2 years: 400 mg, ½ tablet").
4. **Row 17 – IFA syrup 6–59 months:** prophylaxis (1 ml twice a week) and treatment (1–2 ml daily by weight) are both accepted; frequency is not checked.
5. **Zinc under 2 months, paracetamol tablet under 2 months:** no row (the guides give none) — such answers are not checked.
