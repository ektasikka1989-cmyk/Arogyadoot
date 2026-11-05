# AI Engine Audit — ArogyaDoot (5 Nov 2026)

Stage A of the AI engine migration. Every finding below was verified in code (no assumptions from
class names). Codecs: ✅ working+active · 🔌 implemented but disabled · 🟡 partial · ⬜ placeholder ·
❌ broken · ➖ not implemented.

## 1. Headline: both target integrations already exist

- **Gemma/LiteRT-LM**: a complete, production-hardened on-device stack. Currently the **fallback** engine
  (Llama GGUF preferred). No `.litertlm` ships in the repo or the model zip; one must be installed.
- **Groq/Qwen**: a complete cloud client with the exact required endpoint
  (`https://api.groq.com/openai/v1/chat/completions`) and a live-docs-verified model ID
  (`qwen/qwen3.8-27b`). Disabled by a **single flag** (`GroqKeyStore.GROQ_DISABLED=true`) plus a stubbed
  `groqKey()`.
- **No replacement integrations need to be built.** The migration is: provision a Gemma 3n model, flip
  routing defaults, re-enable Groq, rewire the Arogya cloud path, adjust settings UI, verify on device.

## 2. Offline: Gemma 3n / LiteRT-LM — ✅ implemented, fallback-only

| Component | File | Verdict |
|---|---|---|
| Runtime dependency | `app/build.gradle.kts:151` → `com.google.ai.edge.litertlm:litertlm-android:0.16.1` | ✅ active |
| Engine core | `AndroLLMLocalInferenceCore.kt` — `loadModel` (artifact validate → container metadata → family/template/stops → GPU→CPU probe with warmup + coherence self-test + context probe), `generateChat`, `generateEphemeral`, `generateVisionEphemeral`, `cancel*` ×3, `unload`/`shutdownAsync`, `recoverOnCpu`, `runSelfTest`, hard + first-callback timeouts | ✅ active (when a `.litertlm` is loaded) |
| Engine contract | `LocalInferenceCore.kt` — `LocalInferenceCore` interface; `LiteRtCore` (pure delegation) vs `LlamaCppCore`; covers text+vision generate, streaming callbacks, cancel, unload, self-test, CPU recovery, readiness state | ✅ active — this is the Phase-2 separation interface already |
| Manager | `LocalModelManager.kt` — `loadModel` picks engine **by file** (`.gguf`→llama else LiteRT-LM, L241-245), IO-threaded, stale-load + OOM handling (`onTrimMemory` unload), `State` machine + `lastLoadError`; `inferText`, `inferVisionImage` (real JPEG→bytes path), `generateDetached(+Stream)`, grounded/KB/planner helpers | ✅ active |
| Container stack | `androllm/compat/` (`ContainerMetadataReader`, `FlatBufferReader`, `ProtoWireReader`, `ContainerMetadata`, `ModelFamilyRegistry`/`ModelFamily`/`ModelFamilyConfig`, `ChatTemplates`, `SpecialTokens`, `StopSequenceTracker`), `androllm/catalog/ModelMetadataRegistry`, `LiteRtArtifactValidator.validateLitertLm` + `sha256` | ✅ active |
| Streaming bridge | `LitertlmFlowExt.kt` — `Conversation.safeSendMessageAsync` (callbackFlow, stop/loop guards, stall watchdog, `ThinkingConfig(false)`, `Message.user` wrap; deliberately avoids the broken SDK Flow overload) | ✅ active |
| Model management | `ModelFileManager.kt` — `gemmaModelFile()`, `preferredTextModelFile()` (GEMMA_ONLY→gemma, LLAMA_ONLY→llama, else Llama-if-installed else gemma), `ForceModelMode{AUTO,GEMMA_ONLY,LLAMA_ONLY,ENSEMBLE_ONLY,CLOUD_ONLY}`, gemma/llama disconnect toggles, `installFromUri`/`copyFromUri` (`.litertlm` >200 MB), selection persistence | ✅ active |
| Routing position | `FixSightRuntimeGraph.ensureLocalModelReady` — Llama preferred; `.litertlm` only when forced/no-Llama; Gemma standby on Llama load failure (L183-194, skipped only under LLAMA_ONLY) | ✅ active (fallback semantics) |
| Manifest | `AndroidManifest.xml:80-89` — optional `libvndksupport` + `libOpenCL` for LiteRT GPU delegate | ✅ active |
| Vision path | `LocalModelManager.inferVisionImage` (L436: downscale→JPEG→`generateVisionEphemeral`) → `AndroLLMLocalInferenceCore.generateVisionEphemeral` (L1230: `Message.user(Contents.of([Text, Content.ImageBytes]))` → `conv.sendMessage`) — **genuine LiteRT-LM multimodal input, not OCR text**; `visionSupported` set only when a vision backend initializes (L284-319, GPU→CPU→null probes for `gemma3n`/`gemma4`/VISION containers) | ✅ implemented; device proof with gemma-3n-E2B-it-int4 pending |
| Settings UI | `ARHUD.kt:373 SettingsDialog` — Phone-AI chips (Meta Llama/Gemma, Gemma enabled iff installed), Gemma `.litertlm` upload, disconnect switches, force-mode radio, self-test | 🔌 reachable only via legacy chat UI (`MainActivity:681`, opened from Arogya home `onSettings`/`onAssistant` L603/589) — **absent from Arogya-side settings** |

### Gemma gaps (what Stage C–D must provide, not build)
1. **No model artifact**: no `.litertlm` in repo, and `MetaModelStore.classify` (zip installer) accepts only
   gguf/onnx — the Meta zip cannot deliver Gemma. Install path today = legacy file picker only
   (`MainActivity:1570` `.litertlm` check → `ModelFileManager.copyFromUri`). Stage C needs a genuine
   `gemma-3n-E2B-it-int4.litertlm` (user-supplied from official source until a download is configured).
2. **Setup wizard** (`ArogyaSetupUi` ModelsPage/KeyPage) provisions Meta zip + Meta key only — no Gemma step.
3. **LiteRT-LM 0.16.1 vs Gemma 3n**: code already handles `gemma3n`/`gemma4` container types and vision
   executors; confirm against official AI Edge docs + device run in Stage C/D.
4. Default preference is Llama (`preferredTextModelFile` + setup + UI labels). Flipping the default
   (+ persisted prefs that could re-select Llama) is the core Stage F change.

## 3. Online: Groq/Qwen — 🔌 implemented, flag-disabled

| Component | File | Verdict |
|---|---|---|
| Client | `GroqChatProvider.kt` (47) — `chatText`, `chatSafetyText`, `verifyFactualAnswer`, `chatGroundedKnowledgeText`, `chatGenericKnowledgeText` (+SSE streaming w/ fallback), `chatWithExternalCapabilities`, `analyzeImageObservation`, `chatVision`×2 (Groq capped at `GROQ_MAX_IMAGES=3`), `validateModel` ("Reply with OK" probe), `selectGroundedKbRecords` (JSON-schema KB gate), `requestWithRetry` (backoff on NETWORK/SERVER only; 429 opens a cooldown circuit, no blind retry), `attemptRequest` (endpoint+body remap per key type, per-attempt timeouts, OkHttp cancel via `invokeOnCancellation`), `cancelActiveCall`, `status()/lastLatencyMs()/lastError()`, `engineLabel` | ✅ implemented; Groq branch unreachable while disabled |
| Endpoint/model | `GROQ_ENDPOINT=https://api.groq.com/openai/v1/chat/completions` (L71), `GROQ_QWEN_MODEL="qwen/qwen3.8-27b"` (L72), `groqChatBody` remaps every body to Qwen + `reasoning_effort` translation + image cap (L1863+) | ✅ matches required endpoint; ID verified below |
| Key routing | `isGroqKey = key.startsWith("gsk_")` → Groq endpoint+Qwen, else Meta endpoint+Spark (L143-146) | ✅ implemented |
| Key storage | `GroqKeyStore.kt` — AES-256/GCM AndroidKeyStore (`fixsight_groq_aes_v1`), `Engine{GROQ,META}` choice persisted (`online_engine`), legacy plaintext migration, `saveKey`/`clearKey`/`hasKey`, `keyFor`/`getKey` | 🔌 **disabled**: `GROQ_DISABLED=true` (L120) forces `engine()=META`, `groqKey()=""` (L25, real impl kept as `legacyGroqKey`), `saveKey` rejects `gsk_` (L60) |
| Owner/wiring | `AndroBaselineRuntime` constructs `GroqChatProvider(apiKey)` (L236, re-created L339 via `updateGroqApiKey`); `FixSightRuntimeGraph.updateGroqKey/setOnlineEngine/cloudKey` (L204-216) plumb Settings→store→runtime | ✅ implemented |
| Error surface | `GroqProviderStatus{READY,NO_KEY,AUTH_FAILED,RATE_LIMITED,NETWORK_ERROR,UNKNOWN}`; 401/403→AUTH (persistent), 408/5xx→retry w/ backoff, 429→cooldown circuit, per-model external cooldowns; key never logged (only statuses/latencies) | ✅ implemented |

### Groq verification notes
- `qwen/qwen3.8-27b` confirmed as a **live Groq model ID** via Groq docs (console.groq.com/docs/models;
  third-party trackers list it as preview with tools+vision). Sibling IDs exist (`qwen/qwen3-32b`,
  `qwen/qwen3.6-27b`). Stage E must confirm availability + vision support via the authenticated Models API
  for the actual account — do not hard-switch on web results alone.
- ⚠️ Architecture split: `GroqChatProvider` serves the **runtime chat path** (`AndroBaselineRuntime`
  turns: `chatText:7612`, `chatSafetyText:7151`, `chatVision:7294`, grounded/generic KB:7101-7108,
  `verifyFactualAnswer:4167`, `validateModel:356`; also `KnowledgeCloudGenerator:40`,
  `PocketRagCloudResolver:69`). The **Arogya path** (`ArogyaVisitAi.route().cloud()`) uses
  `OpenAiCompatibleClient` + `ArogyaModelEndpoints{meta, together_glimmer}` — Groq is **not wired here**.
  Stage B must choose: (a) add a Groq/Qwen `ArogyaModelEndpoint` + key plumbing (small, reversible,
  recommended), or (b) route Arogya cloud turns through `GroqChatProvider` (larger).
- Settings: legacy `SettingsDialog` has Groq key input + show/hide + test + engine choice (wired to
  `updateGroqKey`/`setOnlineEngine`, neutered by the flag). `ArogyaCloudSettingsDialog` has Meta/Together
  only; `ArogyaSetupUi` KeyPage saves a Meta key only. Phase 5/8 UI must be added on the Arogya side.

## 4. Current defaults (to be retired in Stage G, not deleted)

- **Llama (default offline)**: `LlamaCppCore` + `LlamaNative` + `src/main/cpp/pramaan_llama.cpp`
  (`libpramaan_llama.so` in APK); install via Meta zip (`MetaModelStore.Component.LLAMA`,
  `LlamaFileCheck.isComplete`); selected by default in `preferredTextModelFile`.
- **Meta cloud**: `MetaModelApi` (`spark`, `sparkWebSearch`, `sam`, `transcribe`, `isConfigured` rejects
  `gsk_`), `MetaOnline` (`api/hasInternet/ready`, WAV helper, `meta_egress.log` via `PrivacyGate`).
- **Meta local stack** (distinct from the cloud API): `MetaVisionEngine` + ONNX
  (`DINOV2/SAM_ENCODER/SAM_DECODER/DETR/CLIP_*` in `MetaModelStore`) — local observe/localize/remember for
  AR/camera; `ArogyaVisitAi.visionOk()` gates on it. Keep during migration; removal candidate for Stage I.

## 5. Call-site maps (module level — local text always flows through `LocalModelManager`)

**Every feature calling Llama** (= every local-LLM consumer while a GGUF is loaded): `ArogyaVisitAi`
(`route().phone()` → `generate()` → `readyModel()`: Ask SOP, fillForm, extractFields, familyExplain,
anmMessage, refresher, readCard halves, scanMedicine, voiceStock, organiseNotes, moreDetail);
`AndroBaselineRuntime` turns (`generateDetached`, visit-note, visual-memory, AR); `PocketRag*` local
generation; KB planners/selectors; troubleshooting text. **None call Llama directly — all go through
`LocalModelManager`, which dispatches by loaded file.** Flipping the loaded file flips every feature.

**Every feature calling Meta APIs**: `route().cloud()` (Meta/Together via `OpenAiCompatibleClient`,
streaming+deadline); runtime chat cloud turns (`GroqChatProvider` Meta branch); voice transcribe
(`ArogyaCloud.transcribe`, `AndroVoiceLoop` → `MetaOnline.transcribe`, Whisper fallback);
photo/card vision (`ArogyaPhotoVision` → `MetaOnline.spark`); SAM kit count (`SamCountDialog`,
`AndroBaselineRuntime` → `MetaOnline.sam`); setup connection test (`ArogyaSetupUi:574`,
`ArogyaCloudSettingsDialog` test button); demo/SAM labels. Offline fallbacks exist on all paths.

## 6. Preserved engines (do not touch)
- RAG/retrieval: `PocketRagEngine.answer` (+GenericKb/Hybrid retrievers), evidence assemblers, source
  verification, `verified_answers.json` — provider-agnostic, feeds any LLM.
- OCR geometry: `MLKitProcessor.process/recognizeLines` (lines/boxes), `ArogyaTableOcr` (rows/columns/cells),
  corrections + confidence — keep; Gemma vision must consume images AND map back to cells.
- Speech: `AndroWhisperRecognizer` (+`:whisper` native module), `AndroAudioRecorder`,
  `AndroVoiceAssistant.speakText` (TTS) — independent of LLM choice.
- Deterministic: danger rules, schedules, incentives, form specs, grounding validators, scrubbers.

## 7. Hidden/disabled inventory
1. `GroqKeyStore.GROQ_DISABLED=true` + `groqKey()=""` — entire Groq path.
2. Gemma-as-default — needs force/disconnect/no-Llama; setup never provisions `.litertlm`.
3. Legacy `SettingsDialog` engine+Groq UI — reachable only via legacy chat (`MainActivity:681`) /
   Arogya `onSettings`; nothing equivalent in `ArogyaCloudSettingsDialog`/`ArogyaSetupUi`.
4. `ArogyaModelEndpoints` — Meta+Together only; no Groq preset.
5. `CLOUD_ONLY`/`ENSEMBLE_ONLY` force modes exist in `ForceModelMode` — usage minimal; do not expand scope.

## 8. Risks for Stage B planning
- No Gemma artifact in repo/zip — Stage C needs a verified `gemma-3n-E2B-it-int4.litertlm` + official
  source; validate with `LiteRtArtifactValidator` + container reader, never a renamed `.task`.
- `qwen/qwen3.8-27b` is Preview per trackers — confirm + pick fallback (`qwen3-32b`?) via Models API.
- Two cloud stacks (Arogya `OpenAiCompatibleClient` vs runtime `GroqChatProvider`) — do not fork behavior;
  one decision in Stage B.
- Persisted prefs can reselect Llama/Meta (`KEY_FORCE_MODEL_MODE`, disconnects, `online_engine`,
  endpoint id, `fixsight_cloud_settings`) — migration must handle each explicitly (Phase 6.7).
- 6 GB RAM claims need device measurement (load time, peak RSS, TTFT, tok/s, vision latency) — nothing
  here substitutes for it.
