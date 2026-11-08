# Offline answer failure – root-cause analysis (8 Nov 2026)

Question (voice, 11:49): "The baby is having high fever, what should I do?" – offline, Gemma 3n on the phone.
Evidence: phone logcat `_work/tmp/answer_log.txt` (11:13–11:52), git history, 6 Nov reports.
The 11:40 answer in the screenshot is not in the log (Android pruned the app's older lines).

## Timeline of the 11:50 turn
| Time | Event (log) |
|---|---|
| 11:49:54 | Whisper: correct text, English detected, 1.2 s (mic change works) |
| 11:50:16 | Search 19.0 s, rerank 18.4 s (26 pairs) |
| 11:50:47 | First draft looped: "[S4]. Never spong with cold water" ×8 → loop guard → whole draft discarded (chars=0) |
| 11:51:49 | Recovery pass 61.4 s (first token 15.6 s), repetitive again; trimmer 2620 → 1054 chars |
| 11:52:00 | Accepted as `local_recovery:local_grounded_answer`; total 122 s |

## Did today's mic work cause it?
No evidence that it did:
- Today's commit touches only mic/speech files; answer generation, search, checking and formatting are byte-identical to the 6 Nov builds.
- Guide answers use greedy decoding (`KB_ANSWER_TEMPERATURE = 0.0`, unchanged since the first commit): the output depends only on question text, evidence and chat history – not on memory or CPU speed.
- Timings match the pre-mic baseline: 6 Nov emergency report rerank 18.6 s; UI2/UI5 phone runs 104–124 s total, first words 37–53 s.
- No crash or kill of the app on 8 Nov.
Not yet proven: an A/B on the phone (6 Nov APK vs today's, same question, new chat) – see "Next".

## Issues found
1. **Gemma loops under greedy decoding** (P0). Temperature 0 was set for Llama to stop number-copy errors; with Gemma 3n it repeats a sentence (here with an inline [S4] tag) until the guard fires.
2. **Loop guard throws the whole draft away** (P0). The good part before the loop is lost and a full second generation (~60 s) starts.
3. **Repetition trimmer garbles the answer** (P0). In loop mode `removeRepeatedSentences` drops any sentence sharing most words with an earlier one (shrinks the answer, loses the "What to do / Why it matters" structure) and splits sentences at "e.g." (left "fever e.").
4. **Chat history feeds the bad answer back** (P1). The second ask had history=1: the previous broken answer was in the prompt.
5. **Retrieval is not age-aware** (P0 safety). A baby question got the general/adult fever passage (39.5 °C, tablet Paracetamol, sponging) instead of newborn danger-sign guidance.
6. **"Not covered in the selected knowledge base: 'What should I do'"** (P1). The clause planner treats the generic ask as a separate topic and the disclosure line names it.
7. **Rerank 18 s offline** (P2, pre-existing). 26 pairs on CPU.
8. **Foreground-service crash on 7 Nov 12:33** (P1, pre-existing). `ForegroundServiceDidNotStopInTimeException` for `AndroVoiceForegroundService` (dataSync). Android 15 calls `onTimeout(startId, fgsType)`; the app only overrides `onTimeout(startId)` and only detaches – it must stop the service.
9. **Title truncated** "Ask the" (P2).
10. **Log flooding** (P2): ~75 redraw lines/s while streaming push the app's own diagnostic lines out of the buffer.

## Next
1. A/B on the phone, new chat each time, same typed question: 6 Nov APK (`Arogyadoot-recovery-20261106/feature-audit-20261106-app-debug.apk`) vs today's build. Greedy decoding means same input → same answer, so any difference is a real regression.
2. Fixes 1–3 and 5 first (they are what the user saw), then 6, 8, 4.

## Fixes made (8 Nov, not yet built)
| Issue | Fix | Switch / log |
|---|---|---|
| 2 Loop guard discards the draft | `PocketRagLocalGenerator`: keep the whole sentences written before the loop (`KbReplyText.keepBeforeLoop`), then every normal check; regenerate only if < 160 chars / < 2 sentences remain | `ArogyaFlags.loopKeepBefore`; `POCKET_RAG_LOOP_KEEP_BEFORE` |
| 3 "fever e." | Sentence split no longer breaks after a lone letter ("e. g.", "i. e.") | – |
| 5 Adult passage for a baby | `ArogyaClinicalText.ageViews` (baby → young infant/newborn/child, newborn/≤59 d → newborn young infant, child/≤5 y → child) added as an extra search view next to the Hindi views. The existing `retrievalQuery` was never wired in any commit. | `ArogyaFlags.babyAgeView`; `KB_AGE_VIEW` |
| 6 "Not covered: what should I do" | `discloseUnsupportedClauses` skips generic asks (`KbReplyText.isGenericAsk`) | – |
| 8 FGS crash | `AndroVoiceForegroundService`: override `onTimeout(startId, fgsType)` (Android 15) and actually stop the service | `FOREGROUND_TIMEOUT` |
Not changed on purpose: greedy decoding (deterministic answers, protects copied numbers; issue 1 is handled by 2), chat history (4), rerank time (7).
Tests: `tools/host-tests/AnswerFixesTest.kt` (loop keep, split, generic asks, age views, old trimmer guarantees).

## A/B result (12:12–12:15, new chat, typed, offline)
6 Nov build and today's build behave identically (same 2375-char draft, same trim to 1623, same gate reason
`unsupported_numeric_claim:999.5`, same `numeric_salvage` answer). **No regression from the mic work.**
Findings from the A/B log:
- 11 **False loop detection shrinks good answers**: `removeRepeatedSentences` counted the sentence itself, so a key action restated in "What to do"/summary made the answer a "loop" (2375 → 1623).
- 12 **Flipped prohibition reached the user**: draft said "sponge … and cover with a blanket"; the guide says "Do not cover with a blanket".
- 13 **ASHA clinical checks are not used offline**: `ArogyaClinicalGrounding` (polarity, population, newborn, medicines) is called only from `ArogyaOnlinePolicy`. Wiring it fully would reject every medicine sentence; left for later.
- Search ranked the general fever passage first (rr 4.61) over the baby passage (rr 1.11) → fixed by the age view.

## Additional fixes
| Issue | Fix | Switch / log |
|---|---|---|
| 11 | Loop needs the sentence + 3 more near-copies (not counting itself) | – |
| 12 | `KbReplyText.dropFlippedProhibitions` removes answer sentences stating, without negation, an action the evidence forbids (skipped when the guide also states that action positively) | `ArogyaFlags.negationFlipGuard`; `POCKET_RAG_FLIPPED_PROHIBITION` |
