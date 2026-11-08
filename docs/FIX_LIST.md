# ArogyaDoot Fix List (started 8 Nov 2026)

Single list of every known issue. Status: Open / Fixed (not built) / Built / Verified on phone.
Details: `docs/ANSWER_FAILURE_RCA_20261108.md`, `docs/REFINEMENT_BACKLOG.md`.

## A. Answer quality (current focus)
| ID | Issue | Status |
|---|---|---|
| A1 | Answers must be clearly sectioned: **Answer · Why it matters · What to do · When to tell ANM · Common mistakes to avoid**. Cause: same prompt/formatter as Pramaan, but Pramaan ran Llama 3.2 3B (labels on their own lines); Gemma writes labels mid-line, and the system prompt said "no heading or label" (contradicting the shape line). Fix: prompt no longer contradicts the labels; formatter recognises mid-line labels; Hindi 'ANM को कब बताएँ' spelling recognised | Fixed (not built) |
| A11 | Phone answers capped at ~600 tokens (size estimate + 600 ceiling), cutting "When to tell the ANM" / "Common mistakes". Fix: phone Ask ceilings = cloud (1200/1600/2400) and Ask uses the full ceiling, not the estimate (`ArogyaFlags.askNoCap`) | Fixed (not built) |
| A12 | "What to do: 1." – step number split from its text, step deleted, bare number left | Fixed (not built) |
| A13 | Waiting phase said "Writing…" during the search, nothing moving | Fixed (not built): staged labels, pulsing placeholder, blinking cursor |
| A15 | Streaming loop guard never fired for sentence-length loops (window 512 chars, 8 copies needed; a 65–96 char sentence could not fit) – Gemma looped 37× for 60 s (13:19 run). Fix: units ≥ 24 chars trip after 4 copies; window 1024 | Fixed (not built) |
| A16 | A loop caught after generation (repetition check) still discarded the whole draft. Fix: cut at the first copy of the repeating unit (even mid-copy), keep the sentences before it, drop a dangling citation | Fixed (not built) |
| A17 | "…what should I do?" classified as FACT ("what it is, its role" shape, shortest answer) → 6-sentence answer. Fix: action asks (what should I do / what to do / क्या करें / kya karein) → URGENT shape (action first, steps, referral) | Fixed (not built) |
| A18 | Gemma garbles a digit next to °F ("103°F" → "1031°F", "99.5" → "999.5"); guide text is clean. Number check correctly removes those sentences | Model behaviour – check is correct |
| A19 | **Root cause candidate for garbled digits, loops and run-to-run variation**: KB answers request temperature 0 (greedy); llama.cpp handled it, but LiteRT-LM (Gemma, since 6 Nov) got temperature 0 with topK 40 / topP 0.95 (division by zero in the sampler). 16:37 draft: "5000 mg", "385 °C", "10101 °F" (guide: 500 mg, 38.5 °C, 101 °F). Fix: temperature ≤ 0 → topK 1, topP 1, temperature 1 (pure greedy) | Fixed (not built) – `ArogyaFlags.liteRtGreedyTopK1`, log `ANDROLLM_SAMPLER` |
| A14 | No note when the check removes lines from the answer | Open (not surgical: needs data through 4 layers) |
| A2 | Trimmer treats a normal answer as a loop and cuts ~1/3 (2375 → 1623 chars), losing structure | Fixed (not built) |
| A3 | Flipped "do not" reaches the user ("cover with a blanket") | Fixed (not built) – narrow guard |
| A4 | Baby question answered from adult fever passage; age-aware search existed but was never wired | Fixed (not built) |
| A5 | Gemma loop → whole draft discarded, 60 s regeneration | Fixed (not built) |
| A6 | Sentence cut at "e. g." ("fever e.") | Fixed (not built) |
| A7 | False "Not covered in the selected knowledge base: 'What should I do'" | Fixed (not built) |
| A8 | Full ASHA safety checker (`ArogyaClinicalGrounding`: polarity, age group, medicines) never runs on offline answers | Open |
| A9 | Chat history carries a broken earlier answer into the next prompt | Open |
| A10 | Number check flagged `999.5` | Not a bug: the number was in Gemma's draft (garbled 99.5 °F); the check was right |

## B. Speed
| ID | Issue | Status |
|---|---|---|
| B1 | Offline answer 55–122 s; first words ~37 s | Open |
| B2 | Re-rank 10–18 s offline | Open |

## C. Stability
| ID | Issue | Status |
|---|---|---|
| C1 | Crash: voice foreground service not stopped at Android 15 time limit (7 Nov 12:33) | Fixed (not built) |
| C2 | Log flooding pushes the app's own lines out of logcat | Open |

## D. Mic
| ID | Issue | Status |
|---|---|---|
| D1 | Urdu script on Hindi speech | Built |
| D2 | Claude-style recording bar, phrase-by-phrase, warm model | Built |
| D3 | Unused 252 MB English ASR model in APK | Built (moved out) |
| D4 | Accuracy comparison (Whisper base vs Android offline vs larger Whisper) | Open |

## E. Submission / repo
| ID | Issue | Status |
|---|---|---|
| E1 | Phone check of `PHONE_AUDIT.md` | Open |
| E2 | Evidence table: cloud benchmark via Groq | Open |
| E3 | Leftover Meta/Muse/SAM/Llama strings and dead code | Open |
| E4 | Repo root tidy (`_archive/`) | Open |
| E5 | Clean clone cannot rebuild (git-ignored assets): release APK + asset link in README | Open |
| E6 | Clinical Lead named in README and decks | Open |
| E7 | Title truncated "Ask the" | Open |

## F. Question understanding and content deletion (8 Nov, after the full audit)
| ID | Issue | Fix | Status |
|---|---|---|---|
| F1 | Spelling fixed only for one search lane; question type, age search, prompt used raw typos ("havong h9gh") | `ArogyaQuestionNormalizer`: one cleaned question used by question type, age search, an extra search view and the prompt (original kept) | Fixed (not built) |
| F2 | Digit-in-word typos never fixed ("h9gh") | Keyboard-neighbour / look-alike digit fix, only to a guide word | Fixed (not built) |
| F3 | SMS spellings ("wat shud", "bby", "cn", "giv") not understood | Curated SMS map | Fixed (not built) |
| F4 | Hinglish dictionary 51 words, exact match only; common symptoms missing (ulti, khansi, piliya, sardi, kamzori…) | ~110 Hinglish health words + phrases (tez bukhar, khoon ki kami, tez saans) + spelling variants (bukhaar/bukar, bache/bachhe, ulty, khasi) | Fixed (not built) |
| F5 | Corrector could turn names or Hindi words into rare guide words ("sunita" → "sanita") | Correct only to words in 10+ guide passages; never change guide words; Hindi-only words detected separately from English ("to", "do") | Fixed (not built) |
| F6 | Rewriter examples from another knowledge base (room rent, office leave); "What should be given to a child with fever?" copied for fever questions → dose passages | ASHA examples that keep the intent + rule "what should I do stays what to do" | Fixed (not built) |
| F7 | KB_PROOF deleted every sentence scoring < −3 (paraphrased "Why it matters") → 3-line answers | Low score alone no longer deletes; wrong numbers / opposite meaning still do (`ArogyaFlags.proofDeleteLowScore`) | Fixed (not built) |
| F8 | Number salvage deleted the whole sentence and flattened the answer into one paragraph | Removes only the bracket or clause with the wrong number; keeps lines, sections and steps | Fixed (not built) |
| F9 | Guide vocabulary for the corrector | `assets/guide/vocab_en.txt` (12,283 words, from the guide DB), loaded at app start (`AROGYA_QUESTION_VOCAB`) | Added |
Tests: `tools/host-tests/QuestionNormalizerTest.kt` (14 typo fixes, 20 unchanged questions, Hinglish, question type, age view, Hinglish views).
| F10 | "don't" split into "don" + "t" and "don" corrected | Words with apostrophes kept whole | Fixed (not built) |
| F11 | "weight" matched the how-to word "weigh" (weight questions forced into steps); Hinglish "jhatke" not urgent | " weigh " / "weighing"; jhatke/jhatka urgent | Fixed (not built) |

### 50-question offline check (`tools/question_eval/eval.py`, keyword search over all 5,389 guide passages)
Relevant passage in the top 5: raw question 29/50 → cleaned question + English terms 48/50. Question type correct 24/24.
Number salvage on the real garbled drafts: bracket "(1031°F)" removed and sentence kept; fully garbled sentence removed; bad clause removed.
Not checked offline: Gemma's answer text (needs the phone: `tools/answer_test.ps1`).

## G. Answer completeness and source-only answers (8 Nov, Gemma-only gap analysis)
Pramaan comparison: answer pipeline code identical (prompt, budgets, search, checks, proof); only the model differs (Pramaan: Llama 3.2 3B on llama.cpp; ArogyaDoot: Gemma 3n on LiteRT-LM).
| ID | Gap | Fix | Status |
|---|---|---|---|
| G1 | Prompt told the phone model "six to ten sentences" (cloud: eight to fourteen) – Gemma stops early whatever the budget | Phone asks for eight to fourteen sentences, like the cloud | Fixed (not built) |
| G2 | Each guide passage cut to 1,100 chars around its best sentence – lists of signs/steps cut in half | 1,500 chars per passage, still 6 passages (~9,000 chars, inside the 8K guide context) | Fixed (not built) |
| G3 | Not-in-guide questions: the Ask screen's R8 rule replaced any answer with "not in your guides – ask your ANM" | Reversed on request: the general (or web) answer is shown under "⚠ Not from your ASHA guides … Tap ☁ Check online to verify, or ask your ANM before acting" + closest guide topics; Check online is a filled, coloured button for these answers (`ArogyaNotFromGuide`, `ArogyaFlags.r8LabelledAnswer`, `kbGeneralAnswer = true`). No answer at all → ANM message as before | Fixed (not built) |
| G4 | Output budget | Confirmed raised: phone 1,200 / 1,600 / 2,400 tokens, Ask uses the full ceiling | Done |
| G5 | Re-rank 12–24 s offline; candidate pool limited by the 4,000-char phone cap | Left as is (more candidates = slower re-rank) | Known |
| G6 | Streamed draft replaced after the check without saying why | UI note needs data through 4 layers | Known (A14) |
| G7 | Online guide answers clamped to 1,280 output tokens (lists/"More detail" budgets 1,600/2,400 never applied; Hindi answers ~2.5x tokens cut) | Ceiling 4,096 (`GroqChatProvider.MAX_KB_OUTPUT_TOKENS`) | Fixed (not built) |

### Online vs offline (8 Nov assessment)
| | Offline (phone) | Online (Check online / Cloud first) |
|---|---|---|
| Model | Gemma 3n E2B (LiteRT-LM) | Qwen 3.8 27B on Groq (`qwen/qwen3.8-27b`) |
| Guide text read | 6 passages x 1,500 chars | up to 16 passages x 1,600 chars |
| Answer length | 1,200 / 1,600 / 2,400 tokens, "eight to fourteen sentences" | same budgets, ceiling 4,096 (was 1,280) |
| Checks | numbers, coverage, "do not" guard, proof (no deletion of paraphrase) | same checks |
| Not in guide | general answer, labelled, Check online highlighted | live web search → official page (tier 2) or labelled web answer |
| Time | first words ~40–55 s, full 1.5–2.5 min | first words ≤20 s, total ≤120 s |
| Tested on phone today | yes | **no** (every run was Local first, `mode=NONE`) |

## H. Code audit before build (8 Nov, 18:00)
| ID | Finding | Fix | Status |
|---|---|---|---|
| H1 | **Bug**: step-number clean-up stripped any 1–2 digit number ending a line ("less than 60." → "less than") in every answer | Only a piece that is nothing but "1." is dropped | Fixed (not built) + test |
| H2 | **Bug**: "do not" guard could delete correct lines listed under "Common mistakes to avoid" | Lines in that section are never removed | Fixed (not built) + test |
| H3 | Inline numbered steps ("What to do: 1. … 2. …") shown as one run-on line | Formatter starts each step on its own line | Fixed (not built) + test |
| H4 | Not compiled since 16:40 (normalizer, salvage, labelled not-in-guide, Check online button, online ceiling) | Host tests + Gradle build will tell | Open |
| H5 | **Online gap**: "Check online" answers were accepted with no checks at all (`cloud_verified`): no number check, no "do not" guard, no repair | Same checks as Cloud first; wrong number / swapped term repaired like a phone draft; other objections logged, answer still shown (`ArogyaFlags.checkOnlineChecks`, log `POCKET_RAG_CHECK_ONLINE_GATE`) | Fixed (not built) |
| H6 | **Online gap**: "Check online" prompt had no five-part shape / length line (only Cloud first had it) | Same shape and "eight to fourteen sentences" line | Fixed (not built) |
| H7 | **Online gap**: "do not" guard and cleaned question applied only to phone answers | Guard runs on every online answer; `<question_cleaned>` given to the online model | Fixed (not built) |

## I. AI features audit (8 Nov, 18:10) – all features outside Ask
| ID | Feature | Gap | Severity | Surgical fix |
|---|---|---|---|---|
| I1 | Ask (in a visit) | `visitContext()` (visit's age: "newborn, 7 days old") is never used: search and answer ignore the patient's age from the visit record | High (safety) | Add the visit's age group as an extra search view (like the age view), not to the question text |
| I2 | Ask + attached PDF/Word/slides | `pauseKbForAttachedDocument` switches the guide off: answer from document + model, not the guides, with no label; comments contradict | High | Keep the guide on; use the document lines as extra evidence, or label "from your document, not the guide" |
| I3 | Ask + attached photo | Photo facts for search need the retired Meta vision stack; Gemma's photo description is not used for the search (only OCR text) | Medium | Use the Gemma description (`localVisualContext`) in `kbAttachedPhotoQuery` |
| I4 | Family explanation (patient-facing) | Only numbers are checked; no "do not" flip check, no check that medicine names come from the answer | High (safety) | Run `dropFlippedProhibitions` + a medicine-name grounding filter on the output |
| I5 | Guided form fill (phone) | Requests 2,000 output tokens without the budget check (4,096 context): long ANC forms can overflow / cut | Medium | Route through `ArogyaBudget.tokens` like every other feature |
| I6 | Guided form fill (online) | Whole form limited to 900 output tokens: long forms cut, rows lost | Medium | 2,000–4,096 |
| I7 | Card/register reading by Gemma vision | A transcription that hits the 700-token limit is discarded entirely (`completed` required) | Medium | Keep the partial text when stopped by the limit; larger limit for registers |
| I8 | Demo: card "read online" | Always "Qwen3 could not read more" (card photos never leave the phone by design) – looks like a failure in the demo | Medium (demo) | Hide for cards or say "card photos stay on the phone (privacy)" |
| I9 | All features (router) | Phone output that fails to parse = feature fails; no single retry | Low–Medium | One retry with "Return only the JSON" |
| I10 | Organise notes | A reply cut mid-JSON fails entirely instead of keeping the complete items | Low | Close the array at the last complete item |
| I11 | Labels | "Muse Spark", "Meta key", "Together AI key", "Muse Glimmer" in route/form "why online wasn't used" messages and comments | Low | Same as E3 |
| I12 | All features | One shared lock: a 2-minute Ask blocks card reading, stock, forms until it ends | Low | Known; UI shows busy |
Well-designed (no change needed): organise notes (labels the ASHA's own sentences only), ANM message (identifiers tokenised, facts verified), refresher (answer must be in the passage), card/person/medicine/stock extraction (rules first, every value grounded, split re-read), Finish visit fields (grounded + rule backstop), photo privacy (card/register/person photos never leave the phone).

### Section I fixes (8 Nov, 18:25) – not built
| ID | Fix |
|---|---|
| I11 | Visible leftover labels removed: "no Together AI key saved for Muse Glimmer" / "no Meta key saved" (route), "Muse Spark 1.3 (Meta) • online" (Ask + AI-used panel), "Together AI / Meta key" (form fill), "Meta · Muse Spark" (old key → "Unsupported key – add a Groq key"). Internal names, tags and unreachable SAM code left as is |
| I10 | Organise notes: a reply cut mid-JSON keeps every complete item (`CAPTURE_PARSE_REPAIRED`) + test |
| I5/I6 | Form fill: phone up to 2,400 output tokens clamped to the model's active window after the prompt (Hindi counted); online 3,000 (was 900); both x2.5 for Hindi via ArogyaBudget |
| I3 | Ask + photo: the photo is described for the guide search by Groq vision online (Check online / Cloud first, "Photos online" on) or Gemma on the phone otherwise (`KB_PHOTO_DESCRIBE`); replaces the retired Meta vision facts |
| I2 | Ask + document: "📘 Use ASHA guide too" switch under the attached file (on by default). On: guide stays on, document lines join the search (`KB_KEPT_WITH_ATTACHED_DOCUMENT`). Off: answered from the document, labelled "📄 From your document, not the ASHA guides" |
Still open from section I: I1 (visit age), I4 (family explanation checks), I7 (vision transcription cut), I8 (demo card online), I9 (parse retry), I12 (shared lock).

## Verification (8 Nov, 20:00)
- `assembleDebug`: BUILD SUCCESSFUL (3 old warnings only).
- Host tests: 60/61 files pass; TableOcrTest 74/76 = the same 2 historical failures recorded on 6 Nov (`setup-links`: models link now the R2 mirror; `narrow-register-columns-not-merged`: real register bug, open – see J1). No new failures.
- One label change reverted (ArogyaCloudOutcome legacy Muse label) to keep `cloud-not-used-label` passing; unreachable in the app.
- Pending: phone test (`tools/answer_test.ps1`, photo + PDF toggle).

| ID | Open item | Note |
|---|---|---|
| J1 | Register photo with tightly packed columns returns nobody (`narrow-register-columns-not-merged`) | Pre-existing (before 6 Nov); fix before relying on register imports |

## K. Pre-existing bugs fixed + token limits (8 Nov, 20:30) – not built
| ID | Bug / limit | Fix |
|---|---|---|
| J1 | Narrow register: "S.No" and "Name" headings merged, serial "1" joined the name, person dropped (`narrow-register-columns-not-merged`, failing since before 6 Nov) | A serial-number heading never merges with the next heading; a leading serial number is stripped from a name cell |
| – | `setup-links` test pinned the old models link (R2 mirror is primary since 6 Nov; HF is the fallback) | Test checks the fallback link (app unchanged) |
| I1 | Visit's age ("newborn, 7 days old") never used by Ask | Added as an extra search view (`AROGYA_VISIT_AGE`), not for questions about the mother; reset after each turn |
| I4 | Family explanation: only numbers checked | + no medicine the guide answer does not name, + no reversed "do not" (`AROGYA_FAMILY_GUARD`) |
| I7 | (audit corrected: partial vision text is kept) Gemma vision ran at temperature 0.8 | Greedy (0) for describe / transcribe / read / count |
| I8 | Demo "read card online" looked like a failure | Says card photos stay on the phone for privacy |
| I9 | Phone reply unusable → feature failed | One retry with "reply again in exactly the format asked" (`AROGYA_ROUTE_RETRY`) |
| Tokens | Limits too small for the task | Card 900→1,600 (online 2,400); finish visit 900→1,400 (2,400); voice stock 400→800 (1,500); person 900→1,200 (2,000); organise notes 700→1,000 (2,000); medicine 400→600 (1,200); ANM 450→600 (1,200); quiz 200→400; Gemma vision copy 700/900→1,600, describe 320→480; general (not-in-guide) answer 700→1,000 (Hindi 1,800). Router clamps to the model's real window (8,192), Hindi prompt counted |
Left as is: I12 (one shared lock – architectural).

## Verification (8 Nov, 21:35)
- Full host-test suite (`run.ps1`): all 61 test files pass, including TableOcrTest 76/76 (both historical failures fixed).
- `assembleDebug`: BUILD SUCCESSFUL (old deprecation warnings only).
- Pending: phone test (phone not connected at 21:35).
