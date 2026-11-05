# Voice Input Regression — ArogyaDoot inline dictation

Date: 5 Nov 2026. `assembleDebug` BUILD SUCCESSFUL; DictationTextTest 9/9. Device runs pending.

## 18 checks (Part D) — honest status

| # | Check | Status | Note |
|---|---|---|---|
| 1–3 | Mic in Search SOP / AI question / visit notes | CODE-COMPLETE | same component; live run pending |
| 4–6 | Hindi / English / Hinglish dictation | CODE-COMPLETE | saved voice setting + Whisper auto; accuracy not claimed |
| 7 | Partial + final handling | HOST-TESTED (insert) + CODE | partial re-apply from snapshot; live timing pending |
| 8–9 | Typed-text preservation, cursor insert | HOST-TESTED 9/9 | `insertAtCursor` incl. Hindi, selection, bounds |
| 10–11 | Cancellation, permission denied | CODE-COMPLETE | stop/cancel paths + denial error line; live run pending |
| 12 | Offline behavior | PRESERVED | Whisper + recorder only; no network added |
| 13–14 | Navigation during dictation, restart | CODE-COMPLETE | dispose releases; restart = idle by construction |
| 15 | Gemma + STT memory | NOT TESTED | needs device meminfo during both resident |
| 16–18 | Groq Settings × Gemma loaded/unloaded/absent | CODE-COMPLETE | key save hardened (no-throw + verify); crash repro pending |

## Key-entry crash question (Part D §18)

Code audit says the crash is producible without Gemma resident (keystore throw path), and Settings
provably never loads a model — so "only when Gemma is resident" is unlikely, but NOT disproven.
Decisive test on the emulator, in order: (1) fresh install, skip setup, save key with no model →
(2) download Gemma, background load idle, save key → (3) save key mid-load. Capture logcat each
time; the fixed build must show either success or "keystore error", never a crash.

## Non-regressions

RAG, OCR, TTS/playback, forms, registers, deadlines, fallback order untouched. Deleted popup paths
are preserved as dead `if (false)` branches + comments for one-click revert; no file was removed.
