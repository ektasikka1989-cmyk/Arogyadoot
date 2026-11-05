# Groq / Qwen Integration Report — ArogyaDoot

Date: 5 Nov 2026. Status: **code-complete, build-verified. Device + account verification pending.**

Online engine is Groq (`https://api.groq.com/openai/v1`), model `qwen/qwen3.8-27b` — the same id the
existing `GroqChatProvider` already used (`GROQ_QWEN_MODEL`), now also the default Arogya cloud
preset. Meta Muse Spark is retired from routing (see "Retirement" below).

## What changed

**Key plumbing re-enabled**
- `GroqKeyStore.kt`: `GROQ_DISABLED = false`; `groqKey()` returns the saved key again (encrypted
  store + legacy migration kept); `saveKey()` accepts `gsk_` keys and selects the Groq engine.
- `ArogyaSetupPlan.keyProblem()`: accepts Groq keys, rejects `LLM|` Meta keys with an explanatory
  message; `KEY_PAGE` → `https://console.groq.com/keys`.
- Setup wizard step 3 (`ArogyaSetupUi.kt` Key page): Groq words (EN/HI), key link to Groq console,
  **live test calls the Groq preset** (`OpenAiCompatibleClient.completeStream`, 25 s budget,
  401/403 → "rejected", timeout/offline → save-anyway offer). `ArogyaSetup.status()` reads the
  Groq key.

**Arogya cloud preset (forms, visit AI, photo reading, cloud settings)**
- `ArogyaCloudApi.kt`: new `GroqCloud` (`BASE`, `QWEN = "qwen/qwen3.8-27b"`; id pending Stage E
  Models-API confirmation).
- `ArogyaModelClient.kt`: new first preset `groq` ("Qwen3.8 27B — Groq", `keyFor = "groq"`,
  `metaModel = false`); `OpenAiCompatibleClient.complete()`/`requestBody()` accept optional
  `images` (OpenAI `image_url` parts; text calls unchanged).
- `ArogyaCloudUi.kt`: new `ArogyaCloud.groqKey()`; `keyFor()` routes `"groq"` to it;
  `textEndpoint()` defaults to `"groq"` and maps stale `"meta"` prefs to Groq; `keyFor` else-branch
  returns `""` so the retired Meta endpoint is never usable; Cloud settings dialog lists Groq (+
  Together Glimmer), hides `meta`, defaults to `groq`; status/mode-switch/hasKey read the Groq key.
- `ArogyaVisitAi.kt` route()/fillForm(): cloud runs through the chosen endpoint (Groq by default);
  not-used labels name the endpoint and reason (`no Groq key saved`, timeouts, check failures —
  endpoint-aware for Together/Meta leftovers).

**Ask SOP cloud stack (pre-existing Groq path, now actually reachable)**
- `GroqKeyStore.engine()`: always `GROQ` (stale saved `META` choice falls through; no pref rewrite).
- `FixSightRuntimeGraph.cloudKey()`: Groq key only (Meta fallback removed).
- `GroqChatProvider` already routes `gsk_` keys to the Groq endpoint with `qwen/qwen3.8-27b` and
  labels it `Groq · Qwen3.8 27B` — unchanged, now fed a real key. `ArogyaAiUsed` parses
  qwen/groq/`arogyadoot • online` labels into Qwen steps; `ArogyaCloudOutcome.why()` reasons are
  engine-aware ("no Groq key saved", Groq 401/rate-limit words).
- ARHUD Settings → Online AI: relabelled to Groq key (`gsk_…`); save/test/clear already route via
  `updateGroqKey()` → `GroqChatProvider`.

**Retirement**
- `MetaModelApi.META_ENABLED = false`: `isConfigured` false everywhere; `execute()` and `sam()`
  throw `ApiException(403)` before any bytes leave the phone. All remaining call sites are gated
  (`MetaOnline.ready()` false), rewired (PhotoVision → Groq), or dead branches; the two platform
  direct callers (`camera_find`, voice loop) are inside try/catch or `ready()` checks.
- Online voice (Muse Voice): `ArogyaCloud.transcribe()` returns null (log `muse_retired`); all
  dictation callers fall back to on-phone Whisper; settings toggle relabelled retired + disabled.
- Online counting (SAM 3.1): `SamCountDialog` always uses the on-phone counter / tap-to-count;
  demo shelf step counts by hand. `MobileSAM + DINOv2` (Meta open weights, on-phone) stay and are
  still labelled honestly as such.

## Verification (this turn)

- BUILD SUCCESSFUL; host tests listed in GEMMA_MIGRATION_REPORT.md (CloudTest incl. new groq-preset
  and vision-request checks; TableOcrTest incl. new groq-no-key/groq-steps checks).

## NOT yet verified (needs device + Groq account)

- `qwen/qwen3.8-27b` exists on the account (confirm via authenticated Models API; change
  `GroqCloud.QWEN` + `GROQ_QWEN_MODEL` together if different).
- Auth, text generation, streaming, Hindi/Hinglish, structured output, 401/rate-limit/timeout
  behavior end to end.
- Qwen vision: whether the model consumes `image_url` parts (PhotoVision sends them; Stage E must
  prove a real photo reading, else mark cloud vision unavailable).

## Rollback

`GROQ_DISABLED = true` + `textEndpoint()` default `"groq"` → `"meta"` + `keyFor` else → `metaKey(c)`
+ `META_ENABLED = true` restores the Meta online path. No keys are migrated or deleted by this
change set (saved Meta keys remain readable in settings).
