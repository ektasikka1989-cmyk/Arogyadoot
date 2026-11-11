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

## L. From the 22:39 phone screenshot (8 Nov) – not built
| ID | Problem | Fix |
|---|---|---|
| L1 | "More detail" listed English AND Hindi labels: Gemma wrote Hindi "उततर:" (misspelled) in an English answer, no headings | detailLine(query) gives only the question's language labels; formatter accepts a Hindi label without the virama ("उततर:" → उत्तर) |
| L2 | "[S1S1]" shown raw | Citation numbering and stripping accept run-together / space-separated ids ("[S1S1]", "[S1 S2]"); "[2024]" kept |
| L3 | Follow-up combined search used the raw follow-up text: Hinglish/misspelled follow-ups lost their meaning there | Cleaned reading + English terms join the follow-up search; follow-up decision rules unchanged |
| L4 | "More detail" answer distorted | Open – needs run6_log.txt (same passages by design; distortion cause not yet known) |
| L4 | "More detail" gave nearly the same answer: cached passages + greedy decoding = same output | Fresh search for More detail (up to 8 passages within the evidence budget, visit age, full candidate pool, cleaned question) + language-specific detail labels; cache path kept behind `ArogyaFlags.detailFreshSearch`. The phone's 8K window limits evidence to ~9,900 chars, so detail adds about one passage; Check online reads up to 16 |
| L5 | (found while fixing L4) with fresh search, `detailHit` would have skipped the guide search entirely (empty packet → "not found") | `detailHit` only when the cache path is on; detail packets are not cached |

## M. Tested in Claude's environment (8 Nov, 23:30)
Android Studio's own Kotlin compiler (read from the laptop) run under Java 11 in Claude's workspace:
- App sources used by the host tests compile cleanly (66 files).
- **All 62 host-test files pass** (61 existing + new `PipelineSimTest`).
- `PipelineSimTest` runs real-style Gemma drafts through the phone answer chain (trim → dedupe → "do not" guard → citation numbering → sections) and found two real bugs, now fixed:
  - M1: the "do not" guard removed the whole sentence, including a "What to do:" label and correct advice → now removes only the flipped clause, keeps the label and citation.
  - M2: a numbered step restating the answer's key action was de-duplicated, emptying "What to do" → numbered steps are not de-duplicated outside a real loop.
- Not testable here: Android/UI code (needs Gradle on the laptop), Gemma's actual output (needs the phone).

## N. Check online (9 Nov, 00:40) – not built
User report: the online answer streamed without citations, then "failed" and the phone's answer appeared.
| ID | Cause (found by running the real engine/resolver in Claude's environment) | Fix |
|---|---|---|
| N1 | **Pre-existing**: the online prompt is passages first, then the question, the phone answer to check and all instructions; GroqChatProvider cut it to its first 8,000 chars → Qwen never saw the question or instructions (uncited text) | Resolver fits the prompt by dropping the least relevant passages (≤16,000 chars and ≤4,400 tokens); never cut the end; Groq safety limit 24,000 chars (`CLOUD_PROMPT_FITTED`, `KB_PROMPT_CUT`) |
| N2 | Groq free tier for qwen/qwen3.8-27b: 8,000 tokens/minute, counted as input + max_tokens; Check online read ~24K-token context and tonight's 4,096 max_tokens → request refused → silent fallback to the phone | Check online context 24,000 → 10,000 tokens; max_tokens = what fits under 7,600 after the input (≈2,650 left), Hindi counted at ~1.4 chars/token (`KB_REQUEST_TOKENS`); Hindi phone draft sent shorter (1,600 chars) |
| N3 | A Check online that ended on the phone put the phone answer in the Online tab | Ask flow raises "The online check could not finish — <reason>. The offline answer above is unchanged." (`AROGYA_CHECK_ONLINE_FAILED`) |
Verified here: real engine with a fake online model accepts clean / wrong-number / flipped answers (only the bad part removed); real resolver: question + instructions + five-part line always arrive, input + answer = 7,600 ≤ 8,000 for English, Hindi and short questions (tools/claude_sim/). Developer tier (higher limits) can raise `REQUEST_TOKEN_BUDGET` and `MAX_PROMPT_*`.

## O. Only one source cited on offline answers (9 Nov, 01:00) – not built
| ID | Cause | Fix |
|---|---|---|
| O1 | Sources = only the [S#] markers Gemma wrote (verified); Gemma writes [S1] after every sentence by habit, so content taken from S2/S3 was never credited | `citedSourceIds`: verified markers + passages the answer clearly used (same content test as the no-marker fallback) |
| O2 | Each sentence showed the number Gemma wrote, even when it came from another passage | `reattributeCitations` (offline answers): a sentence's marker moves to the passage it clearly matches (≥2 distinctive items and ≥2 more than its cited passage) |
Switch: `ArogyaFlags.citeAllSupportingSources`. Verified here (tools/claude_sim/check_online/CiteTest.kt): Gemma-style "[S1] everywhere" answer → sources S1, S2, S3; unrelated retrieved passage not credited; one-passage answer still one source; per-sentence numbers 1, 2, 2, 3; Check online engine simulation unchanged.
Note: passages from the same document still share one number (by design).

## P. Independent audit of ChatGPT's Plan A (9 Nov, 11:30) – not built
Plan A (A3–A7) was NOT applied: its PocketRagEngine.kt does not compile, A3 made the "loose motion, 12 months" evidence worse (milestone pages #1) and inflated re-ranker scores (switching off the rewrite < 2.0 and weak-evidence < 1.0 safety nets), A6/A7 withheld correct answers while passing "20 mg zinc for a 4-month-old". Audit doc: "ArogyaDoot Plan A (A3–A7) Independent Audit". Only the sound parts were kept, re-done on the current code:
| ID | Cause (found by running the real retrieval engine, pack and ONNX models) | Fix |
|---|---|---|
| P1 | **Root cause of the irrelevant offline answer**: "loose motion" appears 0× in the pack ("diarrhoea" 215×); the English → guide-word view existed only for Hindi/Hinglish. Top 6 were milestone/swaddling pages, no ORS/zinc | `ArogyaHindiTerms.englishViews`: English lay words (loose motion(s), stomach/tummy pain, throwing up, BP) add ONE search view with the guide word. Retrieval: all 5 lay-word questions now get ORS/zinc, postpartum abdominal pain, blood-pressure passages in the top 6; 12 other questions byte-identical. Switch `ArogyaFlags.englishLayTerms` |
| P2 | Cite-to-every-source fallback when no sentence matched (Hindi answers) | Removed; a sentence with no word overlap keeps the engine-checked [n] marker it was written with (`guideSegments(text, sources, turn.text)`); nothing is withheld |
| P3 | Copied guide sentences (extractive stand-in for a failed answer) were saved as the assistant's answer and steered follow-ups | Still shown (labelled), no longer recorded in session memory |
| P4 | Dose check treated tablet strength as a dose ("1/2 tab of 20 mg tablet" next to "infants" → MATCH); only ran when the QUESTION asked a dose; Devanagari digits in evidence missed | `ArogyaDoseCheck.strengthRisk` + caution line; also runs when only the answer gives a dose; evidence digits normalised. Advisory only — never withholds |
| P5 | **Check online answer "vanished"** (user report): the card's tab was `remember(result){0}`, so the merged online result re-opened on Offline | Card opens on the Online tab when an online answer is present |
Tested here: 62 host tests (Batch2 63/63, Tier 44/44 incl. new checks), real PocketRagEngine compiled + Check online sim 6/6 accepted (incl. Hindi and paraphrase). Not compiled here (needs Gradle): ArogyaVisitAi, ArogyaSopChat, GenericKnowledgeContextProvider. Originals: _work/tmp/backup_before_9nov_fixes/.
Not done (owner decision): honouring the pack's `arogya_chunk_review` exclude flags — tested, mixed (removes good HBNC job-aid / ASHA NCD pages flagged only for a missing URL).

## Q. Multi-symptom evidence and guide dose table (9 Nov, 13:00) – not built
| ID | Cause (found on the real engine, pack and ONNX models) | Fix |
|---|---|---|
| Q1 | Multi-symptom questions: one search; the 4 passage windows the model reads could miss a symptom or use the wrong person's passage ("Vomiting During Pregnancy" for a baby's vomiting; no fever passage for "bacche ko dast aur bukhar") | `ArogyaConcerns` + `withSymptomEvidence` in the context provider: the normal evidence is built first; only a symptom NOT covered by any window (named in its opening or twice, and about the right person) gets one short search ("child vomiting vomits everything management", pool 8, own re-ranker question, used only if best ≥ 1.0); its passages follow the normal ones and its best fact is put first so it gets a window. Switch `ArogyaFlags.perConcernSearch` |
| Q2 | Dose check could not tell whether a dose is right for the age | `ArogyaDoseTable`: 21 rows (zinc, paracetamol tablet/syrup, albendazole, vitamin A, IFA) copied from the guides with page; the answer's amount (mg, ml, tsp, ½/¼ tablet, IU, Hindi words/digits) is compared with the row for the question's (or visit's) age / pregnancy; a mismatch adds "⚠ Guide dose of Zinc for this age: 20 mg (1 tablet)… (Integrated ASHA Module Part C p23…). The answer says 10 mg — confirm with your ANM." Never withholds. Guides that disagree (paracetamol syrup newborn 1 ml vs 1.25 ml) are both accepted and named. Switch `ArogyaFlags.doseTable`. **Rows need ANM sign-off: docs/DOSE_TABLE_REVIEW.md** |
Tested here: retrieval on 31 questions (real engine/pack/models, app order) — 3 multi-symptom questions get the missing symptom's passage, 11 multi-symptom and all 17 single-symptom questions read byte-identical evidence; extra cost only when a symptom is missing (15–29 re-ranker pairs, ~7–15 s on the phone). ConcernsTest 27/27, DoseTableTest 46/46, full host suite unchanged. Not compiled here (needs Gradle): GenericKnowledgeContextProvider, ArogyaVisitAi.
Still open: "बच्चे को दस्त और बुखार है" reads only fever-heavy windows (a hand-washing window that names diarrhoea twice counts as covering it); pregnancy danger-sign question unchanged (its symptom searches scored below 1.0); ORS volumes and frequencies (daily vs weekly) are not checked.

## R. 9 Nov 2026 — understanding by the on-device model, no word lists

Asked: "The complete test has to pass without any static keyword list. Gemma has good capabilities; the code must use them."

What changed (retrieval only — no answer text, prompt, token limit or DB change):

| # | Change | File | Switch |
|---|--------|------|--------|
| R1 | Gemma restates **every** question first (spelling, Hinglish, Hindi, everyday words; one SEARCH line per problem). The literal question is still searched too. If Gemma is not available the app behaves as before. | GenericKnowledgeContextProvider | `ArogyaFlags.rewriteEveryQuestion` |
| R2 | When Gemma's rewrite succeeds, the search uses **only** the question + Gemma's lines. The word-list views (Hindi dictionary terms, English lay terms, spelling fixes) are used only when Gemma gives nothing. | GenericKnowledgeContextProvider | — |
| R3 | Sentence picking weighs words by how rare they are in the **installed guide** (computed from the pack on the phone, rebuilt when the pack changes — so tomorrow's KB upgrade needs no code change). | GenericKbKeyFacts, KbCorpusStatistics, GenericKnowledgePackRepository | `ArogyaFlags.guideWordWeights` |
| R4 | Several problems: facts are taken per Gemma line, in turns (problem 1, problem 2, problem 1 …). A line's facts must contain that line's own problem word (rarest word in the guide, ignoring wording the lines share). | GenericKnowledgeContextProvider, GenericKbKeyFacts (`requireFocus`, `siblings`) | — |
| R5 | A Gemma line whose problem word is in none of the evidence windows gets more evidence: first from lower-ranked passages of the main search, else one short search of that line (with all lines as one extra view). Its best passage is added after the normal ones. Nothing changes when every problem is already covered. | ArogyaConcerns (rewritten: no symptom list), GenericKnowledgeContextProvider | `ArogyaFlags.perConcernSearch` |
| R6 | Switched **off** (they are fixed lists): English lay-term list (`englishLayTerms=false`), typed-in dose table check (`doseTable=false`; code kept, switch on after ANM review if wanted). The old symptom list in ArogyaConcerns is removed. | ArogyaFlags, ArogyaConcerns | — |
| R7 | Bug: Hinglish spelling fixer turned "phula" (swollen) into "piliya" (jaundice). Now only fixes words within 2 letters. | ArogyaQuestionNormalizer | — |
| R8 | Gemma is not asked twice for the same question when the first rewrite fails. | GenericKnowledgeContextProvider | — |

Results (laptop-equivalent harness: real guide DB, real E5 + reranker, every expected problem must be in the passages the model reads):

| Set | Before today | Now (no word lists) |
|-----|--------------|---------------------|
| 50 new questions (multi-symptom, misspelt, English, Hindi, Hinglish) | 41/50 | **50/50** |
| 23 earlier questions | 21/23 | **23/23** |

Honest caveats:
- Gemma's restatements in the test were written by me in the exact format of the rewrite prompt. The phone's Gemma may phrase them differently. Check `KB_QUESTION_REWRITE`, `KB_PARTS`, `KB_PART_SEARCH` in logcat on the phone.
- Cost: one Gemma rewrite per question (~2–3 s measured earlier) + at most 3 short searches when a problem is missing.
- The test checks the evidence the model reads, not the final wording of the answer.
- After the KB upgrade: re-run the 50 + 23 questions.

Tests: ConcernsTest rewritten (20 checks, no word lists); Batch2Test +2 (phula/bukhaar). Full host suite run on the final code.

## S. 9 Nov 2026 — follow-up questions with the on-device model

Problem found (caused by R1, "rewrite every question"): Gemma rewrote a follow-up without knowing the earlier
question. "aur kitne din dena hai?" became "For how many days should the medicine be given?", which matches
any topic's "how many days" passage (IFA, KMC …). Test: 9/15 follow-ups had the right evidence.

Fix (no word lists):

| # | Change | File |
|---|--------|------|
| S1 | Gemma is given the earlier question (the resolved chain for 2nd, 3rd follow-ups) and asked to write the complete question when the new one depends on it, and to ignore it for a new topic. One worked example added. First questions get the same prompt as before. | GenericKbQuestionRewrite, LocalModelManager, FixSightRuntimeGraph, GenericKnowledgeContextProvider |
| S2 | A follow-up rewrite that names the earlier subject ("how long?" → "… zinc …") is no longer thrown away as a "copied example". | GenericKbQuestionRewrite |
| S3 | The app recognises that Gemma resolved a follow-up: its lines contain a word of the earlier question (or its rewrite) that the new question lacks and that is specific in the installed guide (in at most 1 of 20 passages). Then the answer is told it follows up, and the extra contextual search is skipped. Otherwise the old evidence-based follow-up logic runs unchanged. | GenericKbKeyFacts.carriesOver, GenericKnowledgeContextProvider |

Results (harness, real guide, 15 follow-ups in English/Hindi/Hinglish after a first question):

| | Right evidence |
|---|---|
| Gemma without the earlier question (before S) | 9/15 |
| Gemma with the earlier question (now) | **15/15** |
| Recognised as follow-up (S3) | 11/15 (the other 4 still have the right evidence; they use the old path) |
| 50 + 23 earlier questions | 50/50, 23/23 (no change) |

Caveats: follow-up rewrites in the test were written by me in the prompt's format; check `KB_QUESTION_REWRITE followUp=true`
and `KB_FOLLOW_UP rewritten=true` on the phone. A new topic right after a question depends on Gemma ignoring the earlier one
(the prompt says so; the literal question is still searched too). Test data: tools/question_eval/followup_set_15.tsv.
New host test: FollowUpTest (12 checks).

## T. 9 Nov 2026 — phone test: "baby high fever and loose motion" answered "not in the guide" (online)

Seen on the phone: the answer came from the cloud (the "Groq answers when there is internet" switch is on) and the
evidence was newborn cord-clamping pages. Reproduced in the harness: without a model rewrite, "loose motion" and "baby"
pull newborn pages; with the rewrite ("child has high fever" / "child has diarrhoea") the diarrhoea and fever pages come.
So the rewrite did not happen on the phone (online mode).

| # | Change | File |
|---|--------|------|
| T1 | Online answers chosen and the cloud reachable: the **cloud model** does the guide-search rewrite (the question goes to the cloud anyway); the phone model if that fails. Offline first: the phone model only, nothing is sent. | GroqChatProvider.rewriteKbSearchQuestion, AndroBaselineRuntime.rewriteKbQuestion, FixSightRuntimeGraph |
| T2 | When no model could rewrite, a log line says so (`KB_QUESTION_REWRITE_SKIPPED`). | AndroBaselineRuntime |
| T3 | Safety net: English lay terms back on, used **only** when no model rewrote the question (the normal path never uses them). | ArogyaFlags.englishLayTerms |

Phone check: logcat `KB_QUESTION_REWRITE` (cloud=true online, or the phone model's line offline) and `KB_QUESTION_REWRITE_SKIPPED`.

## U. 9 Nov 2026 — phone test 2: unrelated second question, "not covered" sections, stale screen, history

| # | Seen on the phone | Cause | Fix |
|---|---|---|---|
| U1 | A new, unrelated question after another was answered about the first one ("fever … not breastfeeding at two years" — the fever came from the earlier question) and said "not covered" | S1 showed the earlier question to the rewriting model for **every** question; the model merged the topics | The earlier question is shown only when the question may depend on it (≤4 words, or the app's existing context classifier says so). The model also answers **FOLLOWS: YES/NO**; NO → no contextual search with the earlier question, and a rewrite that still carried words over is redone alone. (GenericKbQuestionRewrite, GenericKnowledgeContextProvider) |
| U2 | Sections "What to do / When to tell the ANM / Common mistakes" said "This part is not covered" | Not a format change: the answer format is the same; the evidence was wrong (U1, and no rewrite online — T1). Harness: with the right rewrite the same question gets "Managing fever", feeding and danger-sign passages | U1 + T1 |
| U3 | "Token size changed?" | Not today. Offline limits unchanged. Online (Groq free tier 8K tokens/minute) was set on 8 Nov (N2): Check-online context 10K tokens, answer = what fits under 7,600 | No change |
| U4 | Ask SOP showed the old answer after coming back | The thread was kept and restored on reopen | Ask SOP opens clean with a new conversation. (ArogyaVisitScreen) |
| U5 | Wanted the last 10 questions | — | ☰ in the Ask SOP top bar opens a side panel: last 10 questions (newest first, time, Offline/Online), tap to show the answer again, "New conversation", "Clear history". Saved on the phone. (ArogyaSopHistory, ArogyaSopHistoryStore, ArogyaSopChat.SopHistoryDrawer, ArogyaVisitScreen) |

Tests: FollowUpTest 17 checks (FOLLOWS parsing/prompt), SopHistoryTest 8 checks (last 10, replace same question, round trip, Hindi, bad data). Compose/Android code needs the Gradle build.

## V. 9 Nov 2026 — phone log: "How do I make ORS at home?" → copied guide sentences after 132 s

Log (18:21): rewrite 1.7 s → search 24 s (re-ranker 23.4 s) → Gemma's answer 54 s, good, rejected "number 50 not in the
guide" → number clean-up then rejected "term changed" → second answer 40 s, same rejection → copied guide sentences.

| # | Root cause | Fix | File |
|---|---|---|---|
| V1 | Offline evidence capped at **4,000 chars** (old speed setting) cut the guide passage at "For one glass (200"; the rest ("one litre of water with **50 gm** of sugar and **5 gm** of salt") never reached Gemma, so its correct recipe numbers looked invented | Cap **9,000** (user: quality over speed; fits Gemma's 8K window: ~1,700 prompt + ~2,500 evidence + answer) | GenericKnowledgeContextProvider |
| V2 | A cut window ending in a digit was treated as a complete sentence | Only an uncut window may end on a digit | GenericKbKeyFacts.completeSentences |
| V3 | Number clean-up and term clean-up were not chained: after one, the other check failed and the whole answer was thrown away | `chainedSalvage`: both clean-ups in turn (only remove offending sentences), full gate at the end; first draft and after the retry | PocketRagEngine |
| V4 | Gemma typed "\n" as two characters; "CALC: NONE" was glued onto the search question | Parse treats a literal \n as a line break | GenericKbQuestionRewrite |
| V5 | Re-ranker 23.4 s for 27 pairs | To check on the next log (the polluted question in V4 made it longer; Gemma had just run) | — |

Replay test (real engine + real gate, tools/claude_sim/engine/OrsReplayTest.kt): with the full passage Gemma's answer is
kept on the first try; with the old cut passage the bad sentence is removed and the rest kept (no copied sentences);
literal "\n" parsed. 50/23/25/15 question sets unchanged (113/113 evidence).

## W. 9 Nov 2026 — AI audit gaps 2–5 (surgical)

| Gap | Fix | File |
|---|---|---|
| 2 Online requests ignored Groq's 8,000 tokens/minute (Hindi forms asked 7,500 + prompt → refused; a request within a minute of an answer → refused → quiet phone fallback) | Output capped to what fits under 7,600 with the prompt; one shared per-minute count (answers included); a used-up minute is waited out up to 20 s (`AROGYA_GROQ_MINUTE_WAIT`); Qwen thinking turned off for visit-AI calls (`reasoning_effort: none`, as every other Groq call already had) | ArogyaGroqMinute (new), ArogyaVisitAi.route/fillForm, GroqChatProvider (records usage), ArogyaModelClient |
| 3 Every visit-AI cloud call had 25 s in total | Replies over 1,500 tokens (long forms, notes) get 60 s (`AROGYA_CLOUD_BUDGET … deadlineS=`) | ArogyaVisitAi |
| 4 More detail had about half a normal answer's room on the phone; family explanation cut at 8 sentences with escalation last | Real token counts (English 3.6, Devanagari 1.4 chars/token + 1,700 prompt + 256 margin) → phone room: normal English 1,600 / Hindi ~3,450, More detail English 2,400 / Hindi ~3,450 (was ~1,550); More detail never below the normal answer; on the phone More detail reads the same 9,000-char cap; family: escalation first, 6–10 sentences, keeps 10 | PocketRagEngine.outputBudget, ArogyaVisitAi.moreDetail, ArogyaAiExtras.familyPrompt |
| 5 Copied-guide fallback looked like nonsense | Label: "The AI could not write a checked answer, so here are the exact lines from the guide:" (Hindi too); family explanation of such an answer reads plain guide text (no header, bullets or [S1]) | PocketRagEngine, AndroBaselineRuntime, ArogyaAiExtras.plainGuideText, ArogyaVisitAi |

Not done (on hold): gap 1 (cloud identifiers, after the hackathon); online "More detail" cut at Groq's limit is still not detected.
Tests: BudgetGapsTest 18 checks + OrsReplayTest 6 (real engine, tools/claude_sim/engine), AnswerFormatTest updated; 66/66 host test files.

## X. 9 Nov 2026 — phone 19:43: "ORS not available" answer said "wait 1 minute" (guide: 10 minutes)

| Root cause | Fix | File |
|---|---|---|
| The number check only looked at numbers of 2+ digits and only asked "does this number appear anywhere in the evidence": "1 minute" was never checked; "10" anywhere would also have let "10 hours" through | A number written with a time / volume / weight / spoon unit must appear with the same unit in the evidence or the question ("10 minutes" ok, "1 minute" not; "one litre", "24 hrs", "5gm"/"5 grams" understood). A rejected sentence is removed by the clean-up, the rest of the answer kept | PocketRagQualityGate.unsupportedNumericClaims / unitQuantities |

Not confirmed (no app log): why the final answer left out the guide's recipe numbers (1 litre, 8 tea spoons sugar, 1 tea spoon salt). The gate accepts every phrasing of them (tested); the guide passage is a flattened table ("1 glass of water 1 litre of water 1 tea spoon of sugar 8 tea spoons…"), likely too garbled for Gemma; the "removed and rewritten" is the first draft rejected and a second one written. The installed build (com.arogyadoot.app) wrote no app log lines; a debug build is needed to see the rejection reason.
Tests: OrsReplayTest 13 checks (unit cases + replay), BudgetGapsTest 18.

## Y. 9 Nov 2026 — a wrong number is repaired from the guide, not regenerated

| Problem | Fix | File |
|---|---|---|
| X's unit check rejected "wait 1 minute", but the clean-up only knew 2-digit numbers, so it could not remove that sentence → a second full generation (~40–60 s, "answer removed and rewritten") | Clean-up uses the same unit check | PocketRagQualityGate.salvageUnsupportedNumericSentences |
| Removing a wrong-number sentence lost that step | The sentence is replaced by the guide's own sentence on the same thing (same unit, ≥ half the words shared), cited: "If the child vomits, wait for 10 minutes and then give ORS more slowly. [S2]"; dropped only when no such guide sentence exists | PocketRagQualityGate.guideReplacement |

Replay (real engine): wrong-minute draft → one generation only, final answer has the guide's 10 minutes, recipe and steps kept.
Tests: OrsReplayTest 18 checks.

## Z. 9 Nov 2026 — phone log 20:19 (debug build): what happened, and replay logging

Log: rewrite 1.9 s → search 26.3 s (re-ranker 25.4 s, 27 pairs) → Gemma 54.6 s → rejected "50 not in evidence" → chained clean-up accepted (one generation, V3 works) → proof check 37 s → 121 s total.
- The draft's "50 gm sugar … 5 gm salt" is in the guide (Module 7 p27 / Induction p94) but not in the 6 passages the phone read (3,943 chars): the check removed it correctly for the evidence it had. Correction to V1: the 4,000 cap was not what cut it (evidence was under 4,000 both times).
- Added logs to replay phone answers: `KB_EVIDENCE_WINDOWS` (each passage read: source, size, end), full `POCKET_RAG_REJECTED_DRAFT`, `POCKET_RAG_ACCEPTED_TEXT`.
- Open: re-ranker ~0.9 s per pair (search 25 s, proof 37 s; the proof's 12 s limit does not stop it).

## AA. 9 Nov 2026 — numbers confirmed against the whole guide; re-ranker speed

| Problem (phone log 20:19) | Fix | File |
|---|---|---|
| Gemma wrote the guide's recipe ("one litre of water with 50 gm of sugar and 5 gm of salt", Module 7 p27 / Induction p94), but that page was not among the 6 passages it read, so the number check removed it | A sentence with a number the passages lack is looked up in the whole guide (keyword index, milliseconds). A guide passage holding all its numbers with their units and ≥ half its words confirms it: the passage joins the evidence as a new source, the sentence cites it, the full check runs again. A number the guide does not state anywhere is still replaced or removed | GenericKnowledgePackRepository.lexicalPassages, GenericKnowledgeContextProvider.guidePassagesFor, PocketRagEngine.confirmFromGuide / guideConfirmedAnswer, PocketRagModels (extraEvidence), AndroBaselineRuntime (lookup wired; proof reads the confirming passage) |
| Re-ranker 1.3 s per pair (search 25 s; proof check) | Full ONNX graph optimisation (same model and scores; the start-up parity cases still guard it); `RERANKER_READY threads= opt=all`; `KB_PROOF_MS` logs the proof check time | GenericKbReranker, AndroBaselineRuntime |

Replay (real engine, every guide chunk as the index): recipe kept with the new source, one generation; "70 gm" (not in the guide) not confirmed; without the lookup unchanged. Tests: GuideConfirmTest 8, OrsReplayTest 18, BudgetGapsTest 18.

## AB. 9 Nov 2026 — number confirmation narrowed (user's doubt)

AA confirmed a number against the whole guide: too wide (a dose-table row for another age or medicine, or a passage on another topic, could "confirm" a wrong number). Now: only the other passages the search found for this same question (not given to Gemma) can confirm, and medicine doses (mg) are never confirmed this way — a dose must be in the passages Gemma read. Repository lookup from AA removed. Tests: GuideConfirmTest 10 (dose never confirmed; the 50 gm page is among the question's results).

## AC. 9 Nov 2026 — rotating Ask SOP questions, guide rebuilt with overlapping passages

| # | Change | File |
|---|--------|------|
| AC1 | Ask SOP shows 4 different questions each time it opens (and on "↻ Other questions"), from the 148 questions already tested against the guide (English, Hindi, Hinglish, misspelt, several symptoms, recipes). At least one Hindi-script and one Latin-script question; the next set never repeats the last. "Ask about this guide" topics still win when present. Pool is plain text, one question per line. | ArogyaSopExamples (new), ArogyaSopChat.SopEmptyState, assets/sop/example_questions.txt |
| AC2 | Guide rebuilt with overlapping passages: each passage now also holds the neighbouring sentence from the passage before and after it in the same document (≤220 / ≤260 chars); a sentence cut at a passage boundary is completed. Same schema, passage ids, tables, user_version 2; embeddings recomputed (E5 "passage: "). File: kb_rebuild/ASHA_Health_Guide_v2.db (not yet installed; the bundled asset is unchanged). | tools/kb_rebuild/build_overlap.py |

Results (harness, 163 questions: 50 + 23 + 25 + 15 follow-ups + 50 recipe/Hindi/Hinglish):

| Guide | Pass | Mean best rerank score |
|-------|------|------|
| Current | 162/163 (miss: khichdi/dalia) | 3.53 |
| Only cut sentences completed | 162/163 (same miss) | 3.48 |
| Full overlap (delivered) | 162/163 (miss: pregnancy swelling + headache) | 3.76 |

Caveats: passages are 17% longer on average (990 → 1160 chars); the reranker reads a fixed 320 tokens, so phone speed is unchanged, not faster.
The pregnancy swelling + headache question has weak evidence with BOTH guides (the right passage, induction module p.68 "swelling of face and hands … headache, blurred vision → refer", is not retrieved); the old guide passed only because unrelated passages happened to contain the words. Open retrieval gap, to fix next.
New host test: SopExamplesTest (10 checks). Test data: tools/question_eval/question_set_50b.tsv.

## AD. 9 Nov 2026 — blank offline answer (prompt too long for the phone) and an unrelated "guide says" quote

Found in ors_log4 / ors_log5 (phone, new overlap guide):

1. Hindi question "महिला को पेशाब में जलन और बुखार है": Gemma's engine refused the prompt twice —
   `Prefill input length exceeds available state entries (remaining capacity: 4096)` — so no answer came out.
   The engine is opened with an 8192-token window (`KB_CONTEXT_EXTENSION_ACTIVE kbContext=8192`), but on this
   phone/model the real KV cache is 4096. The evidence budget trusted 8192 (9879 chars); 7.7k chars fitted
   for an English question but not this one. My earlier note that the phone reads up to 9000 chars was wrong
   for this device; the overlap guide's longer passages made it show.
2. "child has not passed urine for 8 hours and is very weak": the proof found 0 of 7 statements in the
   passages, yet the "The guide says" box showed the top passage — a mother's urine after delivery.
   (Retrieval: the guide has no direct child-urine guidance; the old guide had the same top passage.)
3. Gemma read "पेशाब में जलन" (burning urination) as "burning … in the vulva" in its rewrite — not fixed here.

| # | Change | File |
|---|--------|------|
| AD1 | When the engine refuses a prompt for its length, the size it reports is remembered (per model file) and the answer is retried at once with the best passages that fit. Later questions are trimmed before generation when the real size is known (script-aware token estimate: Devanagari ~1.4 chars/token, other ~3). English questions that fit keep all their passages. | ArogyaKvCapacity (new), PocketRagLocalGenerator, LocalModelManager |
| AD2 | No "guide says" quote when none of the answer's statements was found in the passages; the answer then starts "⚠ The guide passages found do not confirm this answer — check with your ANM." (Hindi too). | ArogyaVisitAi, AndroBaselineRuntime |

Logs to check: `KB_CONTEXT_CAPACITY_LEARNED`, `POCKET_RAG_CAPACITY_RETRY`, `POCKET_RAG_CAPACITY_FIT`, `AROGYA_UNPROVEN_ANSWER`.
New host test: KvCapacityTest (16 checks, includes the exact phone error).
Test-method caveat: the harness checks that expected words appear in the evidence. That passed the child-urine
question with a mother's passage. Expectations must name the subject too (planned).

## AE. 9 Nov 2026 — search for the same kinds of bug everywhere

Asked: "Search for all such bugs and resolve." Classes found from logs 4–6 and fixed:

| Class | Where else it could happen | Fix | File |
|---|---|---|---|
| Prompt longer than the phone's real KV cache (4096, opened as 8192) | Every on-phone generation: Ask SOP (AD), visit features (notes, forms, family words) and "More detail" | Any refusal by the engine teaches the real size (all generation paths). Visit features budget their output against it and skip a prompt the phone cannot hold instead of failing twice. | LocalModelManager (`usableContextTokens`, learning in all paths), ArogyaVisitAi |
| "Unconfirmed" warning on a correct Hindi answer (MY AD2 bug, log 6: menstrual pain, proof 0/7) | Every Hindi answer: the proof compares Hindi sentences with English passages and scores them low | The warning and quote removal apply only to answers in the passages' script. Hindi answers show the guide quote as before. | ArogyaVisitAi, AndroBaselineRuntime |
| "From official guides" label on a weakly supported answer | Every answer starting with a ⚠ evidence line | The label is not shown on those answers | ArogyaSopChat |
| Re-ranker blind to the second half of long passages | 4,659 of 5,389 passages are longer than the ~1,000 characters the re-ranker reads (the pregnancy danger-sign list starts at character 1,031 of its passage) | A long passage is scored on its run of whole sentences that clearly best covers the question (rarity weights from the installed guide; only when it covers ≥1.5× more than the beginning). Passages, windows and sources unchanged. Switch: `ArogyaFlags.rerankFocus`. | GenericKbRerankFocus (new), GenericKnowledgePackRepository |
| Hindi words mistranslated or left untranslated in Gemma's search line (पेशाब → vulva; "a किशोरी") | Every Hindi/Hinglish question | Rewrite prompt: translate each Hindi word by its exact meaning; never change body part, symptom or person. (Not testable without the phone's Gemma.) | GenericKbQuestionRewrite |
| Tests passing on the wrong subject (child question, mother's passage) | All question sets | New strict scorer: every expected concern must be in ONE passage that is also about the question's subject (child / newborn / pregnant / mother / adolescent). | tools/question_eval/score_strict.py |

Results, 163 questions (harness, real guide, real E5 + re-ranker):

| | Loose (words anywhere) | Strict (concern + subject in one passage) |
|---|---|---|
| Old guide | 162 | 155 |
| Overlap guide (installed on the phone) | 162 | 159 |
| Overlap guide + re-ranker focus (this build) | 161* | **160** |

\* the one loose "loss" was a fake pass (abdominal swelling matched "baby's abdomen touches mother's abdomen").
Still missing (strict): pregnancy feet swelling + headache (Hinglish, misspelt), child fever + rash + cough (measles), child abdominal swelling + diarrhoea.
Tried and NOT adopted: rewrite lines without "What should an ASHA do…" (fixed the pregnancy questions but lost Hindi convulsions and 12-month fever: 159).
Host suite: 68/68 test files pass (4 asset tests run from the repo folder), plus RerankFocusTest 9/9.

## AF. 9 Nov 2026 — short, section-less offline answers (RCA from logs 3–6)

User: "the output quality of the local LLM has gone bad; no why it matters, what to do …".

Root cause (measured, not guessed):
- The output token limit was NOT reduced: AROGYA_BUDGET shows 1200 (English) / 3000 (Hindi) tokens, uncapped.
- What changed is the evidence the phone reads. 20:19 run (good, 2,511-char answer): 4,485 chars of evidence.
  Logs 5–6 (short ~650-char answers): 7,800–8,500 chars. Cause: my raise of PHONE_EVIDENCE_CAP_CHARS to 9,000
  ("quality over speed") plus the longer overlap passages. The phone's real KV cache is 4,096 tokens (log 4), so
  a ~7,800-char prompt leaves Gemma little room and a long prompt; it wrote short answers or none.
- Separately, a problem stated without question words ("child has not passed urine … very weak",
  "किशोरी को माहवारी में बहुत दर्द होता है") was typed FACT ("what it is, its role") instead of action-first.

| # | Change | File |
|---|--------|------|
| AF1 | Phone evidence back to 4,500 chars (the size that gave full answers). Online and "Check online" unchanged. | GenericKnowledgeContextProvider |
| AF2 | Answer shape: when the question itself gives FACT, Gemma's own restatement decides ("What should be done when a child …" → action-first with what to do and when to refer). Applies to the phone prompt, the online prompt and the output ceiling. | ArogyaQuestionType.kindFor, PocketRagModels (`rewrite`, `kind`), PocketRagEvidenceAssembler, PocketRagPromptBuilder, PocketRagCloudResolver, PocketRagEngine |

Tests: QuestionKindTest 9/9 (new; loads the guide vocabulary like the phone — without it the spelling fixer turns
"done" into "daane"), real engine compiled with these files: OrsReplayTest 18/18, BudgetGapsTest 18/18, GuideConfirmTest 10/10;
AnswerFormatTest 51/51, TierTest 44/44, BudgetTest 30/30.

## AG. 9 Nov 2026 — final sync and regression check

- All changed files in C:\Arogyadoot verified byte-for-byte against the tested copies (18 app files, 4 new tests, run.ps1, Ask SOP question pool).
- The bundled guide (app/src/main/assets/guide/ASHA_Health_Guide_v2.db) is now the overlap guide (sha prefix 05add846…); the old one is in backup_20261109_AG_old_guide. A phone that already has a guide keeps it until a guide is picked again (Settings → Knowledge Base). The Hugging Face download link still serves the old guide.
- Regression check on the final code: 70/70 host test files pass (4 asset tests run from the repo folder); real answer engine with the final files: OrsReplayTest 18/18, BudgetGapsTest 18/18, GuideConfirmTest 10/10; 163-question retrieval: 160 strict / 161 loose.
- New files to `git add`: ArogyaSopExamples.kt, ArogyaKvCapacity.kt, GenericKbRerankFocus.kt, ArogyaSopHistory.kt, ArogyaSopHistoryStore.kt, ArogyaGroqMinute.kt (if not yet added), assets/sop/example_questions.txt, the new tests, tools/kb_rebuild/, tools/question_eval/question_set_50b.tsv, score_strict.py.

## AH. 9 Nov 2026 — decoder loop and two-line answer (phone 23:27, JSY / institutional delivery)

Cause (found in the guide, caused by my overlap rebuild): the overlap guide repeats a neighbouring sentence in
adjacent passages. "ASHAs should motivate every pregnant woman … labour complications may suddenly occur" is in
both page 69 and page 70; both were in the evidence, so the prompt had the sentence twice and Gemma looped on it.
The loop guard then kept only the short text before the loop, giving a two-line answer.

| # | Change | File |
|---|--------|------|
| AH1 | Each evidence sentence is given to the model once (repeats from overlapping passages removed; headers kept; Hindi too). The overlap still helps search. | ArogyaEvidenceDedupe (new), PocketRagEvidenceAssembler |
| AH2 | Text before a decoder loop counts as the answer only if it is at least 500 characters; otherwise the one recovery generation writes the answer again (quality over speed). | PocketRagLocalGenerator, PocketRagEngine |

Tests: EvidenceDedupeTest 7/7 (new); real engine with these files: OrsReplayTest 18/18, BudgetGapsTest 18/18,
GuideConfirmTest 10/10; host suite 71/71 test files (4 asset tests run from the repo folder).

## AI. 11 Nov 2026 — log 14: first draft loops again, evidence cap was not working

Found in ors_log14 (23:51, "mother has fever and foul smelling discharge after delivery"):
- `KB_PHASE14_CONTEXT … chars=8315 budget=4500` and `POCKET_RAG_EVIDENCE_BUDGET … rawChars=8100`: the 4,500-char
  phone cap (AF1) limited only the provider's search budget; six 1,500-char windows were still packed and sent.
  The last windows were unrelated (eye/blindness, dust in the nose). Gemma's first draft looped (5,464 chars,
  `degenerate_output:low_diversity`), was shown while writing, then replaced by a 1,180-char recovery answer
  that repeated itself and invented "two days ago".
- `AROGYA_BUDGET … feature=ask_sop_fact`: the action-first shape (AF2) did not apply, because Gemma wrote
  "What should **an** ASHA do when …", which the type check did not recognise.
- "What are the benefits of using sanitary pads" was typed URGENT ("bene**fits**" contains "fits").

| # | Change | File |
|---|--------|------|
| AI1 | The cap is applied where the phone prompt is assembled: offline phone answers get at most 4,500 chars of evidence (best passages first). Check online / cloud (larger windows) unchanged. | PocketRagEvidenceAssembler (`phoneCapChars`), GenericKnowledgeContextProvider (`phoneEvidenceCap` setter) |
| AI2 | A restatement "What should/must/can … do / be done" gives the action-first shape in any wording. | ArogyaQuestionType.kindFor |
| AI3 | " fits" matched as a word ("benefits" no longer URGENT). | ArogyaQuestionType |

50 new questions (recipes, Hindi, Hinglish, English, multi-symptom; tools/question_eval would hold them as question_set_50c):
22 of the 50 are problem statements that were typed FACT before AF/AI and now get the action-first shape.
Search, strict (concern + person in one passage) using only the evidence that fits 4,500 chars:
50c 49/50, 50 48/50, 23 22/23, 25 25/25, 50b 50/50, follow-ups 15/15 → 209/213 (same as without the cap).
Misses: pregnant + dizzy/tired, pregnant feet swelling + headache (Hinglish misspelt), child abdominal swelling + diarrhoea, child fever + rash + cough.
Tests: QuestionKindTest 13/13; real engine OrsReplay 18/18, BudgetGaps 18/18, GuideConfirm 10/10; host suite 71/71 files.
Not testable here: Gemma's own writing — check on the phone.

## AJ. 11 Nov 2026 — re-audit of offline Ask SOP (stage by stage, log 14)

One offline turn traced end to end (app start → answer). Stage, what the log shows, verdict:

| Stage | Log 14 | Verdict |
|---|---|---|
| Model load | GPU, adapter context=4096 (opened as 8192) | Real cache 4,096 → prompt must stay small (AI1 cap) |
| Question rewrite (Gemma) | 2.2 s, "What should an ASHA do when a woman has high fever and foul smelling discharge after delivery?" | Good wording; shape check missed it → fixed AI2 |
| Search | `rr=off pairs=0` — re-ranker's one-time device check still running (first question after install) | **BUG → AJ1** |
| Evidence | 6 windows, 8,315 chars, last two unrelated (eyes, nose dust) | **BUG → AI1** (cap now real) + AJ1 (ranking) |
| Answer type | ask_sop_fact | **BUG → AI2** |
| Generation 1 | greedy, 92 s, 5,464 chars repeating (`low_diversity 132/910`), shown while writing | **BUG → AJ2, AJ3** |
| Generation 2 (recovery) | greedy, 29 s, 1,287 chars, self-repeating | **BUG → AJ3** |
| Proof | 28 s after the answer | slow but correct; left |
| Total | 154 s | |

| # | Change | File |
|---|--------|------|
| AJ1 | Search waits for the re-ranker's one-time device check (at most 12 s, only the first question after an install); never waits later. Log `RERANKER_WAIT`. | GenericKnowledgePackRepository, GenericKbReranker.awaitReady |
| AJ2 | The streaming loop guard now also catches loops of long sentences (up to 360 chars; 3 consecutive copies). Before, units over 96 chars were never checked, so a repeating 190-char sentence ran for 90 s. | AndroGenerationLoopGuard |
| AJ3 | The recovery answer after a loop samples gently (temperature 0.3 with the engine's topK 40 / topP 0.95) instead of greedy, so it does not fall into the same loop. First answers stay greedy (exact numbers). | PocketRagLocalGenerator, LocalModelManager (`temperatureOverride`) |

Correction to AI: "two days ago" in the log 14 answer was in the question ("delivered 2 days ago"), not invented.
Tests: LoopGuardLongTest 5/5 (new), AnswerFixesTest 69/69; host suite 72/72 files (4 asset tests from the repo folder).
Not compilable here: the Android files (repository, re-ranker, model manager, generator) — Gradle build is the check.

## AK. 11 Nov 2026 — audit of the new code (AD–AJ)

Line-by-line review of everything changed since 9 Nov evening. Two real problems fixed, the rest confirmed:

| # | Finding | Fix | File |
|---|---|---|---|
| AK1 | The engine's message says "**remaining** capacity". In a fresh answer that is the whole cache, but a refusal inside a long chat session could report much less, and the app would have remembered that small number for good (every later answer trimmed to almost nothing). | Learned size is never below the model's own window and nothing under 2,048 is learned. | LocalModelManager.noteKbCapacity |
| AK2 | Action questions recognised from Gemma's restatement ("What should be done when …") were told "this is URGENT", pushing ordinary problems (pain after copper T, baby not sucking well) towards "refer now". | "URGENT" only when the question itself has danger words; otherwise the same action-first order without the word. | ArogyaQuestionType.lengthLine |

Checked and fine: generator capacity retry (one retry, at least one passage fewer); loop text kept only ≥500 chars and with repeated sentences removed (keepBeforeLoop); evidence de-duplication keeps passage headers, keeps the first copy (best passage), Hindi handled; phone cap applies only to offline turns (flag set per turn, cleared in `finally`) and never to Check online / cloud (window > 8,192); re-ranker wait only when its check has not finished (first question after install), at most 12 s; loop guard needs 3 exact consecutive copies of a ≥97-char unit (normal text never does that; tested); recovery sampling only on the recovery pass; visit features skip a prompt the phone cannot hold instead of failing twice; no-quote rule only for same-script answers.
Known limits left: Android files compile only on the laptop (Gradle is the check); Gemma's writing can only be judged on the phone; proof step ~28 s.
Tests: QuestionKindTest 13/13, AnswerFormatTest 51/51, TierTest 44/44, BudgetTest 30/30 (rest of the suite unchanged since AJ: 72/72 files).

## AK2. 11 Nov 2026 — 20 complex questions, gap analysis

20 new complex questions (several symptoms, mother + child, recipes with amounts, doses, Hindi, Hinglish):
tools/question_eval/question_set_20_complex.tsv. Checked per question: passages that reach Gemma within 4,500 chars
after duplicate removal, every concern + the right person in one passage, answer shape, prompt size.

Pipeline facts: prompts 2,200–2,900 tokens (fit the 4,096 cache with room to answer); duplicate removal took out up
to 470 chars per question; re-ranker on.

| # | Gap found | Fix | File |
|---|---|---|---|
| G1 | Source precedence bonus tuned for the old procurement pack (2.0 per level). The ASHA guide declares 4 levels, so a loosely related Integrated-module passage got up to +6 and pushed out the right passage from another guide (MUAC/malnutrition, newborn not breathing). | 1.0 per level. All 233 questions: 223 → 225, nothing lost (0.5 → 224, 0 → 223). | GenericKbPhase14Engine.PRECEDENCE_STEP_LOGITS |
| G2 | "How do I make ORS at home **and how much** to give…" / "how to prepare … and how many times" typed FACT (short shape, 600-token ceiling): the recipe could be cut. | Steps asked + amount asked → procedure shape. | ArogyaQuestionType.kindFor |

Remaining (not fixed, with cause):
- Heavy bleeding + placenta not out: the right passages (m6 p52/59, ASHA facilitator p56 "placenta not delivered within 30 min") are candidates but rank 5th–6th; three Integrated-module passages fill the first places. The app's per-concern search (not in the test harness) should add the placenta passage; check on the phone (`KB_PART_SEARCH`).
- Child diarrhoea + vomiting + passing little urine (Hindi): the guide has little on "little urine" in diarrhoea.
- MUAC red: the guide mentions MUAC tapes only as supplies; no "red = SAM" text.
- Pregnant + dizzy/tired; pregnant feet swelling + headache (misspelt Hinglish); child fever + rash + cough; child abdominal swelling + diarrhoea.
- About 4% of evidence passages are Hindi-text passages from a parenting book, usually not on topic (Hindi questions).
- Dog bite, convulsion + stiff neck: covered (the strict test wanted the word "child" in the passage; the guide's dog-bite and danger-sign pages do not name the child).
Tests: QuestionKindTest 18/18; real engine OrsReplay 18/18, BudgetGaps 18/18, GuideConfirm 10/10; host suite 72/72 files.

## AL. 11 Nov 2026 — verification of every scenario discussed, and guideline links

### Links to the official guidelines
Cause could not be confirmed from the logs: link taps were never logged, and this workspace cannot reach the
government sites to test the addresses. The open code itself is correct (plain view intent). Likely causes on
the phone: Chrome downloads PDFs instead of showing them (looks like "nothing opened"), or a site is slow/down.
| # | Change | File |
|---|---|---|
| AL1 | One opener for all Ask SOP guideline links (Open PDF, per-source Open, Open official page): view intent in a new task; if no app opens it, retried without "#page="; if that fails, the link is copied and a message says to paste it in the browser. Every attempt logged `AROGYA_OPEN_LINK ok/failed/none url=…`. | ArogyaOpenLink (new), ArogyaVisitScreen (4 places) |
15 documents in the guide have no online address (e.g. Health Bulletins, HBNC/HBYC job aid); they show "No online copy is linked".

### Every scenario and how it is verified
| Scenario (from our conversation) | Verified by | Result |
|---|---|---|
| Hindi / Hinglish / misspelt questions find the right passages | 233 questions (50+23+25+15+50+50+20), strict: concern + right person in the phone's 4,500 chars | 225/233 |
| No static keyword lists for understanding (Gemma restates) | design + ConcernsTest | 20/20 |
| Follow-up questions | 15 follow-ups; FollowUpTest | 15/15; 17/17 |
| Ask SOP opens clean; last-10 history | SopHistoryTest | 8/8 (screen: phone) |
| Rotating tested example questions | SopExamplesTest | 10/10 |
| Several symptoms in one question | ConcernsTest; 20 complex set | 20/20; 16/20 strict |
| ORS recipe numbers kept (guide confirmation, narrowed to this question's passages) | GuideConfirmTest, OrsReplayTest (real engine) | 10/10, 18/18 |
| Answer shown, removed, rewritten (chained salvage) | OrsReplayTest | 18/18 |
| "More detail" / family answers not cut short | BudgetGapsTest, BudgetTest | 18/18, 30/30 |
| Blank answer when the prompt is too long for the phone | KvCapacityTest + retry code (AD, AK1) | 16/16 |
| Phone evidence really capped at 4,500 chars | real engine build with final assembler | compiles, tests pass; phone log `rawChars` |
| Loops (repeated sentences from overlap; long-sentence loops; 2-line answers) | EvidenceDedupeTest, LoopGuardLongTest, AnswerFixesTest | 7/7, 5/5, 69/69 |
| Problem statements get what-to-do / when-to-refer answers; recipe + amount gets steps | QuestionKindTest | 18/18 |
| Unrelated "guide says" quote; false ⚠ on Hindi answers | code review (AK) | phone |
| First question after install without re-ranker | code (AJ1) | phone log `RERANKER_WAIT` |
| Long passages ranked on the right part | RerankFocusTest + 233 questions | 9/9 |
| Source preference tuned for ASHA guide | 233-question sweep | 223 → 225 |
| Whole host test suite | 72 test files | 72/72 |
| Laptop files = tested files | checksums of all 23 changed app files | 23/23 match |

Not verifiable here: Gemma's own writing on the phone, online (Groq) answers end to end, the Android screens
(compiled only by Gradle on the laptop), the guideline websites themselves.

## AM. 11 Nov 2026 — photo attachments audit; "adding patients fails in the demo"

No phone log of a failed add yet (logs 4–14 contain no add-person attempt). Registers load fine in every log
(`AROGYA_REGISTRY_LOAD patients=50`). Photo flows audited in code:

| Flow | Reads with | Goes online? | Touched by 9–11 Nov changes? |
|---|---|---|---|
| Add person → Photo (slip, card, register page) | ML Kit text + table rules; Gemma vision for handwriting; Gemma text for missing fields (`extractPersonFields`) | Never (privacy) | Only through ArogyaVisitAi.route: refuses a prompt the phone cannot hold, and only after the phone has reported its real size (AD/AK1) |
| Add person → PDF list | ML Kit per page + Gemma | Never | same |
| Add person → CSV | rules only | — | no |
| Demo: person from slip photo, "Read again with Gemma/Qwen" | as above; Qwen only for the demo's online re-read, Aadhaar blacked out first | demo button only | no |
| MCP card photo | Gemma on the phone | Never | no |
| Medicine label scan | ML Kit + phone/online | online allowed | no |
| Visit photo attachments | ML Kit / Gemma vision | per setting | no |

Changes (diagnostics + one crash guard; no reading logic changed):
| # | Change | File |
|---|---|---|
| AM1 | Every add-person failure is logged with its reason: `AROGYA_ADD_PERSON_FAILED step=photo/csv/pdf/save …`, registry save errors `AROGYA_REGISTRY_UPDATE_FAILED …`. Before, the reason was only shown on screen. | ArogyaAddPersonUi, ArogyaRegistryUi |
| AM2 | Demo "save person" could crash the app if the registers were not open yet (uncaught exception from the store). Now caught, logged `AROGYA_DEMO_PERSON_SAVE_FAILED`, and the screen says to try again. | ArogyaDemoTour |
Next: a phone log of one failed add (steps + screenshot) to find the actual cause.

## AN. 11 Nov 2026 — photo / file attachments: wrong or unrelated photo must end with a clear message

Scenario: Add patient → photo of something else (a wall, a face, a product). Gemma could keep writing for
minutes (greedy decoding loops, no time limit on photo reading), then the text model was asked again and again,
so the screen looked stuck. Audit of every attachment feature, as CTO:

| Gap | Where | Fix | File |
|---|---|---|---|
| G1 No time limit on on-phone photo reading | person slip/register/card/medicine reading, photo description for Ask SOP, kit count | Every Gemma photo reading has a limit (count 25 s, describe 60 s, read 90 s); on expiry the generation is cancelled and the screen shows its "nothing read" message. A transcript that repeats one phrase is rejected as broken. Log `AROGYA_LOCAL_VISION … timedOut= looping=` | ArogyaLocalVision |
| G2 "Nothing here" replies treated as text | all photo readers | `NO_TEXT.`, "No person details …", "There is no text …", "This photo does not show …" all mean nothing found (real transcripts and card rows starting "No." kept) | ArogyaPhotoText.clean / saysNothing |
| G3 Copying details off a photo's text could run 5 min per call | person, medicine, card extraction | 2-minute limit per phone generation for these features | ArogyaVisitAi.generate / route (EXTRACT_FEATURES) |
| G4 Empty result asked the phone again | card reading (up to 6 calls), person list | An empty reading is an answer ("no card values / no person"), never a second call | ArogyaVisitAi |
| G5 Text that is not a register read block after block | person list from a photo/PDF page | Stops after 3 blocks with nobody in them; PDF stops after 2 pages with nobody (Gemma reading) | ArogyaVisitAi.extractPersonList, ArogyaAddPersonUi |
| G6 Unrelated photo in medicine scan gave a form with the photo's first line as the medicine name | stock scan (phone + online re-read), demo | "No medicine name, batch or expiry was found on this photo. Check that it is a medicine strip, box or sachet …" when no batch, expiry, quantity and no known medicine name | ArogyaStockUi, ArogyaDemoTour |
Already correct (checked): add-person photo with no text → "No readable text on this photo"; with text but no
person → "This does not look like a registration slip, register page or card …"; card photos and person photos
never go online; online medicine reading has a 25 s deadline; demo "Read again" says "Gemma found no person's details".
Tests: PhotoNothingTest 18/18 (new); host suite 73/73 files. Android screens: Gradle build is the check.
Phone check: Add patient → photo of a wall → a message within ~1.5 min (usually seconds), never stuck.

## AO. 11 Nov 2026 — remaining gaps closed where possible without the phone

| # | Gap | Fix | File |
|---|---|---|---|
| AO1 | Photo reading in the original chat / troubleshooting screens (6 call sites) had no time limit | Hard 150-second limit inside the one function every on-phone photo reading goes through; the generation is cancelled on expiry (`LOCAL_VLM_TIME_LIMIT`). ArogyaDoot screens keep their shorter limits (AN). | LocalModelManager.inferVisionImage |
| AO2 | Gemma wrote the question again as a title before "Answer:" / "उत्तर:" | That line is dropped (only when: no label, no full stop, directly before the Answer label, shares words with the question or ends with "?"); everything else unchanged | ArogyaAnswerFormat.dropEchoedQuestion, ArogyaVisitAi.buildResult |
| AO3 | Comment still said "9,000-character" cap | corrected | ArogyaVisitAi |

Checked, no change needed: attachment failures already give a plain reason (`lastAttachProblem`); files have size
limits (PDF OCR 24 pages, text 2 MB, Office media 8 MB); kit-count photo has the 25 s limit and "could not estimate".
Still open (needs the phone or content): add-person failure cause (log); placenta per-concern search; guide lacks
MUAC-red and little-urine content; online "More detail" cut-off detection; page label for overlap sentences;
document-only answer for an unrelated document (not verified).
Tests: EchoQuestionTest 7/7 (new), AnswerFormatTest 51/51; host suite 74/74 files.

## AP. 11 Nov 2026 — longer photo safety limits; attachments remembered in follow-up questions

| # | Change | File |
|---|---|---|
| AP1 | Photo safety limits raised (a slow phone or a dense register page needs time; the limit only catches a stuck generation): kit count 25 → 60 s, photo description 60 → 120 s, reading a slip/card/register/label 90 → 180 s; copying details off the text 120 → 180 s; hard limit for any photo reading 150 → 240 s. | ArogyaLocalVision, ArogyaVisitAi (EXTRACT_LIMIT_MS), LocalModelManager (VISION_HARD_LIMIT_MS) |
| AP2 | Follow-up questions remember the attachment. What the phone read from the photo (description, label text) or document (matching lines) is kept for the conversation. A later question with no new attachment that depends on the earlier one ("what dose should I give?", "isme kya karna hai?" — the existing semantic follow-up check, no word list) carries those facts into the guide search and the answer. Replaced by the next attachment; cleared by New / a new visit. Log `KB_ATTACHMENT_FOLLOW_UP tag= used=`. | AndroBaselineRuntime (kbAttachmentFollowUpQuery) |
| AP3 | The ASHA sees it: such an answer starts "📎 This answer also uses the photo you attached earlier." (Hindi too). | ArogyaVisitAi.buildResult |
Not compilable here (Android files); Gradle build is the check.
Phone check: attach a medicine strip photo → ask "what is this for?" → then ask "how many times a day?" — the second answer starts with 📎 and talks about that medicine.
