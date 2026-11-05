# Regression Report — ArogyaDoot AI migration (Stages C–G)

Date: 5 Nov 2026. Scope: Gemma-offline + Groq-online migration, Meta/Llama retirement, UI sweep.
39 files changed (+429/−307), all inside `C:\Arogyadoot`. Nothing committed, nothing pushed.

## Verification performed

- `gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL in 25m 17s** (warnings only; the one new
  warning, `ArogyaSetupUi.kt:343` always-true, is a harmless leftover of the kept HTML-guard shape).
- APK verified: `package com.arogyadoot.app`, label `ArogyaDoot`, `arm64-v8a` native libs.
- Host tests (after syncing `run_some.ps1`'s stale file list with `run.ps1`): CloudTest 16/16,
  AiTableTest 33/33, ApplyTest 22/22, DemoTourTest 70/70, SetupMatchTest 14/14, SpeedTest 18/18,
  FinishVisitTest 34/34, SetupPlanTest 18/18, TableOcrTest 74/76.
- TableOcrTest's 2 failures (`narrow-register-columns-not-merged`, `setup-links`) reproduce at
  HEAD without this change set (pre-existing, unrelated: OCR column-merge WIP; Meta-zip URL assert
  vs R2 primary). New checks added by this migration all pass (groq preset, vision request body,
  groq-no-key, groq-steps, Gemma setup-match ×3, Groq setup-banner/key/page).

## 18-scenario matrix (honest status)

| # | Scenario | Status | Evidence / note |
|---|---|---|---|
| 1–4 | Offline text/KB/citations (Gemma) | NOT TESTED | needs Gemma bundle + device |
| 5–6 | Offline image/register via Gemma vision | NOT TESTED | needs device (Stage D) |
| 7–9 | Online Qwen text/Hinglish/KB-grounded | NOT TESTED | needs Groq key + device (Stage E) |
| 10–11 | Key failure, internet loss | CODE PATHS KEPT | `rejected`/`offline`/`timeout` branches + save-anyway; live check pending |
| 12–13 | Model-load failure, low memory | CODE PATHS KEPT | `gemmaInstalled` gates, `not loaded` + free-space UI; live check pending |
| 14–15 | Visit create/save/reopen | PRESERVED (untouched) | host suite green (Finish/Registry/Save paths) |
| 16 | Voice notes | Whisper path PRESERVED | cloud voice retired by design; dictation callers unchanged |
| 17 | Report generation | PRESERVED (untouched) | AI-table PDF covered by host tests |
| 18 | Restart/settings persistence | PRESERVED | prefs/keystore names unchanged; stale `meta`/`META` prefs migrate at read time without writes |

No AI regression is *expected*: RAG, Whisper, OCR geometry, forms, registers, TTS, deadlines and
fallback order are untouched; every retirement is a routing gate, not a deletion.

## Risks / open items

1. `qwen/qwen3.8-27b` id unverified against the Groq account — confirm via Models API on device.
2. Qwen vision capability unverified — `wanted()` will happily send images; Stage E must prove it.
3. Owner-hosted Gemma URL availability/quota unverified from a phone network.
4. LiteRT runtime + Gemma 3n on 6 GB RAM unverified (load time, peak memory, thermal).
5. Dead Meta branches (`SamCountDialog.runSam31`, demo shelf else-branch, transcribe body) never
   execute but still reference `MetaOnline` — Stage I removes them.
6. `run_some.ps1` file list was stale; synced with `run.ps1` (one-line fix, kept).

## Stage I — dependency cleanup recommendations (NOT executed)

1. Remove dead Meta call branches (item 5) after device proof. No APK saving (Kotlin only).
2. Remove `meta` endpoint preset + `MetaCloud.SPARK/BASE` consts; keep Together preset.
3. Remove `MetaModelApi`/`MetaOnline` voice+vision surface once nothing references it (keep
   `hasInternet` — relocate to `ArogyaCloud`).
4. Native/JNI (`pramaan_llama.cpp`, `libpramaan_llama.so`, GGUF assets): measure APK delta on a
   deletion branch before deciding; keep until Gemma is device-proven (rollback depends on it).
5. `installOneZipFromUri` + Meta-zip `MODELS_URL_*` consts: remove after the last legacy install
   path is unused; update the pre-existing failing `setup-links` assert accordingly.
6. Re-run the full gate + device smoke after each removal step; never combine removals with
   behavior changes.

## Rollback

Single-gate reversals: `META_ENABLED`, `GROQ_DISABLED`, `preferredTextModelFile()` else-branch,
`textEndpoint()` default + `meta` filter, `keyFor` else-branch, `engine()` META mapping,
`ArogyaSetup.status()/scan()` checks, Models-page `GEMMA_*` → `MODELS_*`. Full revert via git.
