# Gemma 3n Migration Report — ArogyaDoot

Date: 5 Nov 2026. Status: **code-complete, build-verified. Device verification pending.**

Offline engine is now Gemma 3n E2B (`gemma-3n-E2B-it-int4.litertlm`, owner-hosted at
`https://huggingface.co/datasets/EktaSikka/Gemma/resolve/main/gemma-3n-E2B-it-int4.litertlm?download=true`).
Llama 3.2 (llama.cpp) is retired from automatic selection; it loads only under an explicit
`LLAMA_ONLY` force in Advanced settings. Nothing was deleted: GGUF code, JNI bridge, Meta zip
installer and presets remain for rollback (see Stage I in REGRESSION_REPORT.md).

## What changed

**Setup downloads Gemma (was: Meta models zip from Cloudflare)**
- `ArogyaSetupPlan.kt`: step-1 plan is the Gemma bundle (`GEMMA_URL_PRIMARY` = owner URL above,
  `GEMMA_FILE`, `GEMMA_BYTES` floor, `gemmaFileNameOk`, `gemmaDownloadComplete`, `gemmaFreeNeeded`).
  `MODELS_URL_*` consts kept, no longer referenced by the wizard.
- `ArogyaSetup.kt`: new `gemmaInstalled()` (via `ModelFileManager.isModelInstalled(GEMMA_E2B)`) and
  `installGemma()` (stream-copy to `filesDir/models` + `saveModelFileName`, progress, logging);
  `status()`/`scan()` use Gemma; `Scan.llamaReady` → `gemmaReady`; `Step.LLAMA` → `Step.GEMMA`;
  `llamaInstalled()`/`installLlama()` kept as `@Deprecated`.
- `ArogyaSetupUi.kt` Models page: downloads `GEMMA_URL_PRIMARY` via DownloadManager, validates exact
  file name + ~2 GB size + rejects HTML error pages, installs via `installGemma()`, verifies with
  `gemmaInstalled()`. Single source (no auto-fallback: the only standby is the gated official page).
  All user strings (EN/HI) say Gemma 3n E2B.
- `ArogyaSetupDownload.kt`: source tracking extended to the Gemma URLs.
- `ArogyaSetupMatch.kt`: new `Kind.GEMMA` (`classify` + `missingFix`); `.gguf` → `LLAMA` kept.

**Phone routing prefers Gemma**
- `ModelFileManager.preferredTextModelFile()`: AUTO now returns the installed `.litertlm` unless
  Gemma is disconnected; else `null` (was: Llama whenever installed). `GEMMA_ONLY`/`LLAMA_ONLY`
  force modes unchanged. `gemmaModelFile()` (selected bundle) pre-existed and is reused.
- `FixSightRuntimeGraph.ensureLocalModelReady()`: comment updated; logic flows from the above.
- `MainActivity.kt`: "not loaded" hint points at Setup → Gemma install.
- ARHUD Settings: phone-AI chips are now "Auto (Gemma first)" / "Gemma"; status lines and About
  screen name Gemma 3n; AR overlay caption names the Gemma ensemble.

**Labels and badges name the real model**
- `ArogyaAiUsed.step()`: new `gemma` branch → "Gemma 3n E2B (Google open weights, offline)";
  `arogyadoot • offline` bare marker → Gemma; explicit `llama` and old-record markers still parse
  as Llama (history stays honest). `short()` prefers the Gemma step for offline answers.
- `ArogyaAiExtras.ArogyaAiCatalog`: phone rows use `GEMMA` ("Google (open weights)"); `LLAMA`
  const removed (was referenced only inside the catalog).
- `ArogyaDemoScript.tools`: offline tools say "Gemma 3n E2B (Google)".
- Phone engine labels (`On-phone model (offline): <file>`) flow automatically from
  `LocalModelManager.loadedModelFileName` — no change needed.

## Verification (this turn)

- `gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**; APK is `com.arogyadoot.app` /
  `ArogyaDoot` (aapt badging), arm64-v8a native libs packaged.
- Host tests: SetupPlanTest 18/18, SetupMatchTest 14/14 (3 new Gemma checks), AiTableTest 33/33,
  ApplyTest 22/22, DemoTourTest 70/70, CloudTest 16/16, SpeedTest 18/18, FinishVisitTest 34/34.
  TableOcrTest 74/76 — the 2 failures (`narrow-register`, `setup-links`) are pre-existing at HEAD,
  unrelated to this migration.

## NOT yet verified (needs a device)

- Downloading the bundle from the owner URL on a real network.
- Genuine `.litertlm` load + text inference through the LiteRT runtime on device.
- Load time, peak/RAM behavior, first-token latency on a 6 GB phone.
- Gemma multimodal image input (Stage D wiring is code-complete, see VISION_VERIFICATION.md).

## Rollback

Each retirement is a one-line gate: `preferredTextModelFile()` else-branch (`null` → `llama`),
`ArogyaSetup.status()`/`scan()` (`gemmaInstalled` → `llamaInstalled`), Models page `GEMMA_*` →
`MODELS_*`. Deleted nothing, so `git revert` of this change set restores Llama-first behavior.
