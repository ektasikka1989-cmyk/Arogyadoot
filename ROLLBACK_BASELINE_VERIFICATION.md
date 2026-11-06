# ROLLBACK BASELINE VERIFICATION — ArogyaDoot

Date: 6 Nov 2026. Method: read-only audit (no source/device/git-state changes).
Question: is commit `59e0418` the exact state immediately BEFORE the user asked
to fix (1) the 16 KB warning, (2) the emulator crash at Groq key entry,
(3) setup-wizard resume, (4) inline mic dictation?

## Verdict: C (PARTIAL)

`59e0418` is **not** the intended baseline. It **contains** the troubleshooting
fixes for all four items. The intended baseline is the post-migration,
pre-troubleshooting working tree, which was **never committed** — no single
commit represents it. It is exactly reconstructible by subtracting the
troubleshooting changeset listed in §4. Plan in §7 (not executed).

## 1. Timeline (all +0530, from git + file mtimes + report contents)

| When | Event | Evidence |
|---|---|---|
| 5 Nov 14:20 | `cd00b00` initial fork commit | git log |
| 5 Nov 15:20 | Migration Stage A audit written | `AI_ENGINE_AUDIT.md` mtime |
| 5 Nov 17:03 | Migration reports batch (Gemma/Groq/routing/vision/regression) | 5 × `.md` mtime 17:03:13 |
| 5 Nov 17:43 | Gemma model + guide downloaded on phone (user testing) | device file dates (prior session) |
| ~6 Nov 00:30–01:10 | User reports 16 KB warning, key-entry crash, setup non-resume, mic popup; troubleshooting session | `EMULATOR_MEMORY_RCA.md` "Reported symptoms" |
| 6 Nov 01:11–01:12 | Troubleshooting reports batch (4 × `.md`) | mtimes 01:11:59–01:12:01 |
| 6 Nov 01:29 | `d201a11` commits migration + troubleshooting together | git log; message names only the troubleshooting |
| 6 Nov 11:11 | `59e0418` README-only commit | git log; diff = README only |
| 6 Nov 11:32+ | Emergency RCA session (slot fix, then reverted) | TEMP bundle/patches |

Reflog shows only these 3 commits (no hidden branches/stashes). The freeze
bundle (`arogyadoot-freeze-59e0418.bundle`) and `emg_*` TEMP files are all
post-`59e0418`; none captures the pre-troubleshooting tree.

Key proof the troubleshooting came second: every reported symptom presupposes
the migration (a *Groq*-key crash, a *Gemma* download, the retired-voice mic
popup), and `REGRESSION_REPORT.md` (migration-era, 17:03) already describes a
39-file uncommitted tree that `d201a11` later committed as 56 files.

## 2. Commit 59e0418

- Date: 2026-11-06 11:11:37 +0530. Message: "Review README against source:
  Gemma/Groq engine, verification status, MIT license". Parent: `d201a11`.
  Tree = `d201a11` tree + README changes only.
- Present: Gemma 3n E2B offline engine (download/install/Gemma-first routing,
  `GEMMA_URL_PRIMARY`); Groq `qwen/qwen3.8-27b` cloud (preset, key plumbing,
  vision via `image_url`); Meta retired (`META_ENABLED=false`,
  `GROQ_DISABLED=false`); inline STT; keystore hardening; 16K linker flags.
- Absent from automatic use: Llama AUTO selection (loads only under the
  UI-unreachable `LLAMA_ONLY` force); Meta cloud/voice/SAM (dead branches);
  Meta zip in the wizard (settings-only via `handleZipFilePick`); popup
  dictation (dead `if (false)` branches in AddPerson/Registry UI).
- Gemma offline inference configured: YES. Groq Qwen configured: YES. Meta
  APIs disabled: YES. Gemma installer replaced Meta ZIP in wizard: YES
  (settings zip path retained). ASHA workflows preserved: YES — guided visit,
  form specs, registry store/codec, finish-visit, capture, share, danger and
  clinical files are byte-identical to `cd00b00`; only `ArogyaRegistryUi.kt`
  changed (labels + mic UX, no flow logic).

## 3. Intended baseline vs 59e0418

| Component | Intended pre-fix baseline | Current 59e0418 | Discrepancy? |
|---|---|---|---|
| Gemma local inference | Gemma-first, same core/routing | Same engine + routing | NO (only stale Llama-worded progress strings differ) |
| Gemma vision | Same multimodal path | Same | NO |
| Groq Qwen | Same preset/key/routing/vision | Same | NO (only KeyPage save-verify + settings status ternary added) |
| Setup wizard | Gemma/Groq pages, Unit `save()` | `save()` verifies persistence + keystore-error strings | **YES — minor** |
| Voice input | Popup dialog everywhere | Inline controller; composer/stock/add-person/registry rewired | **YES — major** |
| Native libraries | Same file set, 4K-aligned llama/whisper | `max-page-size=16384` flags added | **YES — minor** |
| Model installer | Same Gemma flow; same settings zip path | Same | NO |
| Knowledge base | Same code + bundled v2 asset | Same | NO |
| RAG and citations | Same | Same | NO |
| ASHA workflows | Same logic | Same logic + inline-mic UX | **YES — UX only** |

## 4. Exact troubleshooting changeset (what 59e0418 adds over the baseline)

Revert-new files (delete): `ArogyaInlineDictation.kt`,
`ArogyaDictationText.kt`, `tools/host-tests/DictationTextTest.kt`,
`EMULATOR_MEMORY_RCA.md`, `INLINE_STT_IMPLEMENTATION.md`,
`VOICE_INPUT_REGRESSION.md`, `GEMMA_LIFECYCLE_AUDIT.md`.
Revert-whole diffs (restore `cd00b00` form): `ArogyaSopChat.kt`,
`app/src/main/cpp/CMakeLists.txt`, `whisper/.../CMakeLists.txt`.
Hunk-level splits (keep migration hunks, drop troubleshooting hunks):
`FixSightRuntimeGraph.kt` (keep Gemma comments + Groq-only `cloudKey`; drop
`updateGroqKey` try/catch + Llama→standby string swaps),
`ArogyaSetupUi.kt` (keep Gemma/Groq pages; drop save-verify),
`MainActivity.kt` (keep Gemma hint; drop save-verify),
`ArogyaVisitScreen.kt` (keep `metaModel`-label removal; drop dictation rewire),
`ArogyaRegistryUi.kt`/`ArogyaAddPersonUi.kt` (keep labels; drop `if (false)`),
`ArogyaStockUi.kt` (keep labels; drop inline rewire).
Uncertain (display-only, either form is behavior-safe): VoiceCheckCard online-
voice hunk; Registry/Demo "Transcribing…" status strings — recommend keeping
the `59e0418` form.

## 5. Slot-leak assessment

1. Defect: `generateEphemeral()`/`generateVisionEphemeral()` released the
   single `generationActive` slot only after a blocking native
   `Conversation.close()`. A cancel during GPU decode can leave the decode
   un-ended forever, leaking the slot; all later generations fail with
   `GENERATION_ALREADY_IN_PROGRESS` after a 120 s drain (proven by logcat).
2. In `59e0418`: YES (verified: 5 release sites, identical to pre-fix).
3. In intended baseline: YES — the file is byte-identical since `cd00b00`.
4. Preserved independently: YES — `%TEMP%\emg_slotfix.patch` (plus test and
   RCA copies). WARNING: TEMP is volatile; re-save to a durable path.
5. Necessary: YES for stable offline inference whenever cancel-during-decode
   is reachable (Stop/resend exist in every baseline). Not reapplied here.
   Residual question stands: post-cancel first-decode stall needs the settle
   experiment once the model is present.

## 6. Recoverability and risks

- The intended baseline is fully recoverable per §4 (subtractive, no lost
  work: the migration half is identified file-by-file above).
- Risks: (a) restoring the baseline restores the slot-brick risk — re-apply
  the preserved patch promptly after; (b) two display-only hunks are
  unattributable — flagged, harmless; (c) the phone currently runs the fixed
  APK (source/phone mismatch) — do not rebuild/reinstall until approved;
  (d) a clean tree proves nothing about version correctness — this report,
  not `git status`, is the evidence.

## 7. Restoration plan (NON-DESTRUCTIVE, awaiting approval)

1. `git worktree add ../arogyadoot-baseline 59e0418` (current tree untouched).
2. In the worktree: delete the §4 revert-new files; `git checkout cd00b00 --`
   the three revert-whole files; hand-reverse the listed hunks in the 7 mixed
   files (keep `59e0418` form for the 2 uncertain display hunks).
3. Verify: worktree-vs-`59e0418` diff must equal exactly the §4 changeset;
   worktree-vs-`cd00b00` diff must equal exactly the migration set.
4. Report fingerprints; move into place only on explicit approval. No device
   or rebuild steps without a separate approval.
