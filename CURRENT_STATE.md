# ArogyaDoot — Current State Audit (6 Nov 2026)

Read-only audit of `C:\Arogyadoot` made before any code change in this session.
Every statement below was checked against the current source, Git and build output.
Evidence levels: **Verified** (checked here) · **User-reported** · **Code present only** · **Not tested**.

## 1. Git and baseline

| Item | Finding | Evidence |
|---|---|---|
| Branch / remote | `main`, tracking `origin/main` (`github.com/ektasikka1989-cmyk/Arogyadoot`) | `git status`, `git remote -v` |
| HEAD before today | `59e0418` (still contains the troubleshooting changes) | `git log` |
| Uncommitted tree | 20 files, +70 / −624: the Candidate B restoration | `git diff --stat` |
| **Candidate B intact?** | **Yes. Verified.** `git diff` is byte-identical to `C:\Arogyadoot-recovery-20261106\candidate-B.patch` (SHA-256 `3DDC348C…60C4` both); `git apply --reverse --check` passes | sha256 of both |
| Saved today | Commit `7bdb381` + tag `baseline-candidate-B-20261106` (local only, **not pushed**) | `git log` |
| Offline copy | `C:\Arogyadoot-recovery-20261106\arogyadoot-candidateB-20261106.bundle` (full history, `git bundle verify` OK), `candidate-B-WORKING-full-app-debug.apk` (733,888,953 B, SHA-256 `69E9378D…381534F`, matches the restoration report), `ignored-assets-manifest-20261106.txt` (SHA-256 of all 378 Git-ignored asset files). All added to `MANIFEST.txt` | files on disk |

Recovery folder: all 15 manifest items are present.

## 2. Build configuration (Verified from Gradle files)

- Modules: `:app`, `:whisper`. AGP 9.2.1, Kotlin 2.0.21, compileSdk 36.1, targetSdk 36, **minSdk 24**.
- `applicationId com.arogyadoot.app`, namespace `com.example.arfas` (legacy, keep).
- ABI: `arm64-v8a` only. NDK `26.1.10909125`. `useLegacyPackaging = true`.
- Native builds: `app/src/main/cpp` (llama.cpp → `libpramaan_llama.so`, `libggml*.so`); `whisper/src/main/jni/whisper` (`libwhisper.so`). Both use `c++_shared`.
- Key deps: LiteRT-LM 0.16.1 (Gemma), ONNX Runtime 1.27.0, sherpa-onnx 1.13.4 (TTS), ML Kit OCR (Latin + Devanagari), object detection and barcode, ARCore 1.54 + SceneView 4.16.10, OkHttp 4.12.
- **Reproducibility gap:** `app/src/main/assets/**` is Git-ignored except a few folders. The 759 MB of assets (`voice` 471 MB, `semantic` 134 MB, `reranker` 130 MB, `guide` 24 MB, `clip` 1.3 MB) exist only on this computer. A clean clone builds an APK of about 98 MB that is missing speech, embeddings and the reranker. The checksum manifest above is now the reference list.

## 3. Native libraries and 16 KB pages (Verified, ELF `PT_LOAD` alignment of the working APK)

15 of 21 libraries are 16 KB aligned. **6 are 4 KB aligned and will fail on a 16 KB-page device or emulator:**

| Library | Source | Used by |
|---|---|---|
| `libpramaan_llama.so`, `libggml.so`, `libggml-base.so`, `libggml-cpu.so` | `app/src/main/cpp` (llama.cpp) | Legacy Meta Llama GGUF engine only |
| `libwhisper.so` | `:whisper` module | Every mic / speech-to-text use |
| `libc++_shared.so` | NDK r26 (picked first from our own CMake builds) | Our native libs |

The Gemma runtime (`liblitertlm_jni.so`), ONNX, sherpa, ML Kit and ARCore are all aligned already.

## 4. Active AI providers and call paths (Verified in source)

| Provider | Status | Path |
|---|---|---|
| **Gemma 3n E2B (LiteRT-LM)** offline | Active, default | `FixSightRuntimeGraph.ensureLocalModelReady` → `LocalModelManager.loadModel` → `LiteRtCore(AndroLLMLocalInferenceCore)`; slot-leak fix present in `generateEphemeral` / `generateVisionEphemeral` |
| **Groq · `qwen/qwen3.8-27b`** online | Active, optional key | `GroqChatProvider` (`api.groq.com/openai/v1/chat/completions`); visit AI via `ArogyaModelEndpoints["groq"]` + `OpenAiCompatibleClient` |
| Meta Llama 3.2 GGUF (llama.cpp) | **Still reachable**: chosen whenever the model file ends in `.gguf` (`LocalModelManager:241`); "Meta Llama did not load" standby path in `FixSightRuntimeGraph:183` | `LlamaCppCore` / `LlamaNative` |
| Meta Model API (Muse Spark, Muse Voice) | Disabled at the network layer (`MetaModelApi.META_ENABLED = false`; `ArogyaCloud.transcribe` returns early) | — |
| **Together AI · Muse Glimmer 30B** (Meta model) | **Still selectable** in "☁ Cloud or phone AI" settings and routed by `ArogyaCloud.keyFor("together")` | `ArogyaModelClient.kt:29`, `ArogyaCloudUi.kt:190` |
| Meta vision stack (DETR, CLIP, DINOv2, SAM/MobileSAM over ONNX) | **Active if its files are installed** (`MetaVisionEngine.has`), installable from Settings → "Install Meta models zip" and per-model Browse buttons | `AndroBaselineRuntime.metaVision`, `ARHUD.SettingsDialog`, `ArogyaCloudUi` counter |
| Whisper (on-phone STT) | Active | `AndroWhisperRecognizer` → `libwhisper.so` |

The setup wizard no longer downloads the Meta zip (`ArogyaSetupPlan.kt:9`); only Settings still offers it.

## 5. Speech input (Verified in source)

Every text-field mic currently opens the **modal `ArogyaDictationDialog`** (`ArogyaVisitScreen.kt:1429`): record → transcribe → review → "Confirm transcript". Used by `MicField` (person forms), the SOP composer, the visit-notes button, guided-visit speak and voice stock. The inline controller from `59e0418` was removed in Candidate B. The dialog still shows "Muse Voice Transcribe (Meta, online)" strings on a code path that can no longer run.

Note from the recovery RCA: the earlier inline-mic UX set off the Gemma slot leak through cancel/resend races. The slot fix is in the core now. Any new inline dictation must still **never auto-submit**, and it must drop Whisper markers such as `[BLANK_AUDIO]`.

## 6. Settings (Verified in source)

Two separate dialogs:
1. `ARHUD.SettingsDialog`: phone AI, Meta vision stack, Meta Llama connect/disconnect, Force Model Mode (AUTO / GEMMA_ONLY / LLAMA_ONLY), Meta zip install, six Meta model rows, KB packs + check, answer mode, online backup, retired online-voice switch, Groq key, self-test and About. One long dark scroll with no grouping.
2. `ArogyaCloudSettingsDialog` ("☁ Cloud or phone AI"): cloud-first or local-first, cloud model radio (Groq / Together Muse Glimmer), Together key, test.

## 7. Tests

- Host tests: `tools/host-tests/*.kt` (≈55 suites, kotlinc-based, `run.ps1` / `run_some.ps1`). Baseline log: `host_full_active.log`. Known broken before any change: `AddPersonTest` (stale `ArogyaPersonPhoto` reference) and `TableOcrTest` (2 historical failures).
- Instrumented (`app/src/androidTest`): `RestorationTest` and `V1CloudTest` do not compile (pre-existing). **Never run `connectedDebugAndroidTest`**: it uninstalls the app and wipes the 3.6 GB model (RCA §0).

## 8. Verified vs untested functionality

| Feature | Status |
|---|---|
| App launches, general use | User-reported working (Candidate B APK) |
| Gemma load + answer, cancel → resend | Not tested on Candidate B (slot-leak fix present, but resend-after-cancel is unproven per RCA §3) |
| RAG retrieval / citations | Code present; retrieval observed live on 6 Nov (RCA); citations not tested |
| Groq key save / Qwen answer | Not tested on Candidate B |
| STT | Modal dialog works per earlier logs; inline not present |
| OCR, visits, follow-ups, export, TTS | Not tested |
| 16 KB compatibility | **Failing**: 6 libraries are 4 KB aligned (measured) |

## 9. Unresolved defects (not fixed during this audit)

1. Mic UX is a modal record → confirm popup (user priority 1).
2. Meta features still visible and installable: Settings Meta stack, Meta Llama, Together Muse Glimmer, Meta strings in error messages (user priority 2).
3. 16 KB warning: 6 native libraries are 4 KB aligned (user priority 3).
4. Emulator crash while saving the Groq key: root cause unproven, no logcat. Prime code suspect: `GroqKeyStore.saveKey()` runs Android Keystore on the UI thread with no try/catch. A 16 KB-page emulator would also kill the app at the first mic use (`libwhisper.so`) (user priority 4).
5. Build depends on 759 MB of Git-ignored local assets.
6. Pre-existing broken tests (`AddPersonTest`, `TableOcrTest` ×2, `RestorationTest`, `V1CloudTest`).

## 10. Stabilization plan (one fix per commit, in the owner's priority order)

1. **Inline dictation.** Mic in each field records and writes straight into that field; no popup, never auto-submit. Reuses Whisper. Host test for cursor insert and marker stripping.
2. **Remove Meta and simplify Settings.** Delete the Meta model install, Meta vision stack, Meta Llama and Force-mode Llama options, plus the Together Muse option. Group Settings into clear cards without losing any non-Meta function. Gate the Meta vision engine off at runtime. Existing files on the phone are left alone, never deleted.
3. **16 KB.** Stop building and packaging llama.cpp (removes 4 of the 6 bad libraries), link `libwhisper` with `-z max-page-size=16384`, and make sure the packaged `libc++_shared.so` is 16 KB aligned. Re-measure the APK.
4. **Emulator crash.** Guard the Keystore save (no crash; show a "save failed" message instead of false success). The 16 KB fix covers the Whisper linker death. Ask for logcat to confirm.
5. Later: asset provisioning documentation, fixing the stale tests, device acceptance matrix (handover §11).

## 11. Changes made after the audit (6 Nov 2026, one commit each)

| Commit | Change | Verified by |
|---|---|---|
| `7ebae56` | Inline mic dictation everywhere (no popup; never auto-sends; strips `[BLANK_AUDIO]`) | DictationTextTest 13/13, compile |
| `214b341` | Meta removed (Settings rows, zip install, Together Muse, runtime gate `META_MODELS_ENABLED=false`); Settings redesigned as cards; visit "Check online" fixed (it required a Meta key) | Host tests (TableOcr 74/76 = baseline), compile |
| `db1610c` | 16 KB: llama.cpp no longer built, NDK 27, Whisper/ggml flexible page sizes; **20/20 libs 16 KB aligned** (measured) | ELF check of new APK, assembleDebug |
| `ed6793c` | Groq key save no longer crashes on Keystore failure; verified save | compile |

New APK: `app/build/outputs/apk/debug/app-debug.apk` (733,848,487 B, 444 assets, 20 libs), copy in the recovery folder.
**Not yet verified on a device or emulator:** inline mic, the new Settings, 16 KB emulator launch, Groq key save.
Still open: Meta labels inside the demo tour and AI-used badges; dead llama/Meta Kotlin classes; emulator crash root cause (needs logcat).
