# Gemma Lifecycle Audit — ArogyaDoot

Date: 5 Nov 2026. Method: code audit (no device). Verdict: **lifecycle is sound; one real bug
class found and fixed (uncaught keystore throw); no lifecycle change needed for memory.**

## Load triggers (who loads Gemma, when)

| Caller | Thread | When | Verdict |
|---|---|---|---|
| `MainActivity:457 initialiseAsync` | IO scope | app start | OK — background, once per process |
| `AndroVoiceForegroundService:256` | service | voice service start | OK — same mutex, no double load |
| `ensureLocalModelReady` from AI routes (`ArogyaVisitAi.route`, modelInfo checks) | IO | first AI use | OK — returns fast when already ready |
| Setup wizard, Settings, key entry, keyboard | — | — | **No load path.** Settings only reads `gemmaInstalled()` (file check). |

## Single-instance guarantees

- `FixSightRuntimeGraph.ensureLocalModelReady()`: `modelLoadMutex` — "exactly one load attempt at
  a time regardless of Activity/Service races."
- `LocalModelManager.loadModel()`: `State.LOADING` guard + stale-loading recovery; mismatch
  (`loadedModelFileName != preferred`) unloads before reload.
- Standby path (preferred fails → other installed model) skips when the standby is the same file;
  with Gemma-first preferred it almost never fires. Force modes (`GEMMA_ONLY`/`LLAMA_ONLY`) intact.
- Whisper: per-session `AndroWhisperRecognizer` with `release()` in `finally` (dialog, inline
  controller, demo flows). No global Whisper singleton to leak.

## Cancellation / background / low memory

- `loadModel` progress callbacks are fire-and-forget UI hints; the load mutex serializes callers.
- No explicit "unload on background" — the model stays resident by design (reload cost >> hold
  cost on 6 GB phones). No arbitrary memory threshold imposed (per task constraints).
- Failed loads return false with an `onProgress` message; the downloaded bundle is never deleted
  by a failed load (`installGemma` and `ensureLocalModelReady` only read it).

## The bug that was fixed (Part B outcome)

No memory pressure was proven (no device evidence), so no load/unload policy change was made.
The only lifecycle-adjacent defect found by audit: key persistence could kill the process (see
EMULATOR_MEMORY_RCA.md H1/F2). Fixed by catching keystore failures at `updateGroqKey()` and
verifying persistence at both save sites. Settings/key entry now provably cannot initialize or
disturb the model.

## What would change this assessment

- Logcat showing `RUNTIME_GRAPH_MODEL_LOAD_BEGIN` with no matching END + process death → revisit
  with a load watchdog + explicit low-memory message (design only, after proof).
- Proof of 16K-page linker deaths → native rebuild task for `libwhisper`/`libpramaan_llama`
  (Gemma's `liblitertlm_jni` is already 16K-clean).
