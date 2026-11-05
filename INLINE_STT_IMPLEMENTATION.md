# Inline STT Implementation — ArogyaDoot

Date: 5 Nov 2026. Status: code-complete, builds, host-tested. **Device verification pending.**

Mic buttons beside text fields no longer open the transcription popup: speech appears directly in
the field, with live partials, cursor insertion, and immediate editing. One reusable component
serves every field; the working Whisper engine is reused unchanged.

## New files

- `ArogyaDictationText.kt` — pure cursor-insert (`insertAtCursor`: spacing, selection replace,
  blank-safe, bounds-clamped). Host-tested, no Android/Compose types.
- `ArogyaInlineDictation.kt` — `InlineDictationController` (IDLE/LISTENING/WORKING/ERROR,
  streaming Whisper session + `AndroAudioRecorder`, 1.2 s partial polls, 60 s cap, no-speech
  detection, single-flight across fields, release on dispose),
  `rememberInlineDictationController()`, `DictationMicButton` (own permission ask, 🎤/■ states).
- `tools/host-tests/DictationTextTest.kt` — 9/9 passing (registered in both runners).

## Fields converted (popup removed)

| Field | Change |
|---|---|
| Register/beneficiary fields incl. Notes (`MicField`, ~10 fields) | Internal TextFieldValue + inline mic; dictate-callback plumbing deleted (`PatientFormFields`, `ManualPersonStep`, `AddPersonReview`, both dictate-target blocks) |
| Ask SOP / visit AI question (`SopComposer`) | Internal TextFieldValue + inline mic; `onMic` callback + `questionDictation` machinery deleted |
| Stock voice entry (`VoiceStockDialog`) | Nested dictation popup deleted; transcript dictated inline into an editable field, still auto-parsed on final |

## Deliberately kept popup (explicit workflow exception)

`ArogyaDictationDialog` remains for speak→AI-submit flows where the workflow requires a full note
before acting: guided "Speak visit note" (→ form fill) and "Speak about this visit" (→ note append
+ optional organise). These are not text-field dictation.

## Behavior contract (as implemented)

- Existing text preserved; insert at cursor/selection with smart spacing; cursor lands after insert.
- Partials re-apply from the start snapshot (no duplication); final replaces partial.
- Typing while listening stops the session (typed words win); partial text stays editable.
- Starting one field stops any other (speech can never land in the wrong field); leaving the
  screen releases mic + Whisper. Nothing auto-submits.
- Permission denial, missing mic/model, no-speech and failures show inline error lines (EN/HI).
- Language follows the saved voice setting (Auto/Hindi/English); explicit Hindi/English re-decodes
  (keeps the Urdu-script fix). Offline: fully on-phone (Whisper + recorder only).
